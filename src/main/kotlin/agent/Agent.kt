package cn.jason31416.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface AgentEvent {
    /** 流式文本，临时的；UI 可以边收边显示 */
    data class AssistantDelta(val text: String) : AgentEvent

    /** 已提交的 assistant 消息（含 toolCalls）*/
    data class AssistantReplied(val message: ContextEntry, val interrupted: Boolean=false) : AgentEvent

    /** agent收到消息（包括用户消息/系统提示/其他注入消息等）*/
    data class AssistantReceivedMessage(val message: Message) : AgentEvent

    data class RoundStopped(val finishReason: FinishReason, val error: Exception?=null) : AgentEvent

    /** 工具执行完毕，result 就是追加进 context 的那条 tool 消息 */
    data class ToolFinished(
        val call: ToolCall,
        val result: ContextEntry,
        val durationMs: Long,
    ) : AgentEvent
}

/**
 * The abstract message.
 * Might be a message in wechat, or an injected system message
 * Always in the format of a XML tag:
 *
 * <somename attr=... attr2=...>
 * Content here
 * </somename>
 */

sealed interface Message {
    fun packToContextBlock(): ContentBlock

    class TextMessage(
        val content: String, // the content
        val type: String, // The "somename"
        val attributes: Map<String, String>?=null, // attributes of the tag.
    ): Message {
        override fun packToContextBlock(): ContentBlock {
            var attrSection = ""
            if(attributes!=null) {
                attrSection = " "+attributes.entries.joinToString(" ") { it.key + "=" + it.value }
            }
            return ContentBlock(ContentType.TEXT, "<"+type+attrSection+">"+content+"</"+type+">")
        }
    }

    class ImageMessage(
        val imageUrl: String
    ): Message {
        override fun packToContextBlock(): ContentBlock {
            return ContentBlock(ContentType.IMAGE, imageUrl)
        }
    }
}

enum class Phase {
    IDLE,
    MODEL,
    TOOL
}

// todo: compaction

/**
 * One agentic loop bound to one conversation.
 *
 * The loop is the only writer of [context]: append the model's reply, run the tools it asked
 * for, append their results, and stop as soon as the model answers without asking for a tool.
 * Everything that can block is a suspend call, so cancellation just works.
 */
