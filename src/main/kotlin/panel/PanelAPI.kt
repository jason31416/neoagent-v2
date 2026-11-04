package cn.jason31416.panel

import cn.jason31416.NeoagentRuntime
import cn.jason31416.NeoagentRuntime.generateSecret
import cn.jason31416.database.DatabaseHandler
import cn.jason31416.database.MetaTable
import cn.jason31416.database.db
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import org.kotlincrypto.hash.sha2.SHA256
import java.security.SecureRandom
import java.util.Date

private var frontendAuthorizationToken: String? = null

@Serializable
data class RegisterBody(val secret: String, val password: String)
@Serializable
data class LoginBody(val password: String)
fun Routing.apiRoutes() {
    get("/api") {
        call.respond(mapOf(
            "status" to "ok"
        ))
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
        call.respond(mapOf(
            "status" to pswHash.isNotEmpty()
        ))
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
                it[MetaTable.value] = String(SHA256().digest(password.toByteArray()))
            }
        }

        NeoagentRuntime.secret = null
        frontendAuthorizationToken = generateSecret()
        call.respond(mapOf(
            "success" to true,
            "token" to frontendAuthorizationToken,
        ))
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

        if(String(SHA256().digest(body.password.toByteArray())) != pswHash){
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }

        NeoagentRuntime.secret = null
        frontendAuthorizationToken = generateSecret()
        call.respond(mapOf(
            "success" to true,
            "token" to frontendAuthorizationToken,
        ))
    }

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
