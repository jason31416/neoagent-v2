package cn.jason31416.providers

import cn.jason31416.agent.ContentBlock
import cn.jason31416.agent.ContentType
import cn.jason31416.agent.ContextEntry
import cn.jason31416.agent.ModelCapability
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * DeepSeek 的 OpenAI-compatible 适配。
 *
 * 只覆盖 DeepSeek 自己的字段：
 * - 响应 delta 里的 reasoning_content；
 * - assistant 历史里的 reasoning_content 回传；
 * - DeepSeek 的 effort 词表；
 * - inline 图片先传 Files API，换成 file_id（1h 过期，只在实例内缓存）。
 */
open class DeepSeekProvider(
    apikey: String,
    baseurl: String = "https://api.deepseek.com",
    id: String = "deepseek",
) : OpenaiCompatibleModelProvider(
    id = id,
    baseurl = baseurl,
    apikey = apikey,
) {

    private data class CachedFile(val id: String, val expiresAt: Long)

    /** sha256(data URI) -> 已上传的文件；进程退出即丢失，过期就重新上传。 */
    private val files = HashMap<String, CachedFile>()
    private val filesLock = Mutex()

    override fun convertAssistant(entry: ContextEntry): JsonObject = buildJsonObject {
        super.convertAssistant(entry).forEach { (key, value) -> put(key, value) }
        // DeepSeek 在 thinking + tools 时要求回传；空串兜底。
        put("reasoning_content", entry.reasoningContent ?: "")
    }

    override fun modelCapabilities(modelID: String): ModelCapability {
        return ModelCapability(
            maxImage = 600
        )
    }

    override fun consumeChunk(
        chunk: JsonObject,
        acc: AssistantAccumulator,
    ): String? {
        val delta = (chunk["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("delta") as? JsonObject

        (delta?.get("reasoning_content") as? JsonPrimitive)
            ?.contentOrNull
            ?.let(acc::appendReasoning)

        return super.consumeChunk(chunk, acc)
    }

    override fun applyEffort(body: JsonObjectBuilder, effort: String?) {
        val raw = effort?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        val mapped = when (raw) {
            "minimal" -> "low"
            "medium", "xhigh" -> "high"
            "ultra" -> "max"
            else -> raw
        }
        body.put("reasoning_effort", mapped)
    }

    // ---------------------------------------------------------------- files

    /**
     * 把 data: URI 图片换成 Files API 的 file_id。
     *
     * 同一张图在历史里可能出现很多次，这里按 data URI 去重，一次请求只传一次。
     * 上传失败就保留原字符串，走 inline base64，只 warn 不打断对话。
     */
    override suspend fun prepareContext(context: List<ContextEntry>): List<ContextEntry> {
        val images = context
            .flatMap { it.content }
            .filter { it.type == ContentType.IMAGE && it.content.startsWith("data:") }
            .distinctBy { it.content }

        if (images.isEmpty()) return context

        val replacements = images.associate { block ->
            block.content to (resolveFileId(block.content) ?: block.content)
        }

        return context.map { entry ->
            entry.copy(
                content = entry.content.map { block ->
                    if (block.type == ContentType.IMAGE) {
                        block.copy(content = replacements[block.content] ?: block.content)
                    } else {
                        block
                    }
                }.toMutableList()
            )
        }
    }

    override fun convertImage(image: ContentBlock): JsonObject =
        if (image.content.startsWith("file-api-")) {
            buildJsonObject {
                put("type", "file")
                put("file_id", image.content)
            }
        } else {
            super.convertImage(image)
        }

    /** 返回可用的 file_id；上传失败返回 null，调用方退回 inline。 */
    private suspend fun resolveFileId(dataUri: String): String? {
        val key = sha256(dataUri)
        val now = Instant.now().epochSecond

        val cached = filesLock.withLock { files[key] }
        if (cached != null && cached.expiresAt - now > EXPIRY_MARGIN_SECONDS) {
            return cached.id
        }
        if (cached != null) {
            filesLock.withLock { files.remove(key) }
        }

        val uploaded = try {
            uploadImage(dataUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("image upload failed (${key.take(8)}): ${e.javaClass.simpleName}: ${e.message}")
            warn("falling back to inline base64 for this request")
            return null
        }

        filesLock.withLock { files[key] = uploaded }
        return uploaded.id
    }

    private suspend fun uploadImage(dataUri: String): CachedFile {
        val (mime, bytes) = decodeDataUri(dataUri)
        val boundary = "----neoagent-${UUID.randomUUID()}"
        val body = multipartBody(boundary, "image." + mime.substringAfter('/'), mime, bytes)

        val request = HttpRequest.newBuilder(URI.create(filesEndpoint()))
            .timeout(Duration.ofMinutes(10))
            .header("Authorization", "Bearer $apikey")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()

        val response = httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .awaitResponse()

        if (response.statusCode() !in 200..299) {
            throw OpenaiHttpException(response.statusCode(), response.body())
        }

        val root = Json.parseToJsonElement(response.body()).jsonObject
        val id = (root["id"] as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("upload response has no id: ${response.body().take(200)}")
        val createdAt = (root["created_at"] as? JsonPrimitive)?.longOrNull
            ?: Instant.now().epochSecond
        val expiresAt = (root["expires_at"] as? JsonPrimitive)?.longOrNull
            ?: (createdAt + UPLOAD_TTL_SECONDS)

        return CachedFile(id, expiresAt)
    }

    /** 子类或测试可以改上传地址。 */
    protected open fun filesEndpoint(): String = baseurl.trimEnd('/') + "/files"

    private fun decodeDataUri(dataUri: String): Pair<String, ByteArray> {
        val comma = dataUri.indexOf(',')
        require(comma > 0) { "malformed data URI" }

        val meta = dataUri.substring("data:".length, comma)
        require(meta.endsWith(";base64")) { "data URI is not base64: ${meta.take(40)}" }

        val mime = meta.removeSuffix(";base64").ifBlank { "application/octet-stream" }
        val bytes = Base64.getDecoder().decode(dataUri.substring(comma + 1))
        return mime to bytes
    }

    private fun multipartBody(
        boundary: String,
        filename: String,
        mime: String,
        bytes: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream(bytes.size + 512)
        fun text(value: String) = out.write(value.toByteArray(StandardCharsets.UTF_8))

        fun field(name: String, value: String) {
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            text(value)
            text("\r\n")
        }

        field("purpose", "user_data")
        field("expires_after[anchor]", "created_at")
        field("expires_after[seconds]", UPLOAD_TTL_SECONDS.toString())

        text("--$boundary\r\n")
        text("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
        text("Content-Type: $mime\r\n\r\n")
        out.write(bytes)
        text("\r\n--$boundary--\r\n")

        return out.toByteArray()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun warn(message: String) {
        System.err.println("[deepseek] $message")
    }

    private companion object {
        const val UPLOAD_TTL_SECONDS = 3600L
        const val EXPIRY_MARGIN_SECONDS = 60L
    }
}
