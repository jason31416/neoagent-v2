package cn.jason31416.providers

import cn.jason31416.framework.ContextEntry
import cn.jason31416.framework.Model
import cn.jason31416.framework.ModelCapability
import cn.jason31416.framework.ModelEvent
import cn.jason31416.framework.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class SavedModel(
    val id: String,
    val displayName: String,
    val supportsImage: Boolean,
    val reasoningEfforts: List<String>,
    val contextLength: Int,
) {
    internal fun toModel(provider: ModelProvider): Model = Model(
        id, displayName, provider,
        ModelCapability(supportsImage, reasoningEfforts.toList(), contextLength),
    )

    companion object {
        internal fun from(model: Model): SavedModel = SavedModel(
            model.id, model.displayName, model.capabilities.supportsImage,
            model.capabilities.reasoningEfforts.toList(), model.capabilities.contextLength,
        )
    }
}

/** A configured provider instance. Remote discovery never changes its saved models. */
abstract class ModelProvider {
    companion object {
        /** Read-only snapshot of the currently loaded providers. */
        val providers: Map<String, ModelProvider>
            get() = ModelProviderManager.providers
    }

    abstract val id: String

    @Volatile
    private var savedModels: Map<String, SavedModel> = emptyMap()

    /** Query the remote service without changing configuration. */
    abstract fun fetchAvailableModels(): List<Model>

    /** Check the authenticated service endpoint without generating a completion. */
    abstract fun checkConnection()

    abstract fun requestModel(
        model: Model,
        context: List<ContextEntry>,
        tools: List<ToolDefinition>,
        effort: String?,
        sessionID: String,
    ): Flow<ModelEvent>

    internal fun loadModels(models: List<SavedModel>) {
        savedModels = models.associate { it.id to it.copy(reasoningEfforts = it.reasoningEfforts.toList()) }
    }

    /** In-memory lookup; no file or network access. */
    fun getModel(id: String): Model? = savedModels[id]?.toModel(this)

    fun getModels(): List<Model> = savedModels.values.map { it.toModel(this) }

    /** Save the changed configuration and refresh this provider's in-memory model list. */
    fun addModel(model: Model) {
        require(model.modelProvider === this) { "不能添加属于其他 Provider 的模型" }
        ModelProviderManager.addModel(this, SavedModel.from(model))
    }

    fun removeModel(id: String): Boolean = ModelProviderManager.removeModel(this, id)
}
