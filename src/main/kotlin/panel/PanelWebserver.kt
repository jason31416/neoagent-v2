package cn.jason31416.panel

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.auth.session
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.default
import io.ktor.server.http.content.defaultResource
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.Serializable
import java.io.File

fun startWebServer() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json() }
        install(WebSockets)
        install(CORS) {
            anyHost()
            anyMethod()
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }
        install(Authentication) {
            bearer("panel-auth") {
                authenticate { credential ->
                    // credential.token 就是前端带来的那串字符
                    if (credential.token == frontendAuthorizationToken) {
                        UserIdPrincipal("admin")   // 卡对了，放行
                    } else {
                        null                       // 卡不对，拦下
                    }
                }
            }
        }

        routing {
            apiRoutes()

            staticResources("/", "web") {
                default("index.html")
            }
        }
    }.start(wait = true)  // wait=false 不阻塞主线程
}