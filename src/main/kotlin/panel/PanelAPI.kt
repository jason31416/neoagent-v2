package cn.jason31416.panel

import cn.jason31416.NeoagentRuntime
import cn.jason31416.NeoagentRuntime.generateSecret
import cn.jason31416.database.DatabaseHandler
import cn.jason31416.database.MetaTable
import cn.jason31416.database.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.kotlincrypto.hash.sha2.SHA256
import java.security.SecureRandom
import java.util.Date
import kotlin.time.Duration.Companion.days

var frontendAuthorizationToken: String? = null

@Serializable
data class RegisterBody(val secret: String, val password: String)
@Serializable
data class LoginBody(val password: String)
fun Routing.apiRoutes() {
    get("/api") {
        call.respondObj(
            "status" to "ok"
        )
    }

    // authentications
    get ("/api/auth/is_registered") {
        var pswHash = ""
        transaction(db){
            MetaTable.selectAll()
                .where { MetaTable.key eq "password" }
                .firstOrNull()
                ?.let { ret ->
                    pswHash = ret[MetaTable.value]
                }
        }
        call.respondObj(
            "status" to pswHash.isNotEmpty()
        )
    }

    post ("/api/auth/register") {
        val body = runCatching{ call.receive<RegisterBody>() }.getOrNull()
        if(body == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        val secret = body.secret
        if(NeoagentRuntime.secret != secret){
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val password = body.password

        transaction(db){
            MetaTable.upsert {
                it[MetaTable.key] = "password"
                it[MetaTable.value] = SHA256().digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
            }
        }

        NeoagentRuntime.secret = null
        frontendAuthorizationToken = generateSecret()
        call.respondObj(
            "success" to true,
            "token" to frontendAuthorizationToken,
        )
    }

    post("/api/auth/login") {
        val body = runCatching{ call.receive<LoginBody>() }.getOrNull()
        if(body == null) {
            call.respond(HttpStatusCode.BadRequest)
            return@post
        }
        var pswHash = ""
        transaction(db){
            MetaTable.selectAll()
                .where { MetaTable.key eq "password" }
                .firstOrNull()
                ?.let { ret ->
                    pswHash = ret[MetaTable.value]
                }
        }

        if(SHA256().digest(body.password.toByteArray()).joinToString("") { "%02x".format(it) } != pswHash){
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }

        NeoagentRuntime.secret = null
        frontendAuthorizationToken = generateSecret()
        call.respondObj(
            "success" to true,
            "token" to frontendAuthorizationToken,
        )
    }

    authenticate("panel-auth") {
        // For the main panel
        get("/api/status") {
            val tmpToday = System.currentTimeMillis()/1000/86400
            call.respondObj(
                    "version" to NeoagentRuntime.version,
                    "statistics" to mapOf(
                        "running" to System.currentTimeMillis() - NeoagentRuntime.launchTime,
                        "agents" to 1, // todo: How many active agents are running
                        "channels" to 1, // todo: How many active channels are connected
                        "dailyTokenUsage" to mapOf<Long, Int>(tmpToday-5 to 100000, tmpToday-4 to 10000, tmpToday-3 to 4013232, tmpToday-2 to 5421399, tmpToday-1 to 823102, tmpToday to 4492137), // todo: bar graph/line graph of token usage
                        "dailyMessagesHandled" to mapOf<Long, Int>(tmpToday-5 to 1, tmpToday-4 to 1, tmpToday-3 to 4, tmpToday-2 to 5, tmpToday-1 to 1, tmpToday to 4), // todo: bar graph/line graph of number of messages handled each day
                        "systemInfo" to mapOf(
                            "osInfo" to System.getProperty("os.name"),
                            "systemTime" to Date().toLocaleString(),
                            "cpuPercentage" to 45,
                            "memory" to 14000,
                        )
                    )
                )
        }

        // todo: configuration api

        /*
    Will have:
    - main config
    - agent configs (and the ability to add/remove agents, etc.)
    - channel configs (structure vary based on channel)
    - model provider/model configs (require interactive ping functions that allow fetching and testing of models)
     */

        /* ==================================================================
         * 模型提供商 / 模型（临时内存实现，重启进程后回到下面的默认值）
         * ==================================================================
         *
         * 前端已经按下面的结构实现，你替换成真正的存储时保持字段名不变即可。
         *
         * 1. Provider（提供商）
         *    - id          内部唯一标识，创建后不变
         *    - name        展示名，允许重名
         *    - type        适配器类型："openai-compatible" / "deepseek" / "local"
         *    - baseUrl     OpenAI 兼容接口的 base URL
         *    - status      最近一次连通性：connected / untested / error
         *    - apiKey      明文密钥，只允许存在服务端；接口里只返回 apiKeySet 和 apiKeyPreview
         *    - models      该提供商下已保存的模型，见下
         *
         * 2. Model（模型）
         *    后端对外是一张 model-id -> Model 的扁平表，model.id 全局唯一，
         *    所以不同提供商之间不能出现同名模型。
         *    - id            调用 API 时使用的模型名，同时也是这张表的 key
         *    - displayName   面板上显示的名字，留空时用 id
         *    - contextLength 上下文窗口，单位 token
         *    - maxImage      图片输入的最大边长；-1 表示这个模型不支持图片
         *
         * 3. 需要你提供 / 替换的部分
         *    - 持久化：把提供商和模型写进配置文件或数据库，密钥单独加密存放
         *    - testProvider：向提供商发一个最小请求，把结果写回 status
         *    - fetchRemoteModels：调用提供商的 /models 接口，返回可导入的模型及其能力
         *
         * 4. 接口约定
         *    GET  /api/models                         列出全部提供商
         *    POST /api/models/providers               新建提供商
         *    POST /api/models/providers/{id}          更新提供商（含模型列表）
         *    POST /api/models/providers/{id}/delete   删除提供商
         *    POST /api/models/providers/{id}/test     测试连通性
         *    GET  /api/models/providers/{id}/remote   拉取该提供商的远程模型
         *
         *    新建 / 更新的请求体：
         *    {
         *      "name": "DeepSeek",
         *      "type": "deepseek",
         *      "baseUrl": "https://api.deepseek.com",
         *      "apiKey": "sk-...",            // 省略表示不改，null 表示清除
         *      "models": [
         *        { "id": "deepseek-chat", "displayName": "DeepSeek Chat",
         *          "contextLength": 131072, "maxImage": 600 }
         *      ]
         *    }
         * ================================================================== */

        // 演示数据：真正的实现在接入后由配置文件 / 数据库提供，这里只让前端跑通。
        val modelProviders = mutableListOf(
            mutableMapOf<String, Any?>(
                "id" to "deepseek",
                "name" to "DeepSeek",
                "type" to "deepseek",
                "baseUrl" to "https://api.deepseek.com",
                "status" to "connected",
                "models" to mutableListOf(
                    mutableMapOf<String, Any?>(
                        "id" to "deepseek-chat",
                        "displayName" to "DeepSeek Chat",
                        "contextLength" to 131072,
                        "maxImage" to 600,
                    ),
                    mutableMapOf<String, Any?>(
                        "id" to "deepseek-reasoner",
                        "displayName" to "DeepSeek Reasoner",
                        "contextLength" to 65536,
                        "maxImage" to -1,
                    ),
                ),
            ),
            mutableMapOf<String, Any?>(
                "id" to "openai",
                "name" to "OpenAI",
                "type" to "openai-compatible",
                "baseUrl" to "https://api.openai.com/v1",
                "status" to "untested",
                "models" to mutableListOf(
                    mutableMapOf<String, Any?>(
                        "id" to "gpt-4o",
                        "displayName" to "GPT-4o",
                        "contextLength" to 128000,
                        "maxImage" to 600,
                    ),
                    mutableMapOf<String, Any?>(
                        "id" to "gpt-4o-mini",
                        "displayName" to "GPT-4o mini",
                        "contextLength" to 128000,
                        "maxImage" to 600,
                    ),
                    mutableMapOf<String, Any?>(
                        "id" to "o3",
                        "displayName" to "o3",
                        "contextLength" to 200000,
                        "maxImage" to -1,
                    ),
                ),
            ),
            mutableMapOf<String, Any?>(
                "id" to "ollama",
                "name" to "本地 Ollama",
                "type" to "local",
                "baseUrl" to "http://127.0.0.1:11434",
                "status" to "error",
                "models" to mutableListOf<MutableMap<String, Any?>>(),
            ),
        )

        // 明文密钥只留在服务端内存里，接口响应永远只给 apiKeySet 和掩码后的 apiKeyPreview。
        val providerKeys = mutableMapOf(
            "deepseek" to "sk-demo-deepseek-3f9a",
            "openai" to "sk-demo-openai-77c2",
        )

        // 新建提供商时的自增序号，只用于生成不冲突的 id。
        var providerSeq = 0

        // 演示用，没有做并发保护；换成真正的持久化实现时请放到事务里。
        fun maskKey(key: String): String {
            if (key.length <= 8) return "••••"
            return key.take(3) + "••••••••" + key.takeLast(4)
        }

        fun providerModels(provider: Map<String, Any?>): List<Map<String, Any?>> {
            @Suppress("UNCHECKED_CAST")
            return (provider["models"] as? List<Map<String, Any?>>).orEmpty()
        }

        /** 把内部结构转成接口结构：补上 apiKeySet / apiKeyPreview，绝不带明文。 */
        fun providerView(provider: Map<String, Any?>): Map<String, Any?> {
            val id = provider["id"] as String
            val key = providerKeys[id]
            val view = LinkedHashMap<String, Any?>(provider)
            view["apiKeySet"] = !key.isNullOrEmpty()
            view["apiKeyPreview"] = key?.takeIf { it.isNotEmpty() }?.let { maskKey(it) }
            return view
        }

        fun jsonText(body: JsonObject, key: String): String? =
            (body[key] as? JsonPrimitive)?.contentOrNull

        fun jsonInt(body: JsonObject, key: String, fallback: Int): Int =
            (body[key] as? JsonPrimitive)?.intOrNull ?: fallback

        fun errorBody(message: String): JsonObject = buildJsonObject { put("error", message) }

        /** 从请求体里读出模型列表；空 id 的条目直接丢弃。 */
        fun modelsFrom(body: JsonObject): MutableList<MutableMap<String, Any?>> {
            val array = body["models"] as? JsonArray ?: return mutableListOf()
            val result = mutableListOf<MutableMap<String, Any?>>()
            for (element in array) {
                val item = element as? JsonObject ?: continue
                val id = jsonText(item, "id")?.trim().orEmpty()
                if (id.isEmpty()) continue
                val displayName = jsonText(item, "displayName")?.trim().orEmpty()
                result += mutableMapOf<String, Any?>(
                    "id" to id,
                    "displayName" to displayName.ifEmpty { id },
                    "contextLength" to maxOf(0, jsonInt(item, "contextLength", 0)),
                    "maxImage" to jsonInt(item, "maxImage", -1),
                )
            }
            return result
        }

        /** 模型 id 必须全局唯一：先查本次提交里的重复，再查别的提供商已经占用的。 */
        fun duplicateModelIds(
            models: List<Map<String, Any?>>,
            ignoreProviderId: String?,
        ): List<String> {
            val ownIds = models.map { it["id"] as String }
            val repeated = ownIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            val usedElsewhere = modelProviders
                .filter { it["id"] != ignoreProviderId }
                .flatMap { provider -> providerModels(provider).map { it["id"] as String } }
                .toSet()
            return (repeated + ownIds.filter { it in usedElsewhere }).distinct()
        }

        fun findProvider(id: String?): MutableMap<String, Any?>? =
            modelProviders.firstOrNull { it["id"] == id }

        get("/api/models") {
            call.respondObj("providers" to modelProviders.map { providerView(it) })
        }

        post("/api/models/providers") {
            val body = runCatching { call.receive<JsonObject>() }.getOrNull()
            if (body == null) {
                call.respond(HttpStatusCode.BadRequest, errorBody("请求体不是合法的 JSON"))
                return@post
            }

            val name = jsonText(body, "name")?.trim().orEmpty()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, errorBody("提供商名称不能为空"))
                return@post
            }

            val models = modelsFrom(body)
            val duplicates = duplicateModelIds(models, null)
            if (duplicates.isNotEmpty()) {
                call.respond(
                    HttpStatusCode.Conflict,
                    errorBody("模型 id 已经存在：${duplicates.joinToString("、")}"),
                )
                return@post
            }

            var id = ""
            do {
                id = "p${++providerSeq}"
            } while (findProvider(id) != null)

            val provider = mutableMapOf<String, Any?>(
                "id" to id,
                "name" to name,
                "type" to (jsonText(body, "type") ?: "openai-compatible"),
                "baseUrl" to jsonText(body, "baseUrl").orEmpty(),
                "status" to "untested",
                "models" to models,
            )

            val keyElement = body["apiKey"]
            if (keyElement is JsonPrimitive && keyElement.isString && keyElement.content.isNotEmpty()) {
                providerKeys[id] = keyElement.content
            }

            modelProviders += provider
            call.respondObj("provider" to providerView(provider))
        }

        post("/api/models/providers/{id}") {
            val id = call.parameters["id"]
            val provider = findProvider(id)
            if (provider == null) {
                call.respond(HttpStatusCode.NotFound, errorBody("提供商不存在"))
                return@post
            }

            val body = runCatching { call.receive<JsonObject>() }.getOrNull()
            if (body == null) {
                call.respond(HttpStatusCode.BadRequest, errorBody("请求体不是合法的 JSON"))
                return@post
            }

            val name = jsonText(body, "name")?.trim().orEmpty()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, errorBody("提供商名称不能为空"))
                return@post
            }

            val providerId = provider["id"] as String
            val models = modelsFrom(body)
            val duplicates = duplicateModelIds(models, providerId)
            if (duplicates.isNotEmpty()) {
                call.respond(
                    HttpStatusCode.Conflict,
                    errorBody("模型 id 已经存在：${duplicates.joinToString("、")}"),
                )
                return@post
            }

            val oldBaseUrl = provider["baseUrl"]
            val newBaseUrl = jsonText(body, "baseUrl").orEmpty()
            var connectionChanged = newBaseUrl != oldBaseUrl

            // apiKey: 字段省略 = 不修改；null = 清除；字符串 = 覆盖。
            when (val keyElement = body["apiKey"]) {
                null -> Unit
                is JsonNull -> {
                    providerKeys.remove(providerId)
                    connectionChanged = true
                }
                is JsonPrimitive -> if (keyElement.isString) {
                    if (keyElement.content.isEmpty()) providerKeys.remove(providerId)
                    else providerKeys[providerId] = keyElement.content
                    connectionChanged = true
                }
                else -> Unit
            }

            provider["name"] = name
            provider["type"] = jsonText(body, "type") ?: provider["type"]
            provider["baseUrl"] = newBaseUrl
            provider["models"] = models
            // 连接信息变了，之前的连通性结论就不作数了。
            if (connectionChanged) provider["status"] = "untested"

            call.respondObj("provider" to providerView(provider))
        }

        post("/api/models/providers/{id}/delete") {
            val id = call.parameters["id"]
            val removed = modelProviders.removeAll { it["id"] == id }
            if (!removed) {
                call.respond(HttpStatusCode.NotFound, errorBody("提供商不存在"))
                return@post
            }
            call.parameters["id"]?.let { providerKeys.remove(it) }
            call.respondObj("success" to true)
        }

        post("/api/models/providers/{id}/test") {
            val provider = findProvider(call.parameters["id"])
            if (provider == null) {
                call.respond(HttpStatusCode.NotFound, errorBody("提供商不存在"))
                return@post
            }

            // 真正接入时在这里向提供商发一个最小请求（例如列模型），再根据结果写 status。
            val id = provider["id"] as String
            val baseUrl = (provider["baseUrl"] as? String).orEmpty()
            val type = (provider["type"] as? String).orEmpty()
            val hasKey = !providerKeys[id].isNullOrEmpty()
            val reachable = baseUrl.isNotBlank() && (hasKey || type == "local")
            provider["status"] = if (reachable) "connected" else "error"

            call.respondObj("provider" to providerView(provider))
        }

        get("/api/models/providers/{id}/remote") {
            val provider = findProvider(call.parameters["id"])
            if (provider == null) {
                call.respond(HttpStatusCode.NotFound, errorBody("提供商不存在"))
                return@get
            }

            // 真正接入时改成请求提供商的 /models 接口。
            val existing = providerModels(provider).map { it["id"] as String }
            val extra = when (provider["type"]) {
                "deepseek" -> listOf("deepseek-v3.2", "deepseek-vl2")
                "local" -> listOf("qwen3:32b", "llama3.3:70b")
                else -> listOf("gpt-4.1", "o4-mini")
            }

            val remote = (existing + extra).distinct().map { modelId ->
                mapOf(
                    "id" to modelId,
                    "displayName" to modelId,
                    "contextLength" to 131072,
                    "maxImage" to 600,
                )
            }
            call.respondObj("models" to remote)
        }


        // ---- 频道与系统设置：仍然是内存实现，接口形状就是前端要对接的那份 ----

        /**
         * 一个配置项的描述加默认值。前端只按 type 渲染，不认识任何具体字段，
         * 增加配置项时只改这里。
         */
        fun schemaField(
            key: String,
            label: String,
            type: String,
            default: Any? = null,
            help: String? = null,
            required: Boolean = false,
            unit: String? = null,
            placeholder: String? = null,
            options: List<Pair<String, String>>? = null,
            disabled: Boolean = false,
        ): MutableMap<String, Any?> = mutableMapOf(
            "key" to key,
            "label" to label,
            "type" to type,
            "default" to default,
            "help" to help,
            "required" to required,
            "unit" to unit,
            "placeholder" to placeholder,
            "options" to options?.map { mutableMapOf("value" to it.first, "label" to it.second) },
            "disabled" to disabled,
        )

        fun schemaGroup(
            id: String,
            title: String,
            vararg fields: MutableMap<String, Any?>,
        ): MutableMap<String, Any?> = mutableMapOf(
            "id" to id,
            "title" to title,
            "fields" to fields.toList(),
        )

        @Suppress("UNCHECKED_CAST")
        fun schemaGroups(container: Map<String, Any?>): List<Map<String, Any?>> =
            (container["groups"] as? List<Map<String, Any?>>).orEmpty()

        @Suppress("UNCHECKED_CAST")
        fun schemaFields(group: Map<String, Any?>): List<Map<String, Any?>> =
            (group["fields"] as? List<Map<String, Any?>>).orEmpty()

        /** 每个频道一份自己的字段表，因此这里不能共用一份结构。 */
        val channelDefs: List<Map<String, Any?>> = listOf(
            mutableMapOf(
                "id" to "telegram",
                "name" to "Telegram",
                "enabled" to true,
                "groups" to listOf(
                    schemaGroup(
                        "basic", "基础",
                        schemaField(
                            "botToken", "Bot Token", "secret", "7123456789:AAH6xkQ2mB0pLr4tZ8wYdEfGhIjKlMnOpQ",
                            help = "从 @BotFather 获取，保存后只显示末四位",
                            required = true,
                        ),
                        schemaField(
                            "apiBase", "接口地址", "text", "https://api.telegram.org",
                            help = "自建反代时改这里",
                        ),
                        schemaField("proxy", "代理", "text", "", placeholder = "http://127.0.0.1:7890"),
                        schemaField("pollTimeout", "轮询超时", "number", 25, unit = "秒"),
                    ),
                    schemaGroup(
                        "message", "消息",
                        schemaField(
                            "allowedChats", "允许的会话", "tags", listOf("@neoagent_group", "1029384756"),
                            help = "留空表示接受全部会话",
                            placeholder = "@your_channel",
                        ),
                        schemaField("allowGroups", "接受群组消息", "switch", true),
                        schemaField(
                            "parseMode", "消息格式", "select", "markdown",
                            options = listOf(
                                "markdown" to "MarkdownV2",
                                "html" to "HTML",
                                "plain" to "纯文本",
                            ),
                        ),
                        schemaField("welcomeText", "欢迎语", "textarea", "", placeholder = "留空则不发送"),
                    ),
                    schemaGroup(
                        "advanced", "高级",
                        schemaField(
                            "webhookUrl", "回调地址", "readonly",
                            "https://panel.example.com/api/channels/telegram/webhook",
                            disabled = true,
                        ),
                        schemaField("disablePreview", "关闭链接预览", "switch", true),
                        schemaField(
                            "rateLimit", "速率上限", "number", 60, unit = "条/分",
                            help = "超过后排队等待，不会丢消息",
                        ),
                    ),
                ),
            ),
            mutableMapOf(
                "id" to "discord",
                "name" to "Discord",
                "enabled" to true,
                "groups" to listOf(
                    schemaGroup(
                        "basic", "基础",
                        schemaField(
                            "botToken", "Bot Token", "secret", "MTIzNDU2Nzg5:MOCK.DISCORD.TOKEN",
                            help = "在开发者后台的 Bot 页面获取",
                            required = true,
                        ),
                        schemaField("proxy", "代理", "text", "", placeholder = "http://127.0.0.1:7890"),
                    ),
                    schemaGroup(
                        "message", "消息",
                        schemaField("allowedChannels", "允许的频道", "tags", listOf("neoagent")),
                        schemaField("commandPrefix", "命令前缀", "text", "!"),
                    ),
                    schemaGroup(
                        "advanced", "高级",
                        schemaField("shardId", "分片编号", "number", 0, help = "只有大型机器人需要"),
                    ),
                ),
            ),
            mutableMapOf(
                "id" to "slack",
                "name" to "Slack",
                "enabled" to false,
                "groups" to listOf(
                    schemaGroup(
                        "basic", "基础",
                        schemaField("botToken", "Bot Token", "secret", "", placeholder = "xoxb-...", required = true),
                        schemaField("signingSecret", "Signing Secret", "secret", "", required = true),
                    ),
                    schemaGroup(
                        "message", "消息",
                        schemaField("defaultChannel", "默认频道", "text", "#general"),
                    ),
                ),
            ),
            mutableMapOf(
                "id" to "lark",
                "name" to "飞书",
                "enabled" to true,
                "groups" to listOf(
                    schemaGroup(
                        "basic", "基础",
                        schemaField("appId", "App ID", "text", "cli_mock_app_id", required = true),
                        schemaField("appSecret", "App Secret", "secret", "", required = true),
                    ),
                    schemaGroup(
                        "message", "消息",
                        schemaField("encryptKey", "加密 Key", "secret", ""),
                        schemaField("allowedGroups", "允许的群", "tags", emptyList<String>()),
                    ),
                ),
            ),
            mutableMapOf(
                "id" to "webhook",
                "name" to "自建 Webhook",
                "enabled" to false,
                "groups" to listOf(
                    schemaGroup(
                        "basic", "基础",
                        schemaField(
                            "endpoint", "接收路径", "readonly", "/api/channels/webhook/incoming",
                            disabled = true,
                        ),
                    ),
                    schemaGroup(
                        "advanced", "高级",
                        schemaField("secret", "签名密钥", "secret", ""),
                        schemaField("allowedIps", "允许的来源 IP", "tags", emptyList<String>()),
                    ),
                ),
            ),
        )

        val channelValues: MutableMap<String, MutableMap<String, Any?>> = mutableMapOf()
        for (def in channelDefs) {
            val id = def["id"] as String
            val values = mutableMapOf<String, Any?>("enabled" to (def["enabled"] == true))
            schemaGroups(def).forEach { group ->
                schemaFields(group).forEach { field ->
                    values[field["key"] as String] = field["default"]
                }
            }
            channelValues[id] = values
        }

        fun blankValue(value: Any?): Boolean = when (value) {
            null -> true
            is String -> value.isBlank()
            is Collection<*> -> value.isEmpty()
            else -> false
        }

        fun channelStatus(def: Map<String, Any?>, values: Map<String, Any?>): String {
            if (values["enabled"] != true) return "stopped"
            val missing = schemaGroups(def)
                .flatMap { schemaFields(it) }
                .any { it["required"] == true && blankValue(values[it["key"] as String]) }
            return if (missing) "error" else "running"
        }

        fun groupView(group: Map<String, Any?>, values: Map<String, Any?>): Map<String, Any?> = mapOf(
            "id" to group["id"],
            "title" to group["title"],
            "fields" to schemaFields(group).map { field ->
                val view = LinkedHashMap(field)
                view.remove("default")
                val key = field["key"] as String
                val secret = field["type"] == "secret"
                val raw = values[key]
                view["value"] = if (secret) {
                    val text = raw as? String
                    if (text.isNullOrEmpty()) "" else maskKey(text)
                } else {
                    raw
                }
                view["masked"] = secret
                view
            },
        )

        fun channelView(def: Map<String, Any?>): Map<String, Any?> {
            val id = def["id"] as String
            val values = channelValues[id].orEmpty()
            return mapOf(
                "id" to id,
                "name" to def["name"],
                "enabled" to (values["enabled"] == true),
                "status" to channelStatus(def, values),
                "groups" to schemaGroups(def).map { groupView(it, values) },
            )
        }

        /** 把提交上来的值合并进目标表；密钥如果原样回传的是掩码，就保持旧值。 */
        fun applyValues(
            target: MutableMap<String, Any?>,
            incoming: JsonObject,
            fields: List<Map<String, Any?>>,
        ) {
            val byKey = fields.associateBy { it["key"] as String }
            for ((key, element) in incoming) {
                if (key == "enabled") {
                    target[key] = (element as? JsonPrimitive)?.booleanOrNull ?: false
                    continue
                }
                val spec = byKey[key] ?: continue
                if (spec["type"] == "readonly") continue
                if (spec["type"] == "secret") {
                    val current = target[key] as? String ?: ""
                    val masked = if (current.isEmpty()) "" else maskKey(current)
                    val next = (element as? JsonPrimitive)?.contentOrNull ?: ""
                    if (next == masked) continue
                    target[key] = next
                    continue
                }
                target[key] = element.toPlainValue()
            }
        }

        get("/api/channels") {
            call.respondObj("channels" to channelDefs.map { channelView(it) })
        }

        post("/api/channels") {
            val body = runCatching { call.receive<JsonObject>() }.getOrNull()
            if (body == null) {
                call.respond(HttpStatusCode.BadRequest, errorBody("请求体不是合法的 JSON"))
                return@post
            }

            val submitted = body["values"] as? JsonObject ?: JsonObject(emptyMap())
            for ((channelId, payload) in submitted) {
                val def = channelDefs.firstOrNull { it["id"] == channelId } ?: continue
                val incoming = payload as? JsonObject ?: continue
                val target = channelValues.getOrPut(channelId) { mutableMapOf() }
                applyValues(target, incoming, schemaGroups(def).flatMap { schemaFields(it) })
            }

            call.respondObj("channels" to channelDefs.map { channelView(it) })
        }

        val settingsDefs: List<Map<String, Any?>> = listOf(
            schemaGroup(
                "panel", "面板",
                schemaField("panelTitle", "面板标题", "text", "NeoAgent WebUI"),
                schemaField(
                    "language", "语言", "select", "zh-CN",
                    options = listOf("zh-CN" to "简体中文", "en" to "English", "auto" to "跟随系统"),
                ),
                schemaField("openOnStart", "启动时打开浏览器", "switch", true, help = "只在桌面环境下生效"),
                schemaField("sessionTimeout", "会话有效期", "number", 7, unit = "天"),
            ),
            schemaGroup(
                "runtime", "运行时",
                schemaField(
                    "dataDir", "数据目录", "readonly", "~/Library/Application Support/NeoAgent/data",
                    disabled = true,
                ),
                schemaField(
                    "maxAgents", "最大并发 Agent", "number", 4, unit = "个",
                    help = "超出后排队，不会丢弃请求",
                ),
                schemaField("idleTimeout", "空闲回收", "number", 30, unit = "分钟"),
                schemaField("gcInterval", "清理间隔", "number", 10, unit = "分钟"),
            ),
            schemaGroup(
                "network", "网络",
                schemaField("listen", "监听地址", "text", "0.0.0.0:8080", help = "修改后需要重启生效"),
                schemaField("proxy", "出站代理", "text", "", placeholder = "http://127.0.0.1:7890"),
                schemaField("timeout", "请求超时", "number", 60, unit = "秒"),
                schemaField(
                    "allowedOrigins", "跨域来源", "tags", listOf("http://localhost:5173"),
                    help = "留空表示只允许同源",
                    placeholder = "https://panel.example.com",
                ),
            ),
            schemaGroup(
                "log", "日志",
                schemaField(
                    "level", "日志级别", "select", "info",
                    options = listOf("debug" to "debug", "info" to "info", "warn" to "warn", "error" to "error"),
                ),
                schemaField("retentionDays", "保留天数", "number", 14, unit = "天"),
                schemaField("logBody", "记录请求体", "switch", false, help = "请求体可能包含密钥"),
            ),
            schemaGroup(
                "security", "安全",
                schemaField("maxAttempts", "登录失败锁定", "number", 5, unit = "次"),
                schemaField(
                    "allowRegister", "允许注册", "switch", false,
                    help = "由环境变量 NEOAGENT_ALLOW_REGISTER 控制",
                    disabled = true,
                ),
                schemaField(
                    "trustProxy", "信任反向代理", "switch", true,
                    help = "放在 Nginx 后面时打开，用于取真实 IP",
                ),
            ),
        )

        val settingValues: MutableMap<String, Any?> = mutableMapOf()
        settingsDefs.forEach { group ->
            schemaFields(group).forEach { field ->
                settingValues[field["key"] as String] = field["default"]
            }
        }

        get("/api/settings") {
            call.respondObj("groups" to settingsDefs.map { groupView(it, settingValues) })
        }

        post("/api/settings") {
            val body = runCatching { call.receive<JsonObject>() }.getOrNull()
            if (body == null) {
                call.respond(HttpStatusCode.BadRequest, errorBody("请求体不是合法的 JSON"))
                return@post
            }

            val submitted = body["values"] as? JsonObject ?: JsonObject(emptyMap())
            applyValues(settingValues, submitted, settingsDefs.flatMap { schemaFields(it) })
            call.respondObj("groups" to settingsDefs.map { groupView(it, settingValues) })
        }

        // todo: agents monitor api

        // websocket: one master connection to push notifications/send messages for all agents
    }
}

/** 把任意值转成 JsonElement，处理不了的兜底成字符串。 */
fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(this.toString())
}

/** 把请求体里的 JSON 值转回普通 Kotlin 值，方便直接存进内存表。 */
fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        booleanOrNull != null -> booleanOrNull
        intOrNull != null -> intOrNull
        doubleOrNull != null -> doubleOrNull
        else -> contentOrNull
    }
    is JsonArray -> map { it.toPlainValue() }
    is JsonObject -> entries.associate { it.key to it.value.toPlainValue() }
}

/** 像 mapOf 一样用，但能安全返回 JSON。 */
suspend fun ApplicationCall.respondObj(vararg pairs: Pair<String, Any?>) {
    respond(JsonObject(pairs.associate { (k, v) -> k to v.toJsonElement() }))
}