package cn.jason31416.framework

import cn.jason31416.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface AgentEvent {
    /** 流式文本，临时的；UI 可以边收边显示 */
    data class AssistantDelta(val text: String) : AgentEvent

    /** 流式思考过程，临时的；UI 可以边收边显示 */
    data class ReasoningDelta(val text: String) : AgentEvent

    /** 已提交的 assistant 消息；ui最终显示应该以此为基准，包含思考过程，输出，以及工具调用*/
    data class AssistantReplied(val message: ContextEntry, val usage: TokenUsage?=null, val interrupted: Boolean=false) : AgentEvent

    /** agent收到消息（包括用户消息/系统提示/其他注入消息等）*/
    data class AssistantReceivedMessage(val message: Message) : AgentEvent

    data class RoundStopped(val finishReason: FinishReason, val error: Exception?=null) : AgentEvent

    /** 工具执行完毕，result 就是追加进 context 的那条 tool 消息 */
    data class ToolFinished(
        val call: ToolCall,
        val result: ContextEntry,
        val durationMs: Long,
    ) : AgentEvent

    class CompactionTriggered: AgentEvent

    data class CompactionFinished(val summary: String): AgentEvent
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
                attrSection = " "+attributes.entries.joinToString(" ") { it.key + "=" + it.value}
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
val defaultCompactPrompt: String = """
        You are now acting as a compaction engine for neoagent, an AI assistant. Condense the conversation ABOVE into a structured checkpoint that lets another model resume the work with no loss of essential context. This assistant completes real-world tasks with tools (searching, reading, extracting, computing, sending, writing)

        Output the Markdown structure below. Use terse bullets, not prose paragraphs. Write "(none)" for an empty section.

        ## Current Request and Intent
        - [the current unfinished goal that the agent is working on; quote from user verbatim where the exact wording matters]

        ## User Preferences and Constraints
        - [stated preferences, hard constraints, required formats, deadlines, tone, and things to avoid]

        ## Key Facts and Sources
        - [established facts the outcome depends on or anything you found to be worth remembering, each tagged with its origin: user statement, tool result (name the tool or URL), or inference]

        ## Actions Taken and Outcomes
        - [tool calls that produced a key result or changed something: what was done, where, and the outcome. Record every external side effect — messages sent, files written, records updated, purchases or bookings made — so it is never repeated]

        ## Data and Artifacts
        - [values, IDs, references, extracted content, links, and produced outputs worth keeping]

        ## Errors and Recoveries
        - [failed tool calls or wrong turns: what went wrong and what fixed or worked around it]

        ## Pending Tasks
        - [explicitly requested work not yet completed]

        ## Current Work
        - [precisely what was in progress at this checkpoint]

        ## Next Step
        - [the single next action, directly in line with the most recent request, or "(none)"]

        ## Critical Context
        - [decisions and their rationale, open questions, assumptions still to verify, and the data needed to continue]

        Rules:
        - Write concise prose in the same language as the user's most recent messages.
        - Preserve exact names, URLs, identifiers, numbers, dates, quotes, and command or parameter strings; never round, translate, or paraphrase them.
        - Capture user instructions and corrections faithfully, especially the latest ones: the most recent instruction overrides all earlier ones.
        - Keep verified facts separate from assumptions; label anything unverified as an assumption.
        - Discard dead ends, transient tool noise, and details no longer relevant to the goal — keep only what a model needs to continue the work.
        - Prefer durable state over narration: what is true now, not how the conversation unfolded.
        - Do NOT mention this summarization request or that the context was compacted.
        - Output only the checkpoint text: do not call any tool or take any other action.
        - If the conversation already contains a <compacted> block, it is a PRIOR checkpoint. Do not copy it forward verbatim: preserve still-true facts, drop stale ones, and merge newer information into a single consolidated summary under the same structure.
    """.trimIndent()

/**
 * One agentic loop bound to one conversation.
 *
 * The loop is the only writer of [context]: append the model's reply, run the tools it asked
 * for, append their results, and stop as soon as the model answers without asking for a tool.
 * Everything that can block is a suspend call, so cancellation just works.
 */
class AgentLoop(
    private var model: Model,
    public val context: Context,
    private val tools: Map<String, Tool> = hashMapOf(),
    private val maxTurns: Int = 96,
    private val coroutineScope: CoroutineScope,
    private val compactPrompt: String = defaultCompactPrompt
) {
    public var effort: String? = null
    public var currentSessionID: String = UUID.randomUUID().toString();
    private var lastContextUsage: Int? = null;
    private val inbox = Channel<Message>(Channel.UNLIMITED);

    fun send(msg: Message): Boolean = inbox.trySend(msg).isSuccess

    private val _events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * For debug purposes
     */
    fun writeAgentLog(logFile: File){
        val sb = StringBuilder()
        var cnt = 1
        for (i in context.getActiveContext()){
            sb.append("## Context Entry #"+(cnt++)+": "+i.role.name)
            if(i.toolCallID!=null) sb.append(" Responding to ").append(i.toolCallID.substring(0, 15))
            sb.append("\n\n")
            if(i.reasoningContent != null){
                sb.append("> "+i.reasoningContent.replace("\n", "> \n")).append("\n")
            }
            for (j in i.content){
                when (j.type){
                    ContentType.TEXT -> sb.append(j.content).append("\n")
                    ContentType.IMAGE -> sb.append("[IMAGE]").append("\n")
                }
            }
            sb.append("\n")
            for(j in i.toolCalls){
                sb.append("[Tool call #${j.call_id.substring(0, 15)}]: ${j.name} (${j.arguments})\n")
            }
            sb.append("\n")
        }
        logFile.writeText(sb.toString())
    }

    /**
     * To attempt to compact the session's context between begin, end.
     * Returns the compacted string summary.
     */
    private suspend fun compactContext(toCompact: MutableList<ContextEntry>): String? {
        toCompact.add(
            ContextEntry(Role.USER, mutableListOf(ContentBlock(ContentType.TEXT, compactPrompt)))
        )
        val ret = model.request(
            toCompact,
            listOf(),
            effort,
            currentSessionID,
        )
            .filterIsInstance<ModelEvent.Completed>()
            .firstOrNull() ?: error("no Completed")

        ret.exception?.let {
            throw it
        }

        return ret.message.content.getOrNull(0)?.content
    }

    suspend fun compactSession(){
        _events.tryEmit(AgentEvent.CompactionTriggered())
        val compacted = compactContext(context.getActiveContext().toMutableList())
        if(compacted!=null){
            context.advanceHead()
            context.addEntry(ContextEntry(Role.USER, mutableListOf(ContentBlock(ContentType.TEXT,
                "This is an automatically generated checkpoint condensing an earlier span of the conversation to free up context. Treat the captured context as established background and build on it without restating it. Continue the task directly from the messages that follow.\n" +
                        "<compacted>$compacted</compacted>"
            ))))
            _events.tryEmit(AgentEvent.CompactionFinished(compacted))
            return
        }else{
            error("Failed to compact session.")
            //todo: utilize the following strategy to avoid this
            /**
             * Strategy: 1. call compact with full context
             * if failed: call compact until last successful message
             * if still failed: binary compaction
             */
        }
    }

    /**
     * The event bus for the agent, used to receive agent's responses.
     *
     * Use events.collect { e-> } to subscribe.
     */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /**
     * Append Message. Should not be called from outside of the class.
     *
     * The tool call-tool result pair should not be broken, and this MUST NOT be called before the tool result is added to context.
     */
    protected fun appendMessage(msg: Message) {
        _events.tryEmit(AgentEvent.AssistantReceivedMessage(msg))
        if(context.getLastContext()?.role != Role.USER){
            context.addEntry(ContextEntry(Role.USER, arrayListOf(msg.packToContextBlock())))
        }else{
            context.getLastContext()!!.content.add(msg.packToContextBlock())
        }
    }

    /**
     * The core lifetime worker of the agent
     */
    val worker = coroutineScope.launch {
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

                        if(lastContextUsage!=null && lastContextUsage!!>model.capabilities.contextLength * Config.get("agent.compact-threshold", 0.9)){
                            compactSession()
                        }

                        // For interrupt recover
                        val currentOutput: StringBuilder = StringBuilder()
                        val currentReasoning: StringBuilder = StringBuilder()

                        val request = async {
                            model.request(context.getActiveContext(), tools.values.map { it.meta }, effort, currentSessionID)
                                .onEach { e ->
                                    if (e is ModelEvent.TextDelta){
                                        if(e.textDelta!=null) {
                                            _events.tryEmit(
                                                AgentEvent.AssistantDelta(
                                                    e.textDelta
                                                )
                                            )
                                            currentOutput.append(e.textDelta)
                                        }
                                        if(e.reasoningDelta!=null) {
                                            _events.tryEmit(
                                                AgentEvent.ReasoningDelta(
                                                    e.reasoningDelta
                                                )
                                            )
                                            currentReasoning.append(e.reasoningDelta)
                                        }
                                    }
                                }
                                .filterIsInstance<ModelEvent.Completed>()
                                .firstOrNull() ?: error("no Completed")
                        }

                        select {
                            request.onAwait { reply ->
                                when (reply.finishReason) {
                                    FinishReason.CONTENT_FILTER, FinishReason.REQUEST_TOO_LARGE -> {
                                        compactSession()
                                        _events.emit(AgentEvent.RoundStopped(reply.finishReason))
                                        phase = Phase.IDLE
                                    }
                                    FinishReason.LENGTH -> {
                                        compactSession()
                                        _events.emit(AgentEvent.RoundStopped(reply.finishReason))
                                        phase = Phase.IDLE
                                    }

                                    FinishReason.UNKNOWN_ERROR -> {
                                        appendMessage(Message.TextMessage("Assistant failed: error occurred while requesting LLM: "+reply.exception?.message, "system"))
                                        _events.emit(AgentEvent.RoundStopped(reply.finishReason, reply.exception))
                                        phase = Phase.IDLE
                                    }

                                    else -> {
                                        context.addEntry(reply.message)
                                        _events.emit(AgentEvent.AssistantReplied(reply.message, usage=reply.usage))

                                        lastContextUsage = reply.usage?.totalTokens

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
                                if(currentOutput.isNotEmpty()) {
                                    val assistantMsg = ContextEntry(
                                        Role.ASSISTANT,
                                        arrayListOf(ContentBlock(ContentType.TEXT, currentOutput.toString())),
                                        reasoningContent=currentReasoning.toString().ifEmpty { null }
                                    )
                                    _events.emit(AgentEvent.AssistantReplied(assistantMsg, interrupted = true))
                                    context.addEntry(assistantMsg)
                                }
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
                                context.addEntry(reply)
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
                        context.addEntry(result)
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
