package cn.jason31416

import cn.jason31416.agent.ContentBlock
import cn.jason31416.agent.ContentType
import cn.jason31416.agent.ContextEntry
import cn.jason31416.agent.FinishReason
import cn.jason31416.agent.Model
import cn.jason31416.agent.ModelCapability
import cn.jason31416.agent.ModelEvent
import cn.jason31416.agent.Role
import cn.jason31416.agent.ToolDefinition
import cn.jason31416.providers.DeepSeekProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Run with:
 *
 *   DEEPSEEK_API_KEY=sk-... <run Main.kt>
 *
 * Tests the DeepSeek provider with one text prompt and one image prompt.
 */
fun main() {
    val apiKey = System.getenv("DEEPSEEK_API_KEY")
    if (apiKey.isNullOrBlank()) {
        System.err.println("Set DEEPSEEK_API_KEY before running this test.")
        return
    }

    try {
        runBlocking { runTests(apiKey) }
        println("\nAll checks passed.")
    } catch (e: Exception) {
        System.err.println("\nFAILED: ${e.message}")
        exitProcess(1)
    }
}

private suspend fun runTests(apiKey: String) {
    val provider = DeepSeekProvider(apikey = apiKey)
    val model = Model("deepseek-flash", "DeepSeek Flash", provider, ModelCapability(maxImage = 600))

    val text = runCase(
        name = "text",
        model = model,
        context = listOf(
            ContextEntry(
                role = Role.USER,
                content = mutableListOf(
                    ContentBlock(ContentType.TEXT, "Reply with exactly: hello")
                ),
            )
        ),
    )
    check(text.finishReason == FinishReason.STOP) {
        "text: unexpected finish reason ${text.finishReason}"
    }
    val textContent = text.message.content.joinToString("") { it.content }
    check(textContent.contains("hello", ignoreCase = true)) {
        "text: expected 'hello', got '$textContent'"
    }

    val image = runCase(
        name = "image",
        model = model,
        context = listOf(
            ContextEntry(
                role = Role.USER,
                content = mutableListOf(
                    ContentBlock(
                        ContentType.TEXT,
                        "What color is the shape in this image? Answer with one word.",
                    ),
                    ContentBlock(ContentType.IMAGE, RED_CIRCLE_DATA_URI),
                ),
            )
        ),
    )
    check(image.finishReason == FinishReason.STOP) {
        "image: unexpected finish reason ${image.finishReason}"
    }
    val imageContent = image.message.content.joinToString("") { it.content }
    check(imageContent.contains("red", ignoreCase = true)) {
        "image: expected something containing 'red', got '$imageContent'"
    }
}

private suspend fun runCase(
    name: String,
    model: Model,
    context: List<ContextEntry>,
): ModelEvent.Completed {
    println("\n=== $name ===")

    val streamed = StringBuilder()
    var completed: ModelEvent.Completed? = null

    model.request(
        context = context,
        tools = emptyList<ToolDefinition>(),
        effort = "none",
        sessionID = "main-test-$name",
    ).collect { event ->
        when (event) {
            is ModelEvent.TextDelta -> {
                streamed.append(event.text)
                print(event.text)
            }

            is ModelEvent.Completed -> {
                completed = event
            }
        }
    }

    val result = completed ?: error("$name: stream ended without Completed")
    println()
    println("finish=${result.finishReason}")
    println("text=$streamed")
    println("reasoning=${result.message.reasoningContent?.take(120)}")
    result.exception?.let { println("error=${it.message}") }
    check(result.exception == null) { "$name: provider error: ${result.exception?.message}" }

    return result
}

/**
 * 64x64 white image with a red circle in the middle.
 * Embedded as a data URI so the test doesn't depend on an external image host.
 */
private const val RED_CIRCLE_DATA_URI =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAAAp0lEQVR42u3ZwQ3AIAwEwauE/otKL0kDiQQEzGEWUcDOC2Hr3vwIAAAAAAAAAADADHCV8nqtAV/RszGKTx/L0ML6IQYtTB/CkEP9H4NM6rsN8qnvMxwGmF3fYZBbfavhGEBkfZMBAIAIQHx9vQEAAAAAAMwH8BIDAMCPzAGw/aeeuZAHYPvRYobhbobxeoYFR5IVU4YlX5I1a6pFNwAAAAAAAADgFMADZNh3i+S0WuMAAAAASUVORK5CYII="
