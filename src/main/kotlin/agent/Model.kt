package cn.jason31416.agent

import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.Flow

enum class Role {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

/*
{
  "id": "call_xyz789",
  "type": "function",
  "function": {
    "name": "get_order_status",
    "arguments": "{\"order_id\":\"A123\"}"
  }
}
 */
data class ToolCall(
    val call_id: String,
    val name: String,
    val arguments: String
)

enum class ContentType {
    TEXT,
    IMAGE
}

/*
{ "type": "text", "text": "这张图片里是什么？另外，帮我查一下订单 123 的状态。" },
{
  "type": "image_url",
  "image_url": {
    "url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg...",
    "detail": "auto"
  }
}
 */
data class ContentBlock(
    val type: ContentType,
    val content: String, // This can both be the text or the image url, depending on the type
)

data class ContextEntry(
    val role: Role,
    val content: MutableList<ContentBlock>,
    val toolCalls: List<ToolCall> = emptyList(), // role=assistant only
    val toolCallID: String?=null, // role=tool only
)

// Avoid using any type not defined here
enum class ParameterType {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN,
    ARRAY, // must be treated as special case in tool call parsing.
}

fun ParameterType.convert(el: JsonElement): Any? = when (this) {
    ParameterType.STRING  -> el.jsonPrimitive.contentOrNull
    ParameterType.INTEGER -> el.jsonPrimitive.intOrNull
    ParameterType.NUMBER  -> el.jsonPrimitive.doubleOrNull
    ParameterType.BOOLEAN -> el.jsonPrimitive.booleanOrNull
    ParameterType.ARRAY   -> el as? JsonArray
}

data class Parameter(
    val type: ParameterType,
    val name: String,
    val description: String,
    val additional: Map<String, JsonElement> = emptyMap(), // such as default, minLength... When the type is an array, `items` must be defined as { "type": "xxx(string, integer, ...)" }.
    val required: Boolean = false,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<Parameter>,
)

enum class FinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    UNKNOWN_ERROR,
    MAX_ROUND_REACHED, // This is not triggered by provider but rather Agent.kt's limit.
}

sealed interface ModelEvent {
    data class TextDelta(val text: String) : ModelEvent
    data class Completed(val message: ContextEntry, val finishReason: FinishReason, val exception: Exception?) : ModelEvent
}

/**
 * A general provider for LLM service, representing a single loaded model.
 */
interface Model {
    companion object {
        val modelRegistry = hashMapOf<String, Model>()
    }
    val id: String
    val displayName: String

    /**
     * One round trip with the model. Returns the assistant's reply — the same [ContextEntry]
     * the loop appends to the conversation, so no DTO mapping is needed in between.
     *
     * Streams TextDelta if the model supports, only calling completed otherwise.
     */
    fun request(
        context: List<ContextEntry>,
        tools: List<ToolDefinition>,
        effort: String?,
        sessionID: String, // For opencode's protocol
    ): Flow<ModelEvent>
}