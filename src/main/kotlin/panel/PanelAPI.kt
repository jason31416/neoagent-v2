package cn.jason31416.panel

import cn.jason31416.NeoagentRuntime
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import java.util.Date

fun Routing.apiRoutes() {
    get("/api") {
        call.respond(mapOf(
            "status" to "ok"
        ))
    }
    // todo: authentication api

    // For the main panel
    get("/api/status") {
        call.respond(mapOf(
            "version" to NeoagentRuntime.version,
            "statistics" to mapOf(
                "running" to System.currentTimeMillis() - NeoagentRuntime.launchTime,
                "agents" to -1, // todo: How many active agents are running
                "channels" to -1, // todo: How many active channels are connected
                "dailyTokenUsage" to listOf<Int>(), // todo: bar graph/line graph of token usage
                "dailyMessagesHandled" to listOf<Int>(), // todo: bar graph/line graph of number of messages handled each day
                "systemInfo" to mapOf(
                    "osInfo" to System.getProperty("os.name"),
                    "systemTime" to Date().toLocaleString(),
                    "cpuPercentage" to -1,
                    "memory" to -1,
                )
            )
        ))
    }

    // todo: configuration api

    /*
    Will have:
    - main config
    - agent configs (and the ability to add/remove agents, etc.)
    - channel configs (structure vary based on channel)
    - model provider/model configs (require interactive ping functions that allow fetching and testing of models)
     */

    // todo: agents monitor api

    // websocket: one master connection to push notifications/send messages for all agents
}
