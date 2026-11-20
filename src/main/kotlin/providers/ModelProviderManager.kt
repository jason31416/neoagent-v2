package cn.jason31416.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class ProviderInput(
    val id: String?=null,
    val name: String,
    val type: String,
    val baseUrl: String,
    val models: List<SavedModel>,
    val apiKey: String? = null,
    val clearApiKey: Boolean = false,
)

@Serializable
private data class ProviderConfig(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<SavedModel>,
)

@Serializable
private data class ProviderFile(val version: Int = 1, val providers: List<ProviderConfig>)

@Serializable
data class ProviderView(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val status: String,
    val apiKeySet: Boolean,
    val apiKeyPreview: String?,
    val models: List<SavedModel>,
    val testError: String? = null,
)

class ProviderNotFoundException : IllegalArgumentException("提供商不存在")
class ProviderNotLoadedException : IllegalStateException("尚未加载提供商配置，请先调用 reload()")

/**
 * File I/O happens only on explicit reload or configuration changes.
 * Call reload() explicitly before use; startup integration is intentionally left to the caller.
 */
object ModelProviderManager {
    private val configPath = Path.of("config", "providers.json").toAbsolutePath().normalize()
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val lock = Any()

    private data class Entry(
        val config: ProviderConfig,
        val provider: ModelProvider,
        val status: String = "untested",
        val testError: String? = null,
    )

    private var entries: Map<String, Entry>? = null

    private fun loaded(): Map<String, Entry> = entries ?: throw ProviderNotLoadedException()

    val providers: Map<String, ModelProvider>
        get() = synchronized(lock) { loaded().mapValues { it.value.provider } }

    fun getModel(providerId: String, modelId: String) = synchronized(lock) {
        loaded()[providerId]?.provider?.getModel(modelId)
    }

    fun getModel(modelKey: String) = synchronized(lock) {
        loaded()[modelKey.substringBefore("/")]?.provider?.getModel(modelKey.substringAfter("/"))
    }

    fun listProviders(): List<ProviderView> = synchronized(lock) {
        loaded().values.map { view(it) }
    }

    /** Build everything first; invalid files never replace the running configuration. */
    fun reload() = synchronized(lock) {
        val file = if (Files.notExists(configPath)) ProviderFile(providers = emptyList())
        else json.decodeFromString<ProviderFile>(Files.readString(configPath))
        require(file.version == 1) { "不支持的提供商配置版本" }
        require(file.providers.map { it.id }.distinct().size == file.providers.size) { "提供商 ID 重复" }
        val next = linkedMapOf<String, Entry>()
        file.providers.forEach { raw ->
            val config = normalize(raw)
            next[config.id] = Entry(config, instantiate(config))
        }
        entries = next
    }

    fun create(input: ProviderInput): ProviderView = synchronized(lock) {
        val current = loaded()
        require(input.id !in current) { "提供商 ID 已存在：${input.id}" }
        val config = normalize(ProviderConfig(
            input.id!!, input.name, input.type, input.baseUrl,
            keyFor(input, ""), input.models,
        ))
        val entry = Entry(config, instantiate(config))
        commit(current + (config.id to entry))
        view(entry)
    }

    fun update(id: String, input: ProviderInput): ProviderView = synchronized(lock) {
        val current = loaded()
        val old = current[id] ?: throw ProviderNotFoundException()
        val config = normalize(ProviderConfig(
            id, input.name, input.type, input.baseUrl, keyFor(input, old.config.apiKey), input.models,
        ))
        val connectionChanged = config.type != old.config.type || config.baseUrl != old.config.baseUrl ||
            config.apiKey != old.config.apiKey || config.models != old.config.models
        val next = Entry(
            config, instantiate(config),
            if (connectionChanged) "untested" else old.status,
            if (connectionChanged) null else old.testError,
        )
        commit(current + (id to next))
        view(next)
    }

    fun delete(id: String) = synchronized(lock) {
        val current = loaded()
        if (id !in current) throw ProviderNotFoundException()
        commit(current - id)
    }

    internal fun addModel(provider: ModelProvider, model: SavedModel) = synchronized(lock) {
        val current = loaded()
        val entry = current[provider.id] ?: throw ProviderNotFoundException()
        require(entry.provider === provider) { "提供商配置已更新，请重新获取 Provider" }
        val models = entry.config.models.associateBy { it.id }.toMutableMap()
        models[model.id] = model
        val config = normalize(entry.config.copy(models = models.values.toList()))
        commit(current + (provider.id to Entry(config, provider)))
        provider.loadModels(config.models)
    }

    internal fun removeModel(provider: ModelProvider, modelId: String): Boolean = synchronized(lock) {
        val current = loaded()
        val entry = current[provider.id] ?: throw ProviderNotFoundException()
        require(entry.provider === provider) { "提供商配置已更新，请重新获取 Provider" }
        if (entry.config.models.none { it.id == modelId }) return@synchronized false
        val config = entry.config.copy(models = entry.config.models.filterNot { it.id == modelId })
        commit(current + (provider.id to Entry(config, provider)))
        provider.loadModels(config.models)
        true
    }

