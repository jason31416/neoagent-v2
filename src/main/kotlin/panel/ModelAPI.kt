package cn.jason31416.panel

import cn.jason31416.providers.ModelProviderManager
import cn.jason31416.providers.ProviderInput
import cn.jason31416.providers.ProviderNotFoundException
import cn.jason31416.providers.ProviderNotLoadedException
import cn.jason31416.providers.ProviderView
import cn.jason31416.providers.SavedModel
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.coroutines.cancellation.CancellationException

@Serializable
private data class ProvidersResponse(val providers: List<ProviderView>)

@Serializable
private data class ProviderResponse(val provider: ProviderView)

@Serializable
private data class RemoteModelsResponse(val models: List<SavedModel>)

@Serializable
private data class ModelSuccessResponse(val success: Boolean = true)

@Serializable
private data class ModelErrorResponse(val error: String)

// Decode independently of the server's JSON settings: invalid/unknown fields must fail.
private val modelApiJson = Json {}

/** Register inside panel-auth so configuration and credentials stay authenticated. */
fun Route.modelRoutes() {
    get("/api/models") {
        call.modelOperation {
            val providers = withContext(Dispatchers.IO) { ModelProviderManager.listProviders() }
            call.respond(ProvidersResponse(providers))
        }
    }

    post("/api/models/providers") {
        call.modelOperation {
            val input = call.receiveProviderInput()
            val provider = withContext(Dispatchers.IO) { ModelProviderManager.create(input) }
            call.respond(ProviderResponse(provider))
        }
    }

    post("/api/models/providers/{id}") {
        call.modelOperation {
            val id = call.providerId()
            val input = call.receiveProviderInput()
            val provider = withContext(Dispatchers.IO) { ModelProviderManager.update(id, input) }
            call.respond(ProviderResponse(provider))
        }
    }

    post("/api/models/providers/{id}/delete") {
        call.modelOperation {
            val id = call.providerId()
            withContext(Dispatchers.IO) { ModelProviderManager.delete(id) }
            call.respond(ModelSuccessResponse())
        }
    }

    post("/api/models/reload") {
        call.modelOperation {
            withContext(Dispatchers.IO) { ModelProviderManager.reload() }
            call.respond(ModelSuccessResponse())
        }
    }

    post("/api/models/providers/{id}/test") {
        call.modelOperation {
            val id = call.providerId()
            // Connection failures are safe status/testError fields in the returned view.
            val provider = withContext(Dispatchers.IO) { ModelProviderManager.test(id) }
            call.respond(ProviderResponse(provider))
        }
    }

    get("/api/models/providers/{id}/remote") {
        call.modelOperation(remote = true) {
            val id = call.providerId()
            val models = withContext(Dispatchers.IO) { ModelProviderManager.fetchRemoteModels(id) }
            call.respond(RemoteModelsResponse(models))
        }
    }
}

private fun ApplicationCall.providerId(): String =
    parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing provider ID")

private suspend fun ApplicationCall.receiveProviderInput(): ProviderInput = try {
    val body = receive<JsonObject>()
    val key = body["apiKey"]
    require(key == null || key === JsonNull || (key is JsonPrimitive && key.isString)) {
        "Invalid API key field"
    }
    val input = modelApiJson.decodeFromJsonElement<ProviderInput>(body)
    // The public API derives clearApiKey; a caller cannot override omission semantics.
    val keyText = (key as? JsonPrimitive)?.content
    when {
        key == null -> input.copy(apiKey = null, clearApiKey = false)
        key === JsonNull || keyText.isNullOrEmpty() -> input.copy(apiKey = null, clearApiKey = true)
        else -> input.copy(apiKey = keyText, clearApiKey = false)
    }
} catch (e: CancellationException) {
    throw e
} catch (_: BadRequestException) {
    throw IllegalArgumentException("Invalid provider request")
} catch (_: ContentTransformationException) {
    throw IllegalArgumentException("Invalid provider request")
} catch (_: SerializationException) {
    throw IllegalArgumentException("Invalid provider request")
}

private suspend fun ApplicationCall.modelOperation(
    remote: Boolean = false,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: ProviderNotFoundException) {
        respond(HttpStatusCode.NotFound, ModelErrorResponse("提供商不存在"))
    } catch (_: ProviderNotLoadedException) {
        respond(HttpStatusCode.ServiceUnavailable, ModelErrorResponse("模型提供商配置尚未加载"))
    } catch (_: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ModelErrorResponse("提供商配置或请求参数不正确"))
    } catch (_: Exception) {
        if (remote) {
            respond(HttpStatusCode.BadGateway, ModelErrorResponse("无法从提供商获取模型列表"))
        } else {
            respond(HttpStatusCode.InternalServerError, ModelErrorResponse("模型提供商操作失败"))
        }
    }
}
