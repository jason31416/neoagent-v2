package cn.jason31416.providers

import cn.jason31416.agent.ContextEntry
import cn.jason31416.agent.Model
import cn.jason31416.agent.ModelEvent
import cn.jason31416.agent.ToolDefinition
import kotlinx.coroutines.flow.Flow


/**
 * An instance of a single model provider (e.g. Deepseek would be its own instance of ModelProvider).
 *
 * If a certain provider requires special adaptation, it should extend an existing provider.
 */
interface ModelProvider {
    val id: String

    companion object {
        val providers: MutableSet<ModelProvider> = mutableSetOf()

        fun getModels(): MutableSet<Model> {
            val ret = HashSet<Model>()
            for (provider in providers) {
                ret += provider.getAvailableModels()
            }
            return ret
        }
    }

    /**
     * This requests for a refresh of the model list. It is possible that certain provider doesn't support this and must be configured manually.
     */
    fun reloadModels() {}

    /**
     * This returns a cached list of models that has been loaded/configured.
     */
    fun getAvailableModels(): List<Model>

    /**
     * This returns a cached list of models that has been loaded/configured.
     */
    fun requestModel(
        model: Model,
        context: List<ContextEntry>,
        tools: List<ToolDefinition>,
        effort: String?,
        sessionID: String,
    ): Flow<ModelEvent>
}