    suspend fun fetchRemoteModels(id: String): List<SavedModel> {
        val entry = synchronized(lock) { loaded()[id] ?: throw ProviderNotFoundException() }
        return withContext(Dispatchers.IO) { entry.provider.fetchAvailableModels().map { SavedModel.from(it) } }
    }

    /** Check /models accessibility and authentication; no chat request or capability parsing. */
    suspend fun test(id: String): ProviderView {
        val entry = synchronized(lock) { loaded()[id] ?: throw ProviderNotFoundException() }
        val error = try {
            withContext(Dispatchers.IO) { entry.provider.checkConnection() }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            safeError(e, entry.config.apiKey)
        }
        return synchronized(lock) {
            val current = loaded()
            val latest = current[id] ?: throw ProviderNotFoundException()
            // Ignore a result from a provider replaced while its request was running.
            if (latest !== entry) return@synchronized view(latest)
            val next = latest.copy(status = if (error == null) "connected" else "error", testError = error)
            entries = current + (id to next)
            view(next)
        }
    }

    private fun keyFor(input: ProviderInput, old: String): String {
        require(!(input.clearApiKey && input.apiKey != null)) { "密钥修改参数冲突" }
        return if (input.clearApiKey) "" else input.apiKey ?: old
    }

    private fun normalize(config: ProviderConfig): ProviderConfig {
        require(config.id.trim().isNotBlank()) { "提供商 ID 不能为空" }
        require(config.name.trim().isNotBlank()) { "提供商名称不能为空" }
        require(!config.id.contains("/")) { "提供商 ID 不能包含 '/'" }
        require(config.type in setOf("openai-compatible", "deepseek")) { "不支持的提供商类型" }
        val base = config.baseUrl.trim().trimEnd('/')
        val uri = try { URI(base) } catch (_: Exception) { throw IllegalArgumentException("接口地址不合法") }
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null) { "接口地址必须是 HTTP(S) Base URL" }
        val models = config.models.map { model ->
            require(model.id.isNotBlank()) { "模型 ID 不能为空" }
            require(model.contextLength > 0) { "上下文长度必须大于 0" }
            require(model.reasoningEfforts.all { it.isNotBlank() }) { "思考强度不能为空" }
            model.copy(
                id = model.id.trim(), displayName = model.displayName.trim().ifEmpty { model.id.trim() },
                reasoningEfforts = model.reasoningEfforts.map { it.trim() }.distinct(),
            )
        }
        require(models.map { it.id }.distinct().size == models.size) { "同一提供商内的模型 ID 不能重复" }
        return config.copy(name = config.name.trim(), baseUrl = base, models = models)
    }

    private fun instantiate(config: ProviderConfig): ModelProvider {
        val provider = when (config.type) {
            "openai-compatible" -> OpenaiCompatibleModelProvider(config.id, config.baseUrl, config.apiKey)
            "deepseek" -> DeepSeekProvider(config.apiKey, config.baseUrl, config.id)
            else -> throw IllegalArgumentException("不支持的提供商类型")
        }
        provider.loadModels(config.models)
        return provider
    }

    /** Caller holds the lock. Only publish memory after the complete file is replaced. */
    private fun commit(next: Map<String, Entry>) {
        val content = json.encodeToString(ProviderFile(providers = next.values.map { it.config }))
        Files.createDirectories(configPath.parent)
        val temp = Files.createTempFile(configPath.parent, "providers-", ".tmp")
        // Both paths belong to this resolved configuration directory.
        try {
            Files.writeString(temp, content)
            try {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, configPath, StandardCopyOption.REPLACE_EXISTING)
            }
            entries = next
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun view(entry: Entry): ProviderView {
        val config = entry.config
        return ProviderView(
            config.id, config.name, config.type, config.baseUrl, entry.status,
            config.apiKey.isNotEmpty(), when {
                config.apiKey.isEmpty() -> null
                config.apiKey.length <= 8 -> "••••••••"
                else -> "••••••••" + config.apiKey.takeLast(4)
            },
            config.models.map { it.copy(reasoningEfforts = it.reasoningEfforts.toList()) }, entry.testError,
        )
    }

    private fun safeError(error: Exception, apiKey: String): String = when (error) {
        is OpenaiHttpException -> when (error.statusCode) {
            401, 403 -> "服务可达，但认证失败或没有访问权限（HTTP ${error.statusCode}）"
            404 -> "找不到 /models 接口，请检查 Base URL（HTTP 404）"
            429 -> "服务请求受限，请稍后重试（HTTP 429）"
            else -> "服务接口返回 HTTP ${error.statusCode}，请检查接口地址和服务状态"
        }
        is java.net.http.HttpTimeoutException -> "连接测试超时（30 秒）"
        else -> {
            val message = error.message.orEmpty()
            if (apiKey.isNotEmpty() && message.contains(apiKey)) "连接失败（${error.javaClass.simpleName}）"
            else "连接失败（${error.javaClass.simpleName}），请检查网络及接口配置"
        }
    }
}
