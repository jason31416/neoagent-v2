package cn.jason31416.framework

import kotlinx.serialization.json.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class ConfigFieldType(val wireName: String) {
    TEXT("text"),
    SECRET("secret"),
    NUMBER("number"),
    BOOLEAN("switch"),
    OPTIONS("select"),
    LIST("tags"),
    TEXTAREA("textarea"),
    READONLY("readonly"),
}

data class ConfigOption(val value: String, val label: String)

class ConfigField<T>(
    val key: String,
    val label: String,
    val type: ConfigFieldType,
    val defaultValue: T,
    val configSection: ConfigSection,

    val help: String? = null,
    val required: Boolean = false,
    val unit: String? = null,
    val placeholder: String? = null,
    val options: List<ConfigOption> = emptyList(),
    val disabled: Boolean = false,
) {
    var value: T
        get() = decode(configSection.read(key))
        set(value) {
            configSection.write(key, encode(value))
        }

    internal fun isBlank(): Boolean = when (val current: Any? = value) {
        null -> true
        is String -> current.isBlank()
        is Collection<*> -> current.isEmpty()
        else -> false
    }

    internal fun setJson(element: JsonElement) {
        configSection.write(key, encode(decode(element.toPlainValue())))
    }

    fun toView(): Map<String, Any?> = linkedMapOf(
        "key" to key,
        "label" to label,
        "type" to type.wireName,
        "help" to help,
        "required" to required,
        "unit" to unit,
        "placeholder" to placeholder,
        "options" to options.map {
            linkedMapOf("value" to it.value, "label" to it.label)
        },
        "disabled" to disabled,
        "value" to value,
        // 先不做 secret 掩码；接面板时再打开。
        "masked" to false,
    )

    @Suppress("UNCHECKED_CAST")
    private fun decode(raw: Any?): T = when (type) {
        ConfigFieldType.TEXT,
        ConfigFieldType.TEXTAREA,
        ConfigFieldType.SECRET,
        ConfigFieldType.READONLY ->
            (raw?.toString() ?: (defaultValue as String)) as T

        ConfigFieldType.OPTIONS -> {
            val fallback = defaultValue as String
            val text = raw?.toString() ?: fallback
            val allowed = options.map { it.value }
            (if (allowed.isEmpty() || text in allowed) text else fallback) as T
        }

        ConfigFieldType.NUMBER -> when (val fallback = defaultValue) {
            is Int -> (when (raw) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull() ?: fallback
                else -> fallback
            }) as T

            is Double -> (when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull() ?: fallback
                else -> fallback
            }) as T

            else -> defaultValue
        }

        ConfigFieldType.BOOLEAN -> {
            val fallback = defaultValue as Boolean
            (when (raw) {
                is Boolean -> raw
                is String -> raw.toBooleanStrictOrNull() ?: fallback
                is Number -> raw.toInt() != 0
                else -> fallback
            }) as T
        }

        ConfigFieldType.LIST -> {
            val fallback = defaultValue as List<String>
            val list = when (raw) {
                is Collection<*> -> raw
                    .mapNotNull { it?.toString()?.trim() }
                    .filter { it.isNotEmpty() }

                is Array<*> -> raw
                    .mapNotNull { it?.toString()?.trim() }
                    .filter { it.isNotEmpty() }

                is String -> raw
                    .split(',', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                else -> fallback
            }
            list as T
        }
    }

    private fun encode(value: T): Any? = when (type) {
        ConfigFieldType.LIST ->
            (value as? List<*>)?.map { it?.toString() } ?: emptyList<String>()

        else -> value
    }
}

