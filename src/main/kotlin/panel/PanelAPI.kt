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

/** 像 mapOf 一样用，但能安全返回 JSON。 */
suspend fun ApplicationCall.respondObj(vararg pairs: Pair<String, Any?>) {
    respond(JsonObject(pairs.associate { (k, v) -> k to v.toJsonElement() }))
}