package cn.jason31416.panel

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
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
import io.ktor.server.websocket.WebSockets
import java.io.File

fun startWebServer() {
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) { json() }
        install(WebSockets)
        install(CORS) { allowHost("*") }

        routing {
            apiRoutes()

            staticResources("/", "web") {
                default("index.html")
            }
            get("/{...}") {
                call.respondBytes(
                    Thread.currentThread().contextClassLoader
                        .getResourceAsStream("web/index.html")!!.readBytes(),
                    io.ktor.http.ContentType.Text.Html
                )
            }
        }
    }.start(wait = false)  // wait=false 不阻塞主线程
}