open class ConfigSection(
    val id: String,
    val title: String,
    val config: Config,
) {
    private val _fields = mutableListOf<ConfigField<*>>()

    val fieldList: List<ConfigField<*>>
        get() = _fields

    internal fun read(key: String): Any? =
        ConfigManager.read(config.path, id, key)

    internal fun write(key: String, value: Any?) =
        ConfigManager.write(config.path, id, key, value)

    private fun <T> add(field: ConfigField<T>): ConfigField<T> {
        require(_fields.none { it.key == field.key }) {
            "Duplicate config key '${field.key}' in group '$id'"
        }
        _fields += field
        return field
    }

    /**
     * text / textarea / secret / number / decimal / switch / tags / readonly
     * 都只是“type + 默认值 + 元数据”不同，统一走这里。
     */
    private fun <T> valueField(
        key: String,
        label: String,
        type: ConfigFieldType,
        default: T,
        help: String? = null,
        required: Boolean = false,
        unit: String? = null,
        placeholder: String? = null,
        disabled: Boolean = false,
    ): ConfigField<T> = add(
        ConfigField(
            key = key,
            label = label,
            type = type,
            defaultValue = default,
            help = help,
            required = required,
            unit = unit,
            placeholder = placeholder,
            disabled = disabled,
            configSection = this
        )
    )

    fun text(
        key: String,
        label: String,
        default: String = "",
        help: String? = null,
        required: Boolean = false,
        placeholder: String? = null,
        disabled: Boolean = false,
    ): ConfigField<String> = valueField(
        key = key,
        label = label,
        type = ConfigFieldType.TEXT,
        default = default,
        help = help,
        required = required,
        placeholder = placeholder,
        disabled = disabled,
    )

    fun textarea(
        key: String,
        label: String,
        default: String = "",
        help: String? = null,
        required: Boolean = false,
        placeholder: String? = null,
        disabled: Boolean = false,
    ): ConfigField<String> = valueField(
        key = key,
        label = label,
        type = ConfigFieldType.TEXTAREA,
        default = default,
        help = help,
        required = required,
        placeholder = placeholder,
        disabled = disabled,
    )

    fun secret(
        key: String,
        label: String,
        default: String = "",
        help: String? = null,
        required: Boolean = false,
        placeholder: String? = null,
        disabled: Boolean = false,
    ): ConfigField<String> = valueField(
        key = key,
        label = label,
        type = ConfigFieldType.SECRET,
        default = default,
        help = help,
        required = required,
        placeholder = placeholder,
        disabled = disabled,
    )

    fun number(
        key: String,
        label: String,
        default: Int = 0,
        help: String? = null,
        required: Boolean = false,
        unit: String? = null,
        placeholder: String? = null,
        disabled: Boolean = false,
    ): ConfigField<Int> = valueField(
        key = key,
        label = label,
        type = ConfigFieldType.NUMBER,
        default = default,
        help = help,
        required = required,
        unit = unit,
        placeholder = placeholder,
        disabled = disabled,
    )

    fun decimal(
        key: String,
        label: String,
        default: Double = 0.0,
        help: String? = null,
        required: Boolean = false,
        unit: String? = null,
        placeholder: String? = null,
        disabled: Boolean = false,
    ): ConfigField<Double> = valueField(
        key = key,
        label = label,
        type = ConfigFieldType.NUMBER,
        default = default,
        help = help,
        required = required,
        unit = unit,
        placeholder = placeholder,
        disabled = disabled,
    )

    fun switch(
        key: String,
        label: String,
        default: Boolean = false,
        help: String? = null,
        required: Boolean = false,
        disabled: Boolean = false,
    ): ConfigField<Boolean> = valueField(
        key = key,
        label = label,
        type = ConfigFieldType.BOOLEAN,
        default = default,
        help = help,
        required = required,
        disabled = disabled,
    )

    fun tags(
        key: String,
        label: String,
        default: List<String> = emptyList(),
        help: String? = null,
        required: Boolean = false,
        placeholder: String? = null,
        disabled: Boolean = false,
    ): ConfigField<List<String>> = valueField(
        key = key,
        label = label,
        type = ConfigFieldType.LIST,
        default = default.toList(),
        help = help,
        required = required,
        placeholder = placeholder,
        disabled = disabled,
    )

    fun select(
        key: String,
        label: String,
        options: List<Pair<String, String>>,
        default: String = options.firstOrNull()?.first.orEmpty(),
        help: String? = null,
        required: Boolean = false,
        disabled: Boolean = false,
    ): ConfigField<String> {
        val parsedOptions = options.map { ConfigOption(it.first, it.second) }
        // 配置文件里可能留着已经删掉的选项，回退到第一个合法值。
        val fallback = default.takeIf { candidate ->
            parsedOptions.any { it.value == candidate }
        } ?: parsedOptions.firstOrNull()?.value ?: default

        return add(
            ConfigField(
                key = key,
                label = label,
                type = ConfigFieldType.OPTIONS,
                defaultValue = fallback,
                help = help,
                required = required,
                options = parsedOptions,
                disabled = disabled,
                configSection = this
            )
        )
    }

    fun readonly(
        key: String,
        label: String,
        value: String = "",
        help: String? = null,
        disabled: Boolean = true,
    ): ConfigField<String> = valueField(
        key = key,
        label = label,
        type = ConfigFieldType.READONLY,
        default = value,
        help = help,
        disabled = disabled,
    )

    fun toView(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "title" to title,
        "fields" to _fields.map { it.toView() },
    )
}

