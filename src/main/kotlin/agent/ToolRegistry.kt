package cn.jason31416.agent

import kotlinx.serialization.json.*
import java.security.InvalidParameterException
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.callSuspendBy

data class ToolContext(
    val parameters: Map<String, JsonElement>,
    val agent: Agent,
)

fun ToolContext.getParameter(name: String): JsonElement? = parameters[name]

data class Tool(
    val meta: ToolDefinition,
    val execute: suspend (ToolContext) -> List<ContentBlock>,
)

annotation class Description(
    val description: String
)

class ToolRegistry {
    companion object {
        private val tools = HashMap<String, Tool>()

        fun registerTool(tool: Tool) {
            tools[tool.meta.name] = tool
        }

        // todo: Support for lists?
        fun registerTool(toolFunction: KFunction<*>){
            val rt = toolFunction.returnType
            require(!rt.isMarkedNullable) { "Return type cannot be null" }
            require(rt.classifier == List::class) {
                "${toolFunction.name}'s return type must be List<ContentBlock>"
            }
            val element = rt.arguments.singleOrNull()?.type
            require(element?.classifier == ContentBlock::class) {
                "${toolFunction.name}'s return type must be List<ContentBlock>"
            }

            val params = toolFunction.parameters
            val paramL = ArrayList<Parameter>()
            for(param in params){
                val paramType: ParameterType = when(param.type.classifier) {
                    String::class -> ParameterType.STRING
                    Boolean::class -> ParameterType.BOOLEAN
                    Int::class -> ParameterType.INTEGER
                    Double::class -> ParameterType.NUMBER
                    else -> error("Parameter type for ${param.name} not supported")
                }
                var desc: String?=null;
                for(annotation in param.annotations){
                    if(annotation is Description){
                        desc = annotation.description
                    }
                }
                if(desc == null) error("Parameter must have @ParamDescription set.")
                require(param.name!=null) {
                    "Parameter name cannot be null"
                }
                paramL.add(Parameter(paramType, param.name!!, desc, required=param.isOptional))
            }
            var desc: String?=null;
            for(annotation in toolFunction.annotations){
                if(annotation is Description){
                    desc = annotation.description
                }
            }
            if(desc == null) error("Function must have @ParamDescription set.")
            val toolDefinition = ToolDefinition(
                toolFunction.name,
                desc,
                paramL
            )
            registerTool(Tool(toolDefinition) { context ->
                val args = HashMap<KParameter, Any?>()

                for (p in toolFunction.parameters) {
                    val name = p.name ?: error("Parameter name missing")

                    val element = context.parameters[name]
                    if (element == null || element is JsonNull) {
                        when {
                            // 有默认值：不放进 map，callSuspendBy 会用默认值
                            p.isOptional -> Unit
                            // 可空且没默认值：显式传 null
                            p.type.isMarkedNullable -> args[p] = null
                            else -> error("${toolDefinition.name}: missing parameter '$name'")
                        }
                    } else {
                        args[p] = element.toKotlinValue(p.type)
                    }
                }

                @Suppress("UNCHECKED_CAST")
                toolFunction.callSuspendBy(args) as List<ContentBlock>
            })
        }

        private fun JsonElement.toKotlinValue(type: KType): Any = when (type.classifier) {
            String::class  -> jsonPrimitive.content
            Boolean::class -> jsonPrimitive.boolean
            Int::class     -> jsonPrimitive.int
            Long::class    -> jsonPrimitive.long
            Double::class  -> jsonPrimitive.double
            Float::class   -> jsonPrimitive.float
            else -> error("Unsupported parameter type: $type")
        }

        fun getTool(toolName: String): Tool? = tools[toolName]
    }
}

fun registerTestingTools() {
    @Description("A tool designed for testing. Calculates a+b. Will throw when b is 0 for testing.")
    fun addition_tool(@Description("The first number, must be an integer.") a: Int, @Description("The second number, must not be 0.") b: Double): List<ContentBlock> {
        require(b!=0.0) {
            "This is a testing throw message"
        }
        return listOf(ContentBlock(ContentType.TEXT, "<result>The result of the call is "+(a+b)+"</result>"))
    }
}