class Agent(
    private val model: Model,
    private val tools: Map<String, Tool> = hashMapOf(),
    private val maxTurns: Int = 96,
    private val coroutineScope: CoroutineScope,
) {
    public val context = arrayListOf<ContextEntry>()
    public var effort: String? = null
    public var currentSessionID: String = UUID.randomUUID().toString();
    private val inbox = Channel<Message>(Channel.UNLIMITED)

    fun send(msg: Message): Boolean = inbox.trySend(msg).isSuccess

    private val _events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * The event bus for the agent, used to receive agent's responses.
     *
     * Use events.collect { e-> } to subscribe.
     */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /**
     * The tool call-tool result pair should not be broken, and this MUST NOT be called before the tool result is added to context.
     */
    private fun appendMessage(msg: Message) {
        _events.tryEmit(AgentEvent.AssistantReceivedMessage(msg))
        if(context.isEmpty()||context[context.size-1].role != Role.USER){
            context.add(ContextEntry(Role.USER, arrayListOf(msg.packToContextBlock())))
        }else{
            context[context.size-1].content.add(msg.packToContextBlock())
        }
    }

    /**
     * The core lifetime worker of the agent
     */
    private val worker = coroutineScope.launch {
        var phase = Phase.IDLE
        var turns = 0
        var pendingToolCalls = arrayListOf<ToolCall>()

        while (true) {
            try {
                when (phase) {
                    Phase.IDLE -> {
                        val msg = inbox.receiveCatching();
                        if(msg.getOrNull()==null) break
                        appendMessage(msg.getOrThrow())
                        while (true) {
                            val next = inbox.tryReceive().getOrNull() ?: break
                            appendMessage(next)
                        }

                        turns = 0
                        phase = Phase.MODEL
                    }

                    Phase.MODEL -> {
                        if (++turns > maxTurns) {
                            appendMessage(Message.TextMessage("The maximum turns for a single round has been reached.", "system"))
                            _events.emit(AgentEvent.RoundStopped(FinishReason.MAX_ROUND_REACHED))
                            phase = Phase.IDLE
                            continue
                        }

                        val request = async {
                            model.request(context.toList(), tools.values.map { it.meta }, effort, currentSessionID)
                                .onEach { e ->
                                    if (e is ModelEvent.TextDelta){
                                        _events.tryEmit(
                                            AgentEvent.AssistantDelta(
                                                e.text
                                            )
                                        )
                                    }
                                }
                                .filterIsInstance<ModelEvent.Completed>()
                                .firstOrNull() ?: error("no Completed")
                        }

                        select {
                            request.onAwait { reply ->
                                when (reply.finishReason) {
                                    FinishReason.CONTENT_FILTER, FinishReason.REQUEST_TOO_LARGE -> {
                                        // todo: Process this extreme case. Likely context is unusable.
                                        _events.emit(AgentEvent.RoundStopped(reply.finishReason))
                                        phase = Phase.IDLE
                                    }

                                    FinishReason.UNKNOWN_ERROR -> {
                                        appendMessage(Message.TextMessage("Assistant failed: error occurred while requesting LLM: "+reply.exception?.message, "system"))
                                        _events.emit(AgentEvent.RoundStopped(reply.finishReason, reply.exception))
                                        phase = Phase.IDLE
                                    }

                                    else -> {
                                        context += reply.message
                                        _events.emit(AgentEvent.AssistantReplied(reply.message))

                                        if (reply.finishReason != FinishReason.TOOL_CALLS) {
                                            _events.emit(AgentEvent.RoundStopped(reply.finishReason))
                                            phase = Phase.IDLE
                                        } else {
                                            pendingToolCalls += reply.message.toolCalls
                                            phase = Phase.TOOL
                                        }
                                    }
                                }
                            }
                            inbox.onReceive { msg ->
                                request.cancelAndJoin()
                                appendMessage(msg)
                                while (true) {
                                    val next = inbox.tryReceive().getOrNull() ?: break
                                    appendMessage(next)
                                }
                                turns = 0
                                phase = Phase.MODEL // explicit
                            }
                        }
                        continue
                    }

                    Phase.TOOL -> {
                        if (pendingToolCalls.isEmpty()) {
                            phase = Phase.MODEL
                            continue
                        }
                        val msg = inbox.tryReceive().getOrNull();
                        if (msg != null) {
                            for (i in pendingToolCalls) {
                                var reply = i.reply("<error>tool execution was cancelled due to interrupting user input</error>");
                                context += reply
                                _events.emit(AgentEvent.ToolFinished(i, reply, -1))
                            }
                            appendMessage(msg)
                            while (true) {
                                val next = inbox.tryReceive().getOrNull() ?: break
                                appendMessage(next)
                            }
                            phase = Phase.MODEL
                            pendingToolCalls.clear()
                            turns = 0
                            continue
                        }

                        val call = pendingToolCalls.removeAt(0)

                        val timestamp = System.currentTimeMillis()
                        val result = execute(call)
                        context += result
                        _events.emit(AgentEvent.ToolFinished(call, result, System.currentTimeMillis() - timestamp))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(AgentEvent.RoundStopped(FinishReason.UNKNOWN_ERROR, e))
                pendingToolCalls.clear()
                turns = 0
                phase = Phase.IDLE
            }
        }
    }

    suspend fun Flow<ModelEvent>.awaitReply(): ModelEvent.Completed =
        filterIsInstance<ModelEvent.Completed>().firstOrNull()
            ?: error("model stream ended without a Completed event")

    private suspend fun execute(call: ToolCall): ContextEntry {
        val tool = tools[call.name]
            ?: return call.reply("<error>Unknown tool: ${call.name}</error>")

        val args = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }
            .getOrElse { return call.reply("<error>Invalid arguments for '${call.name}': ${it.message}</error>") }

        return try {
            call.reply(tool.execute(ToolContext(args, this)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            call.reply("<error>Tool '${call.name}' failed: ${e.message ?: e::class.simpleName}</error>")
        }
    }

    private fun ToolCall.reply(content: List<ContentBlock>) =
        ContextEntry(Role.TOOL, ArrayList(content), toolCallID = call_id)

    private fun ToolCall.reply(text: String) =
        reply(listOf(ContentBlock(ContentType.TEXT, text)))
}
