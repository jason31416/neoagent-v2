package cn.jason31416

import cn.jason31416.agent.Agent
import cn.jason31416.agent.AgentEvent
import cn.jason31416.agent.ContentType
import cn.jason31416.agent.Message
import cn.jason31416.agent.Model
import cn.jason31416.agent.ModelCapability
import cn.jason31416.agent.ToolRegistry
import cn.jason31416.agent.registerTestingTools
import cn.jason31416.providers.DeepSeekProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.WCWidth
import java.io.PrintWriter
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 一个临时的控制台 TUI：
 *
 *   ┌──────────────────────────────┐
 *   │ 日志 / 模型流式回复            │  <- 滚动区
 *   ├──────────────────────────────┤
 *   │ > 用户正在输入…                │  <- 常驻最后一行
 *   └──────────────────────────────┘
 *
 * 运行：DEEPSEEK_API_KEY=sk-... <run Main.kt>
 * 最好在真正的终端里跑（iTerm / Terminal.app），IDE 的 Run 窗口不一定支持滚动区。
 */
fun main() {
    val apiKey = System.getenv("DEEPSEEK_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("Set DEEPSEEK_API_KEY before running.")
        exitProcess(1)
    }

    // Agent 的 worker 跑在这个 scope 上，和 UI 线程分开。
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    try {
        val agent = createAgent(apiKey, scope)
        ConsoleUi().use { console ->
            runBlocking { chatLoop(agent, console) }
        }
    } catch (e: Exception) {
        // 终端关掉之后才能安全地走 stderr。
        System.err.println("fatal: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    } finally {
        scope.cancel()
    }

    println("bye")
}

// ========================================================================
// Agent 接线
// ========================================================================

/**
 * 把 provider、model、tool 接到一个 [Agent] 上。
 *
 * 这是 Main 里唯一碰 provider 的地方；下面的 chatLoop 只认 Agent 接口。
 */
private fun createAgent(apiKey: String, scope: CoroutineScope): Agent {
    val provider = DeepSeekProvider(apikey = apiKey)
    val model = Model(
        id = "deepseek-flash",
        displayName = "DeepSeek Flash",
        modelProvider = provider,
        capabilities = ModelCapability(maxImage = 600),
    )

    // ToolRegistry 是个单例：注册进去之后只能按名字取，没有“列出全部”的接口。
    registerTestingTools()
    val tools = ToolRegistry.allTools()

    return Agent(model = model, tools = tools, coroutineScope = scope)
}

// ========================================================================
// Agent 交互：整个程序只有这里读写 Agent
// ========================================================================

/**
 * 聊天主循环。
 *
 * 两个输入源：
 *  - 键盘：Enter 把当前行交给 [Agent.send]；
 *  - 事件：把 [Agent.events] 转成 [AgentEvent] 逐个画到控制台。
 *
 * 两者都通过 Channel 汇到这个单线程的 select 循环里，所以同一时刻
 * 只有一个线程写终端，不会互相撕屏。
 */
private suspend fun chatLoop(agent: Agent, console: ConsoleUi) = coroutineScope {
    // Agent 暴露的是 SharedFlow；转成 Channel 才能和键盘一起 select。
    // UNDISPATCHED：先完成订阅再往下走，避免漏掉最开始的事件。
    val agentEvents = Channel<AgentEvent>(Channel.UNLIMITED)
    val pump = launch(start = CoroutineStart.UNDISPATCHED) {
        agent.events.collect { agentEvents.send(it) }
    }

    val input = InputLine()
    val decoder = KeyDecoder()
    console.prompt(input)

    while (true) {
        val exit = select<Boolean> {
            console.keys.onReceiveCatching { result ->
                if (result.isClosed) {
                    true
                } else {
                    handleKey(decoder.feed(result.getOrThrow()), input, console, agent)
                }
            }

            agentEvents.onReceive { event ->
                runCatching { renderAgentEvent(console, event) }
                    .onFailure { console.log("[error] render failed: ${it.message}\n") }
                false
            }
        }

        if (exit) break
    }

    pump.cancel()
}

/**
 * 处理一个按键。返回 true 表示退出循环。
 *
 * 行编辑只做了最基础的：可打印字符、Backspace、Enter、Ctrl-C/Ctrl-D。
 * 方向键和 PgUp/PgDn 用来翻历史，不会进输入框。
 */
private fun handleKey(
    key: Key,
    input: InputLine,
    console: ConsoleUi,
    agent: Agent,
): Boolean = when (key) {
    Key.Interrupt -> true

    Key.Enter -> {
        submit(input, console, agent)
        false
    }

    Key.Backspace -> {
        input.backspace()
        console.prompt(input)
        false
    }

    is Key.Char -> {
        input.insert(key.codePoint)
        console.prompt(input)
        false
    }

    Key.Up -> {
        console.scrollUp(1)
        false
    }

    Key.Down -> {
        console.scrollDown(1)
        false
    }

    Key.PageUp -> {
        console.scrollUp(console.pageSize)
        false
    }

    Key.PageDown -> {
        console.scrollDown(console.pageSize)
        false
    }

    Key.Home -> {
        console.scrollToTop()
        false
    }

    Key.End -> {
        console.scrollToBottom()
        false
    }

    Key.Ignore -> false
}

/** Enter：把输入行交给 Agent，然后立刻回到输入状态。 */
private fun submit(input: InputLine, console: ConsoleUi, agent: Agent) {
    val line = input.take()

    // 发新消息时回到底部，保证能看到自己刚发的和接下来的回复。
    console.scrollToBottom()
    console.hidePrompt()

    if (line.isNotBlank()) {
        console.log("> $line\n", Ansi.USER)
        if (!agent.send(Message.TextMessage(line, "user"))) {
            console.log("[error] agent is not accepting messages\n")
        }
    }

    console.prompt(input)
}

class ToggleStore {
    companion object {
        var toggleState = false
    }
}

/**
 * [AgentEvent] -> 控制台文本。
 *
 * 这里只做显示，不改变 Agent 的任何状态。
 */
private fun renderAgentEvent(console: ConsoleUi, event: AgentEvent) {
    when (event) {
        // 流式文本，来一个字写一个字。
        is AgentEvent.AssistantDelta -> {
            if(ToggleStore.toggleState) console.log("\n")
            ToggleStore.toggleState = false
            console.log(event.text)
        }

        // 这一轮的 assistant 消息已经提交；如果有 tool call，在这里显示。
        is AgentEvent.AssistantReplied -> {
            console.ensureNewline()
            event.message.toolCalls.forEach { call ->
                console.log("  ⚙ ${call.name}(${call.arguments})\n", Ansi.YELLOW)
            }
        }

        // 工具执行完，result 就是追加进 context 的那条消息。
        is AgentEvent.ToolFinished -> {
            val result = event.result.content.joinToString(" ") { when(it.type){
                ContentType.IMAGE -> "[Image]"
                ContentType.TEXT -> it.content
            } }
            console.log("  ↳ $result (${event.durationMs}ms)\n", Ansi.GRAY)
        }

        is AgentEvent.RoundStopped -> {
            console.ensureNewline()
            event.error?.let { console.log("[error] ${it.message}\n", Ansi.RED) }
            console.log("[round stopped: ${event.finishReason}]\n", Ansi.DIM)
        }

        is AgentEvent.ReasoningDelta -> {
            ToggleStore.toggleState = true
            console.log(event.text, Ansi.DIM)
        }

        // 用户自己发的消息，不用回显（submit 里已经打过 "> ..." 了）。
        is AgentEvent.AssistantReceivedMessage -> Unit
    }
}

// ========================================================================
// 输入行
// ========================================================================

/**
 * 一行输入：用 code point 列表存，而不是 String。
 *
 * 因为中文/emoji 一个字符可能占两个终端列，String 的下标和显示宽度不是一回事；
 * 直接存 code point，光标位置和宽度都按字符算。
 */
private class InputLine {
    private val chars = ArrayList<Int>()

    var cursor = 0
        private set

    fun insert(codePoint: Int) {
        chars.add(cursor, codePoint)
        cursor++
    }

    fun backspace() {
        if (cursor > 0) {
            chars.removeAt(cursor - 1)
            cursor--
        }
    }

    /** 取出内容并清空，相当于“提交”。 */
    fun take(): String {
        val text = String(chars.toIntArray(), 0, chars.size)
        chars.clear()
        cursor = 0
        return text
    }

    /** 光标左边占多少终端列。 */
    fun widthBeforeCursor(): Int {
        var width = 0
        for (i in 0 until cursor) width += WCWidth.wcwidth(chars[i]).coerceAtLeast(0)
        return width
    }

    override fun toString(): String = String(chars.toIntArray(), 0, chars.size)
}

/** 解码后的按键。 */
private sealed interface Key {
    data class Char(val codePoint: Int) : Key
    data object Enter : Key
    data object Backspace : Key
    data object Interrupt : Key
    data object Up : Key
    data object Down : Key
    data object PageUp : Key
    data object PageDown : Key
    data object Home : Key
    data object End : Key
    data object Ignore : Key
}

/**
 * 把原始字节流解码成 [Key]。
 *
 * 方向键/PgUp 在终端里是 `ESC [ A`、`ESC [ 5 ~` 这样的转义序列，
 * 这里只认这几个常用的，其他序列一律 Ignore（不会漏进输入框）。
 */
private class KeyDecoder {
    private val sequence = StringBuilder()
    private var inEscape = false

    fun feed(byte: Int): Key {
        if (inEscape) {
            // ESC 后面的第一个字符必须是 [ 或 O，否则整个序列丢掉。
            if (sequence.isEmpty()) {
                if (byte == '['.code || byte == 'O'.code) {
                    sequence.append(byte.toChar())
                } else {
                    inEscape = false
                }
                return Key.Ignore
            }

            // CSI 的 final byte 在 0x40..0x7E；之前的都是参数。
            if (byte in 0x40..0x7E) {
                val seq = sequence.toString() + byte.toChar()
                inEscape = false
                sequence.clear()
                return when (seq) {
                    "[A", "OA" -> Key.Up
                    "[B", "OB" -> Key.Down
                    "[5~" -> Key.PageUp
                    "[6~" -> Key.PageDown
                    "[H", "[1~", "[7~" -> Key.Home
                    "[F", "[4~", "[8~" -> Key.End
                    else -> Key.Ignore
                }
            }

            sequence.append(byte.toChar())
            return Key.Ignore
        }

        return when (byte) {
            27 -> {
                inEscape = true
                sequence.clear()
                Key.Ignore
            }

            13, 10 -> Key.Enter
            127, 8 -> Key.Backspace
            3, 4 -> Key.Interrupt
            else -> if (byte >= 32) Key.Char(byte) else Key.Ignore
        }
    }
}

// ========================================================================
// 终端封装（和 Agent 无关）
// ========================================================================

/** 控制台颜色。SGR 转义不会影响显示宽度，所以只在中画到屏幕时拼。 */
private object Ansi {
    const val RESET = "\u001B[0m"
    const val DIM = "\u001B[2m"
    const val RED = "\u001B[31m"
    const val YELLOW = "\u001B[33m"
    const val GRAY = "\u001B[90m"
    const val USER = "\u001B[1;36m" // 粗体青色
    const val PROMPT = "\u001B[36m"
}

/** 一行日志：文本 + 整行的颜色。 */
private data class StyledLine(val text: String, val style: String)

/**
 * 终端的最底层封装，只做三件事：
 *
 *  - [log]：往日志区追加文本（流式回复、工具结果、错误都走这里）；
 *  - [prompt]：在最后一行画 "> ..." 并把光标摆到输入位置；
 *  - [keys]：原始按键流。
 *
 * 实现刻意**不用滚动区（DECSTBM）和光标保存（ESC 7/8）**：这两样在不同终端
 * 上行为不一致，实测会在真终端里把 prompt 画歪。这里只用最基础的三样：
 *
 *  - CUP `ESC [ r ; c H`：把光标放到指定行列；
 *  - EL  `ESC [ 2 K`：清掉整行；
 *  - 备用屏 `ESC [ ? 1049 h/l`：退出时恢复用户原来的终端内容。
 *
 * 行缓冲在内存里维护，渲染时只重画有变化的行。
 */
private class ConsoleUi : AutoCloseable {
    private val terminal: Terminal = TerminalBuilder.builder().system(true).build()
    private val out: PrintWriter = terminal.writer()
    private val rows: Int = terminal.size.rows
    private val cols: Int = terminal.size.columns

    /** 已经换行结束的行。 */
    private val history = ArrayDeque<StyledLine>()

    /** 正在写、还没换行的一行。 */
    private val current = StringBuilder()
    private var currentWidth = 0
    private var currentStyle = ""

    private var promptVisible = false
    private var promptText = ""
    private var promptWidth = 0

    /** 上一帧画到屏幕上的内容，用来跳过没变化的行。 */
    private val painted = arrayOfNulls<String>(rows + 1)

    /** 视口离底部多少行；0 表示跟随最新输出。 */
    private var scrollOffset = 0

    init {
        require(rows >= 3 && cols >= 10) {
            "not a real terminal (${rows}x${cols}). " +
                "Run it in iTerm/Terminal, or enable IntelliJ's " +
                "\"Emulate terminal in output console\"."
        }

        // 原始模式：终端不再自己回显、不再按行缓冲，按键一个字节一个字节地来。
        terminal.enterRawMode()

        out.print("\u001B[?1049h") // 进入备用屏，不污染用户原来的滚动记录
        out.print("\u001B[2J")
        out.flush()
    }

    /**
     * 原始按键流。单独一个守护线程阻塞读，读到就丢进 Channel；
     * 终端关闭时 read() 会返回 -1 或抛异常，顺手把 Channel 关掉。
     */
    val keys: ReceiveChannel<Int> = Channel<Int>(Channel.UNLIMITED).also { channel ->
        thread(isDaemon = true, name = "tui-keys") {
            try {
                val reader = terminal.reader()
                while (true) {
                    val key = reader.read()
                    if (key < 0) break
                    channel.trySend(key)
                }
            } catch (_: Exception) {
                // 终端已关闭，正常退出
            } finally {
                channel.close()
            }
        }
    }

    /** 往日志区追加文本；`\n` 会开新行。 */
    fun log(text: String, style: String = "") {
        // 一行只记一个颜色：真出现中途换色，就先结束上一行。
        if (current.isNotEmpty() && currentStyle != style) newLine()
        currentStyle = style

        val linesBefore = history.size

        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            i += Character.charCount(codePoint)

            when (codePoint) {
                '\n'.code -> newLine()
                '\r'.code -> Unit
                else -> append(codePoint)
            }
        }

        // 正在往上翻历史时，新输出不应该把视口顶走：等量增加偏移，画面保持不动。
        val added = history.size - linesBefore
        if (scrollOffset > 0 && added > 0) scrollOffset += added

        render()
    }

    /** 如果当前不在行首，补一个换行。 */
    fun ensureNewline() {
        if (current.isNotEmpty()) {
            newLine()
            render()
        }
    }

    /** 在最后一行画输入框。 */
    fun prompt(input: InputLine) {
        promptText = input.toString()
        promptWidth = input.widthBeforeCursor()
        promptVisible = true
        render()
    }

    /** 擦掉输入框（提交消息时用）。 */
    fun hidePrompt() {
        promptVisible = false
        render()
    }

    // ------------------------------------------------------------ 行缓冲

    private fun append(codePoint: Int) {
        val width = WCWidth.wcwidth(codePoint).coerceAtLeast(0)

        // 这一行放不下了就自动换行。
        if (currentWidth + width > cols && currentWidth > 0) newLine()

        current.appendCodePoint(codePoint)
        currentWidth += width
    }

    private fun newLine() {
        history.addLast(StyledLine(current.toString(), currentStyle))
        while (history.size > MAX_HISTORY) history.removeFirst()
        current.clear()
        currentWidth = 0
    }

    // ------------------------------------------------------------ 滚动

    /** 一屏能翻多少行（最后一行留给 prompt）。 */
    val pageSize: Int get() = maxOf(1, rows - 2)

    /** 往上翻 [lines] 行。 */
    fun scrollUp(lines: Int) {
        scrollOffset = (scrollOffset + lines).coerceAtMost(maxScroll())
        render()
    }

    /** 往下翻 [lines] 行。 */
    fun scrollDown(lines: Int) {
        scrollOffset = (scrollOffset - lines).coerceAtLeast(0)
        render()
    }

    fun scrollToTop() {
        scrollOffset = maxScroll()
        render()
    }

    fun scrollToBottom() {
        if (scrollOffset != 0) {
            scrollOffset = 0
            render()
        }
    }

    private fun maxScroll(): Int = maxOf(0, lineCount() - (rows - 1))

    private fun lineCount(): Int = history.size + 1

    private fun lineAt(index: Int): StyledLine =
        if (index < history.size) history[index] else StyledLine(current.toString(), currentStyle)

    // ------------------------------------------------------------ 渲染

    /**
     * 把“最后 rows-1 行”画到屏幕上，最后一行留给 prompt。
     * 内容底部对齐，看起来像聊天记录自然向上滚。
     */
    private fun render() {
        val total = lineCount()
        scrollOffset = scrollOffset.coerceIn(0, maxScroll())

        // 视口是 [start, end)：end 由“离底部多少行”决定。
        val end = total - scrollOffset
        val start = maxOf(0, end - (rows - 1))

        val window = ArrayList<StyledLine>(rows - 1)
        for (index in start until end) window += lineAt(index)

        // 内容底部对齐，少的时候贴在 prompt 上方。
        val blank = (rows - 1) - window.size
        for (row in 1 until rows) {
            val line = window.getOrNull(row - 1 - blank)
            if (line == null || line.text.isEmpty()) {
                paint(row, "")
            } else {
                paint(row, line.style + line.text + Ansi.RESET, line.style + line.text)
            }
        }

        if (promptVisible) {
            val text = fit(promptText, cols - 2)
            // 把滚动偏移放进缓存 key，翻页时强制重画这一行。
            paint(rows, Ansi.PROMPT + "> " + Ansi.RESET + text, "$text|$scrollOffset")

            if (scrollOffset > 0) {
                val badge = "[↑ $scrollOffset]"
                out.print(goto(rows, maxOf(1, cols - badge.length + 1)))
                out.print(Ansi.GRAY + badge + Ansi.RESET)
            }

            out.print(goto(rows, (3 + promptWidth).coerceIn(1, cols)))
        } else {
            paint(rows, "")
        }

        out.flush()
    }

    private fun paint(row: Int, text: String, cacheKey: String = text) {
        if (painted[row] == cacheKey) return
        painted[row] = cacheKey

        out.print(goto(row, 1))
        out.print("\u001B[2K")
        out.print(text)
    }

    /** 按显示宽度截断，避免中文把行撑爆。 */
    private fun fit(text: String, maxWidth: Int): String {
        var width = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val charWidth = WCWidth.wcwidth(codePoint).coerceAtLeast(0)
            if (width + charWidth > maxWidth) break
            width += charWidth
            i += Character.charCount(codePoint)
        }
        return text.substring(0, i)
    }

    override fun close() {
        out.print(Ansi.RESET)
        out.print("\u001B[?1049l") // 离开备用屏，恢复用户原来的终端内容
        out.print("\u001B[?25h") // 确保光标可见
        out.flush()
        terminal.close()
    }

    private companion object {
        const val MAX_HISTORY = 5000
    }

    private fun goto(row: Int, column: Int) = "\u001B[$row;${column}H"
}