abstract class Config(
    val id: String,
    val path: String = "$id.yml",
) {
    abstract val sections: List<ConfigSection>

    /** 转成前端 SettingsView 要的 `groups`。 */
    fun sectionsView(): List<Map<String, Any?>> {
        validate()
        return sections.map { it.toView() }
    }

    /** 把前端提交的扁平 values 写进对应字段；未知 key 和只读项忽略。 */
    fun applyJson(values: JsonObject) {
        validate()

        val fields = sections
            .flatMap { it.fieldList }
            .associateBy { it.key }

        for ((key, element) in values) {
            val field = fields[key] ?: continue
            if (field.type == ConfigFieldType.READONLY) continue
            field.setJson(element)
        }
    }

    /** 同一 Config 下的 section id 和 field key 都必须唯一。 */
    fun validate() {
        val sectionIds = sections.map { it.id }
        require(sectionIds.size == sectionIds.toSet().size) {
            "Duplicate config section id in config '$id'"
        }

        val keys = sections.flatMap { it.fieldList }.map { it.key }
        require(keys.size == keys.toSet().size) {
            "Duplicate config key in config '$id'"
        }
    }
}

/** 频道配置：除 sections 外，还有前端顶部的 enabled 和 status。 */
abstract class ChannelConfig(
    id: String,
    val name: String,
    path: String,
    private val defaultEnabled: Boolean = false,
) : Config(id, path) {
    var enabled: Boolean
        get() = (ConfigManager.read(path, ConfigManager.ROOT_SECTION, ENABLED_KEY) as? Boolean)
            ?: defaultEnabled
        set(value) {
            ConfigManager.write(path, ConfigManager.ROOT_SECTION, ENABLED_KEY, value)
        }

    val status: String
        get() = when {
            !enabled -> "stopped"
            sections.flatMap { it.fieldList }.any { it.required && it.isBlank() } -> "error"
            else -> "running"
        }

    fun channelView(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "enabled" to enabled,
        "status" to status,
        "groups" to sectionsView(),
    )

    companion object {
        private const val ENABLED_KEY = "enabled"
    }
}

private fun JsonElement.toPlainValue(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        booleanOrNull != null -> booleanOrNull
        intOrNull != null -> intOrNull
        doubleOrNull != null -> doubleOrNull
        else -> contentOrNull
    }
    is JsonArray -> map { it.toPlainValue() }
    is JsonObject -> entries.associate { it.key to it.value.toPlainValue() }
}

/**
 * 每个 Config 对应一个 YAML 文件，文件内部按 ConfigSection 嵌套：
 *
 * ```yaml
 * general:
 *   setting1: hello
 * ```
 */
object ConfigManager {
    internal const val ROOT_SECTION = "__root"

    private val values =
        LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, Any?>>>()
    private val loadedPaths = mutableSetOf<String>()
    private val dirtyPaths = mutableSetOf<String>()
    private var saveSuppression = 0

    @Synchronized
    internal fun read(path: String, sectionId: String, key: String): Any? {
        ensureLoaded(path)
        return values[path]?.get(sectionId)?.get(key)
    }

