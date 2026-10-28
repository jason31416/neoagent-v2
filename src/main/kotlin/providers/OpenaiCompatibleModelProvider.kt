package cn.jason31416.providers

import cn.jason31416.agent.ContentBlock
import cn.jason31416.agent.ContentType
import cn.jason31416.agent.ContextEntry
import cn.jason31416.agent.FinishReason
import cn.jason31416.agent.Model
import cn.jason31416.agent.ModelCapability
import cn.jason31416.agent.ModelEvent
import cn.jason31416.agent.Role
import cn.jason31416.agent.ToolCall
import cn.jason31416.agent.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI Chat Completions wire format 的通用实现。
 *
 * 这里只实现 OpenAI 标准：不感知 DeepSeek/Kimi/Qwen 的 reasoning_content、
 * thinking 等厂商字段。厂商差异放到子类里覆盖对应方法。
 *
 * requestModel 是固定骨架：
 * buildRequest -> send -> read SSE -> accumulate -> 唯一一个 Completed。
 */
open class OpenaiCompatibleModelProvider(
    override val id: String,
    protected val baseurl: String,
    protected val apikey: String,
    protected val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    protected val extraHeaders: Map<String, String> = emptyMap(),
) : ModelProvider {

    @Volatile
    private var cachedModels: List<Model> = emptyList()

    override fun getAvailableModels(): List<Model> = cachedModels

    final override fun requestModel(
        model: Model,
        context: List<ContextEntry>,
        tools: List<ToolDefinition>,
        effort: String?,
        sessionID: String,
    ): Flow<ModelEvent> = flow {
        val acc = AssistantAccumulator()
        try {
            val prepared = prepareContext(context)
            val response = send(buildHttpRequest(model, prepared, tools, effort, sessionID))

            if (response.statusCode() !in 200..299) {
                emit(httpFailure(response))
                return@flow
            }

            readStream(response, acc, this)
            emit(buildCompleted(acc))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(buildFailure(acc, e))
        }
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- request

    /**
     * 发请求前对 context 的最后一次加工，默认原样返回。
     *
     * 子类可以在这里做异步准备（比如把 inline 图片换成上传后的 file_id），
     * 而不用把网络 I/O 塞进 convert* 这些纯函数里。
     */
    protected open suspend fun prepareContext(context: List<ContextEntry>): List<ContextEntry> = context

    private fun buildHttpRequest(
        model: Model,
        context: List<ContextEntry>,
        tools: List<ToolDefinition>,
        effort: String?,
        sessionID: String,
    ): HttpRequest {
        val body = buildRequestBody(model, context, tools, effort).toString()
        return HttpRequest.newBuilder(URI.create(endpoint()))
            .timeout(Duration.ofMinutes(10))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .also { applyHeaders(it, sessionID) }
            .build()
    }

    /** chat/completions 的完整 URL。 */
    protected open fun endpoint(): String = baseurl.trimEnd('/') + "/chat/completions"

    /**
     * 认证和通用 header。
     *
     * OpenaiCompatibleModelProvider 本身不使用 sessionID；需要会话头的子类覆盖这里。
     */
    @Suppress("UNUSED_PARAMETER")
    protected open fun applyHeaders(builder: HttpRequest.Builder, sessionID: String) {
        builder.setHeader("Content-Type", "application/json")
        builder.setHeader("Accept", "text/event-stream")
        applyAuth(builder)
        applyExtraHeaders(builder)
    }

    private fun applyAuth(builder: HttpRequest.Builder) {
        if (apikey.isNotBlank()) {
            builder.setHeader("Authorization", "Bearer $apikey")
        }
    }

    private fun applyExtraHeaders(builder: HttpRequest.Builder) {
        extraHeaders.forEach { (name, value) ->
            builder.setHeader(name, value)
        }
    }

    /** 完整请求体。子类优先覆盖下面的小方法，不要轻易覆盖这里。 */
    protected open fun buildRequestBody(
        model: Model,
        context: List<ContextEntry>,
        tools: List<ToolDefinition>,
        effort: String?,
    ): JsonObject = buildJsonObject {
        put("model", model.id)
        put("stream", true)
        put("messages", buildMessages(context))

        if (tools.isNotEmpty()) {
            put("tools", buildTools(tools))
            put("tool_choice", "auto")
        }

        applyEffort(this, effort)
    }

    /** reasoning_effort；厂商在这里做自己的 effort 映射。 */
    protected open fun applyEffort(body: JsonObjectBuilder, effort: String?) {
        val value = effort?.trim()?.takeIf { it.isNotEmpty() } ?: return
        body.put("reasoning_effort", value)
    }

    // ---------------------------------------------------------------- messages

    private fun buildMessages(context: List<ContextEntry>): JsonArray =
        buildJsonArray {
            context.forEach { add(convertEntry(it)) }
        }

    /** 按 role 分派。 */
    protected open fun convertEntry(entry: ContextEntry): JsonObject = when (entry.role) {
        Role.SYSTEM, Role.USER -> buildJsonObject {
            put("role", entry.role.name.lowercase())
            put("content", convertUserContent(entry.content))
        }

        Role.ASSISTANT -> convertAssistant(entry)
        Role.TOOL -> convertToolMessage(entry)
    }

    /** user content：纯文本，或 text + image_url parts。 */
    protected open fun convertUserContent(blocks: List<ContentBlock>): JsonElement {
        val text = blocks
            .filter { it.type == ContentType.TEXT }
            .joinToString("\n") { it.content }
        val images = blocks.filter { it.type == ContentType.IMAGE }

        if (images.isEmpty()) {
            return JsonPrimitive(text)
        }

        return buildJsonArray {
            if (text.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
            images.forEach { add(convertImage(it)) }
        }
    }

    /** 一张 IMAGE block 变成什么样的 JSON part；默认 image_url。 */
    protected open fun convertImage(image: ContentBlock): JsonObject = buildJsonObject {
        put("type", "image_url")
        put("image_url", buildJsonObject { put("url", image.content) })
    }

    /** assistant：content / null + tool_calls。 */
    protected open fun convertAssistant(entry: ContextEntry): JsonObject = buildJsonObject {
        put("role", "assistant")

        val text = entry.content
            .filter { it.type == ContentType.TEXT }
            .joinToString("") { it.content }

        if (text.isEmpty() && entry.toolCalls.isNotEmpty()) {
            put("content", JsonNull)
        } else {
            put("content", text)
        }

        if (entry.toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                entry.toolCalls.forEach { add(convertToolCall(it)) }
            })
        }
    }

    /** tool 消息：tool_call_id + content。 */
    protected open fun convertToolMessage(entry: ContextEntry): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", entry.toolCallID ?: "")
        put("content", buildJsonArray {
            entry.content.forEach { item ->
                add(when (item.type) {
                    ContentType.TEXT -> buildJsonObject {
                        put("type", "text")
                        put("text", item.content)
                    }
                    ContentType.IMAGE -> convertImage(item)
                })
            }
        })
    }

    private fun convertToolCall(call: ToolCall): JsonObject = buildJsonObject {
        put("id", call.call_id)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", call.name)
            put("arguments", call.arguments)
        })
    }

    // ---------------------------------------------------------------- tools

    private fun buildTools(tools: List<ToolDefinition>): JsonArray =
        buildJsonArray {
            tools.forEach { add(convertTool(it)) }
        }

    protected open fun convertTool(tool: ToolDefinition): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    tool.parameters.forEach { parameter ->
                        put(parameter.name, buildJsonObject {
                            put("type", parameter.type.name.lowercase())
                            put("description", parameter.description)
                            parameter.additional.forEach { (key, value) ->
                                put(key, value)
                            }
                        })
                    }
                })
                put("required", buildJsonArray {
                    tool.parameters.filter { it.required }.forEach { add(it.name) }
                })
            })
        })
    }

    // ---------------------------------------------------------------- response

    private suspend fun readStream(
        response: HttpResponse<Stream<String>>,
        acc: AssistantAccumulator,
        collector: FlowCollector<ModelEvent>,
    ) {
        response.body().use { lines ->
            val iterator = lines.iterator()
            while (iterator.hasNext()) {
                currentCoroutineContext().ensureActive()

                val data = parseSseData(iterator.next()) ?: continue
                if (data == "[DONE]") break

                consumeChunk(parseChunk(data), acc).let {
                    if(it.first!=null||it.second!=null)
                        collector.emit(ModelEvent.TextDelta(reasoningDelta=it.first, textDelta=it.second))
                }
            }
        }
    }

    private fun parseSseData(line: String): String? {
        if (line.isEmpty() || line.startsWith(":")) return null
        if (!line.startsWith("data:")) return null
        return line.removePrefix("data:").trim().takeIf { it.isNotEmpty() }
    }

    private fun parseChunk(data: String): JsonObject =
        Json.parseToJsonElement(data).jsonObject

    /**
     * 处理一个 SSE chunk。
     *
     * 返回本次新增的文本（没有文本就返回 null），供上层发 TextDelta。
     * 前者为thinking的delta，后者为输出的delta
     * 子类可以在这里补充厂商字段的解析，然后调用 super。
     */
    protected open fun consumeChunk(chunk: JsonObject, acc: AssistantAccumulator): Pair<String?, String?> {
        (chunk["error"] as? JsonObject)?.let { error ->
            acc.error = IllegalStateException("Provider stream error: $error")
            return Pair(null, null)
        }

        val choice = (chunk["choices"] as? JsonArray)
            ?.firstOrNull() as? JsonObject
            ?: return Pair(null, null)

        var textDelta: String? = null

        (choice["delta"] as? JsonObject)?.let { delta ->
            (delta["content"] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    acc.appendText(it)
                    textDelta = it
                }

            (delta["tool_calls"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.let(acc::appendToolCall)
            }
        }

        (choice["finish_reason"] as? JsonPrimitive)
            ?.contentOrNull
            ?.let { acc.finishReason = it }

        return Pair(null, textDelta)
    }

    private fun buildCompleted(acc: AssistantAccumulator): ModelEvent.Completed {
        val message = acc.buildMessage()

        val finish = if (acc.error != null) {
            FinishReason.UNKNOWN_ERROR
        } else {
            mapFinishReason(acc.finishReason, message.toolCalls.isNotEmpty())
        }

        val error = acc.error ?: if (finish == FinishReason.UNKNOWN_ERROR) {
            IllegalStateException("Model stream ended with finish_reason=${acc.finishReason}")
        } else {
            null
        }

        return ModelEvent.Completed(message, finish, error)
    }

    protected open fun mapFinishReason(raw: String?, hasToolCalls: Boolean): FinishReason =
        when (raw) {
            "stop" -> if (hasToolCalls) FinishReason.TOOL_CALLS else FinishReason.STOP
            "length" -> FinishReason.LENGTH
            "tool_calls" -> if (hasToolCalls) FinishReason.TOOL_CALLS else FinishReason.UNKNOWN_ERROR
            "content_filter" -> FinishReason.CONTENT_FILTER
            "insufficient_system_resource", "aborted" -> FinishReason.UNKNOWN_ERROR
            else -> FinishReason.UNKNOWN_ERROR
        }

    private fun buildFailure(acc: AssistantAccumulator, error: Exception): ModelEvent.Completed =
        ModelEvent.Completed(acc.buildMessage(), FinishReason.UNKNOWN_ERROR, error)

    private fun httpFailure(response: HttpResponse<Stream<String>>): ModelEvent.Completed {
        val body = response.body().use { it.toList().joinToString("\n") }
        return failure(response.statusCode(), body)
    }

    /** 非 2xx / 异常的统一出口。 */
    protected open fun failure(statusCode: Int, body: String): ModelEvent.Completed {
        // 413 来自 API 前面的网关（body 超过 48 MiB），不是模型本身失败。
        val reason = if (statusCode == HTTP_REQUEST_TOO_LARGE) {
            FinishReason.REQUEST_TOO_LARGE
        } else {
            FinishReason.UNKNOWN_ERROR
        }

        return ModelEvent.Completed(
            emptyAssistant(),
            reason,
            OpenaiHttpException(statusCode, body),
        )
    }

    private fun emptyAssistant(): ContextEntry =
        ContextEntry(Role.ASSISTANT, mutableListOf())

    // ---------------------------------------------------------------- models

    override fun reloadModels() {
        val response = httpClient.send(
            buildModelsRequest(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

        if (response.statusCode() !in 200..299) {
            throw OpenaiHttpException(response.statusCode(), response.body())
        }

        cachedModels = parseModels(Json.parseToJsonElement(response.body()).jsonObject)
    }

    private fun buildModelsRequest(): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(modelsEndpoint()))
            .timeout(Duration.ofSeconds(30))
            .GET()

        builder.setHeader("Accept", "application/json")
        applyAuth(builder)
        applyExtraHeaders(builder)
        return builder.build()
    }

    private fun modelsEndpoint(): String =
        baseurl.trimEnd('/') + "/models"

    protected open fun modelCapabilities(modelID: String): ModelCapability{
        return ModelCapability()
    }

    protected open fun parseModels(root: JsonObject): List<Model> =
        (root["data"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val modelId = (obj["id"] as? JsonPrimitive)
                ?.contentOrNull
                ?: return@mapNotNull null
            val displayName = (obj["name"] as? JsonPrimitive)
                ?.contentOrNull
                ?: modelId
            Model(modelId, displayName, this, modelCapabilities(modelId))
        } ?: emptyList()

    // ---------------------------------------------------------------- transport

    private suspend fun send(request: HttpRequest): HttpResponse<Stream<String>> =
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).awaitResponse()

    /**
     * 把 JDK 的 CompletableFuture 桥成可取消的 suspend 调用。
     * 协程取消时 cancel(true) 会尽量中断底层请求。
     */
    protected suspend fun <T> CompletableFuture<T>.awaitResponse(): T =
        suspendCancellableCoroutine { continuation ->
            whenComplete { value, error ->
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    continuation.resume(value)
                }
            }
            continuation.invokeOnCancellation { cancel(true) }
        }

    // ---------------------------------------------------------------- state

    protected class AssistantAccumulator {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        private val calls = sortedMapOf<Int, ToolCallDraft>()

        var finishReason: String? = null
        var error: Exception? = null

        fun appendText(value: String) {
            text.append(value)
        }

        fun appendReasoning(value: String) {
            reasoning.append(value)
        }

        fun appendToolCall(delta: JsonObject) {
            val index = (delta["index"] as? JsonPrimitive)?.intOrNull
                ?: calls.size
            val draft = calls.getOrPut(index) { ToolCallDraft() }

            (delta["id"] as? JsonPrimitive)
                ?.contentOrNull
                ?.let { draft.id = it }

            (delta["function"] as? JsonObject)?.let { function ->
                (function["name"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.let { draft.name = it }

                (function["arguments"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.let { draft.arguments.append(it) }
            }
        }

        fun buildMessage(): ContextEntry {
            val blocks = mutableListOf<ContentBlock>()
            if (text.isNotEmpty()) {
                blocks += ContentBlock(ContentType.TEXT, text.toString())
            }

            return ContextEntry(
                role = Role.ASSISTANT,
                content = blocks,
                toolCalls = calls.values.mapNotNull { it.build() },
                reasoningContent = reasoning.takeIf { it.isNotEmpty() }?.toString(),
            )
        }

        private class ToolCallDraft {
            var id: String? = null
            var name: String? = null
            val arguments = StringBuilder()

            fun build(): ToolCall? {
                val id = id ?: return null
                val name = name ?: return null
                return ToolCall(id, name, arguments.toString().ifEmpty { "{}" })
            }
        }
    }
}

class OpenaiHttpException(
    val statusCode: Int,
    val responseBody: String,
) : Exception(
    "HTTP $statusCode from OpenAI-compatible endpoint: ${responseBody.take(2000)}"
)

/** 网关的请求体超限状态码。 */
private const val HTTP_REQUEST_TOO_LARGE = 413