    @Synchronized
    internal fun write(path: String, sectionId: String, key: String, value: Any?) {
        ensureLoaded(path)

        val sections = values.getOrPut(path) { LinkedHashMap() }
        val fields = sections.getOrPut(sectionId) { LinkedHashMap() }

        if (fields[key] == value) return
        fields[key] = value

        dirtyPaths.add(path)
        if (saveSuppression == 0) {
            save(path)
            dirtyPaths.remove(path)
        }
    }

    /** 批量修改：期间只改内存，结束后每个文件只落盘一次。 */
    fun <R> batch(block: () -> R): R {
        synchronized(this) {
            saveSuppression++
        }

        try {
            return block()
        } finally {
            synchronized(this) {
                saveSuppression--
                if (saveSuppression == 0) {
                    for (path in dirtyPaths.toList()) {
                        save(path)
                        dirtyPaths.remove(path)
                    }
                }
            }
        }
    }

    /** 手动重新加载所有已经访问过的配置文件。 */
    @Synchronized
    fun reload() {
        for (path in loadedPaths.toList()) {
            val parsed = readFromDisk(path) ?: continue
            values[path] = parsed
        }
    }

    /** 手动重新加载某个配置文件。 */
    @Synchronized
    fun reload(path: String) {
        loadedPaths.add(path)
        val parsed = readFromDisk(path) ?: return
        values[path] = parsed
    }

    /** 调试用：查看当前内存里的配置。 */
    @Synchronized
    fun snapshot(): Map<String, Map<String, Map<String, Any?>>> =
        values.mapValues { (_, sections) ->
            sections.mapValues { (_, fields) -> fields.toMap() }
        }

    private fun ensureLoaded(path: String) {
        if (path in loadedPaths) return
        loadedPaths.add(path)

        val parsed = readFromDisk(path) ?: return
        values[path] = parsed
    }

    private fun readFromDisk(path: String): LinkedHashMap<String, LinkedHashMap<String, Any?>>? {
        val file = File(path)
        if (!file.isFile) return LinkedHashMap()

        val root = try {
            file.inputStream().use { stream ->
                Yaml().load<Any?>(stream)
            }
        } catch (e: Exception) {
            System.err.println("[config] failed to read ${file.absolutePath}: ${e.message}")
            return null
        }

        val rootMap = root as? Map<*, *> ?: return null
        val parsed = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        val rootValues = LinkedHashMap<String, Any?>()

        for ((rawKey, rawValue) in rootMap) {
            val key = rawKey?.toString() ?: continue

            if (rawValue is Map<*, *>) {
                val target = LinkedHashMap<String, Any?>()
                for ((rawFieldKey, rawFieldValue) in rawValue) {
                    val fieldKey = rawFieldKey?.toString() ?: continue
                    target[fieldKey] = rawFieldValue
                }
                parsed[key] = target
            } else {
                // 根级标量，例如 enabled: true
                rootValues[key] = rawValue
            }
        }

        if (rootValues.isNotEmpty()) {
            parsed[ROOT_SECTION] = rootValues
        }

        return parsed
    }

    private fun yamlWriter(): Yaml {
        val options = DumperOptions().apply {
            // 强制展开成块状 YAML，不要出现 {a: b} 这种 flow style。
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2

            // 让 list 也缩进在 key 下面：
            // key:
            //   - item
            indicatorIndent = 2
            indentWithIndicator = true

            isAllowUnicode = true
            width = 120
        }
        return Yaml(options)
    }

    private fun save(path: String) {
        val file = File(path).absoluteFile

        val root = LinkedHashMap<String, Any?>()
        val configValues = values[path] ?: emptyMap()

        // 根级标量（例如 enabled: true）写在文件最外层。
        configValues[ROOT_SECTION]?.forEach { (key, value) ->
            root[key] = value
        }

        // 普通 section 写成嵌套 map。
        configValues.forEach { (sectionId, fields) ->
            if (sectionId != ROOT_SECTION && fields.isNotEmpty()) {
                root[sectionId] = LinkedHashMap(fields)
            }
        }

        try {
            file.parentFile?.mkdirs()

            val temp = File(file.parentFile ?: File("."), "${file.name}.tmp")
            temp.writeText(yamlWriter().dump(root))

            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (e: Exception) {
            System.err.println("[config] failed to save ${file.absolutePath}: ${e.message}")
        }
    }
}
