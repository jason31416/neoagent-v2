package cn.jason31416.agent

import cn.jason31416.NeoagentRuntime
import cn.jason31416.channel.*
import cn.jason31416.database.db
import cn.jason31416.framework.*
import cn.jason31416.providers.ModelProviderManager
import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

// Each row is a ContextEntry instance
object ContextTable: Table("context") {
    val id = integer("id").autoIncrement()
    // Agent's ID
    val agent = text("agent")
    val role = text("role")
    val content = text("content") // JSON list structured content block
    val toolCalls = text("toolCalls").nullable() // JSON list structured content tool calls
    val toolCallID = text("toolCallID").nullable()
    val reasoningContent = text("reasoningContent").nullable()

    override val primaryKey = PrimaryKey(id)
}

object AgentsTable: Table("agents") {
    val id = text("id")
    val contextHead = integer("context_head").default(0)

    override val primaryKey = PrimaryKey(id)
}

class AgentContext(val systemPrompt: ()->String, val agentId: String): Context {
    init {
        transaction(db) {
            AgentsTable.insertIgnore {
                it[AgentsTable.id] = agentId
            }
        }
    }

    private fun getContextHead(): Int = transaction(db) {
        AgentsTable.selectAll()
            .where { AgentsTable.id eq agentId }
            .firstOrNull()
            ?.get(AgentsTable.contextHead) ?: 0
    }

    override fun getActiveContext(): List<ContextEntry> = transaction(db) {
        val head = AgentsTable.selectAll()
            .where { AgentsTable.id eq agentId }
            .firstOrNull()?.get(AgentsTable.contextHead) ?: 0

        listOf(ContextEntry(Role.SYSTEM, mutableListOf(ContentBlock(ContentType.TEXT, systemPrompt()))))+
        ContextTable.selectAll()
            .where { (ContextTable.agent eq agentId) and (ContextTable.id greater head) }
            .orderBy(ContextTable.id)
            .map(::rowToEntry)
    }

    override fun getLastContext(): ContextEntry? = transaction(db) {
        val head = AgentsTable.selectAll()
            .where { AgentsTable.id eq agentId }
            .firstOrNull()?.get(AgentsTable.contextHead) ?: 0

        ContextTable.selectAll()
            .where { (ContextTable.agent eq agentId) and (ContextTable.id greater head) }
            .orderBy(ContextTable.id, SortOrder.DESC)
            .firstOrNull()
            ?.let(::rowToEntry)
    }

    override fun addEntry(entry: ContextEntry) {
        transaction(db) {
            ContextTable.insert {
                it[agent] = agentId
                it[role] = entry.role.name
                it[content] = Json.encodeToString(entry.content)
                it[toolCalls] = entry.toolCalls.takeIf { it.isNotEmpty() }
                    ?.let { Json.encodeToString(it) }
                it[toolCallID] = entry.toolCallID
                it[reasoningContent] = entry.reasoningContent
            }
        }
    }

    override fun advanceHead() {
        transaction(db) {
            val lastId = ContextTable.selectAll()
                .where { ContextTable.agent eq agentId }
                .orderBy(ContextTable.id, SortOrder.DESC)
                .firstOrNull()
                ?.get(ContextTable.id)

            if (lastId != null) {
                AgentsTable.update({ AgentsTable.id eq agentId }) {
                    it[contextHead] = lastId
                }
            }
        }
    }

    private fun rowToEntry(row: ResultRow): ContextEntry = ContextEntry(
        role = Role.valueOf(row[ContextTable.role]),
        content = Json.decodeFromString(row[ContextTable.content]),
        toolCalls = row[ContextTable.toolCalls]
            ?.takeIf { it.isNotBlank() }
            ?.let { Json.decodeFromString(it) }
            ?: emptyList(),
        toolCallID = row[ContextTable.toolCallID],
        reasoningContent = row[ContextTable.reasoningContent],
    )
}

enum class AgentHandleMode {
    ALWAYS,
    MENTION
}

@Serializable
data class AgentInterfaceConfig(
    val handleMode: AgentHandleMode, // when should we trigger a round of agent?
)

@Serializable
data class AgentConfig(
    val model: String,
    val boundInterfaces: Map<String, AgentInterfaceConfig>,
    val identity: String,
    val allowedTools: List<String>
)

/**
 * A single persistent agent's instance.
 *
 * binds to several [IMChat] instances or even [Channel] instances for account-level controls, holds an [AgentLoop] instance, and holds a list of available tools.
 * Multiple Agents could be active at the same time with different tools running in different chats.
 *
 * A design choice: A single agent is not designed to work in multiple chats with multiple person though it is technically possible.
 *
 * todo: Should we make it possible to make an agent handle all rejected traffic (no agent listening)? or maybe just all unknown pms?
 */
class Agent(
    val id: String,
    @Volatile var agentConfig: AgentConfig
){
    companion object {
        val agents = ConcurrentHashMap<String, Agent>()

        fun getListeningAgents(i: AgentInterface) = agents.values.filter {
            it.agentConfig.boundInterfaces.containsKey(i.getInterfaceID())
        }

        fun loadAgents(){
            assert(agents.isEmpty())

            val agentConfigDir = File("agents/")
            if (!agentConfigDir.exists()) {
                agentConfigDir.mkdirs()
            }
            for(i in agentConfigDir.listFiles()!!) {
                if(i.isFile && i.extension == "yml") {
                    val config = try {
                        Yaml.default.decodeFromString(AgentConfig.serializer(), i.readText())
                    } catch (e: Exception) {
                        System.err.println("[agent] failed to read ${i.absolutePath}: ${e.message}")
                        continue
                    }
                    Agent(i.nameWithoutExtension, config)
                }
            }
        }

        fun reloadAgentConfigs(){
            val agentConfigDir = File("agents/")
            if (!agentConfigDir.exists()) {
                agentConfigDir.mkdirs()
            }
            val agentTmp = agents.keys.toMutableList()
            for(i in agentConfigDir.listFiles()!!) {
                if(i.isFile && i.extension == "yml") {
                    val config = try {
                        Yaml.default.decodeFromString(AgentConfig.serializer(), i.readText())
                    } catch (e: Exception) {
                        agentTmp.remove(i.nameWithoutExtension)
                        System.err.println("[agent] failed to read ${i.absolutePath}: ${e.message}. Running with old config.")
                        continue
                    }
                    if(agents.containsKey(i.nameWithoutExtension)) {
                        agentTmp.remove(i.nameWithoutExtension)
                        agents[i.nameWithoutExtension]?.agentConfig = config
                    }else{
                        Agent(i.nameWithoutExtension, config)
                    }
                }
            }
            for(i in agentTmp){
                agents[i]?.deactivate()
            }
        }
    }

    init {
        if(agents.containsKey(id)) throw IllegalArgumentException("Agent with id $id already exists")

        agents[id] = this

        //todo: send agent's message to chat. To currentChannel or by default the first channel in the boundInterfaces
    }

    // The interfaces (IMChat or Channel) that the agent handle.

    val context = AgentContext({"TODO"}, id)
    val agentLoop = AgentLoop(
        model = { ModelProviderManager.getModel(agentConfig.model)?: error("Agent $id: model '${agentConfig.model}' not found") },
        context = context,
        coroutineScope = NeoagentRuntime.coroutineScope
    )

    var currentChannel: Channel? = null

    fun handleMessage(message: IMMessage) {
        var mentioned = false

        val content = mutableListOf<String>()
        currentChannel = message.channel

        message.content.forEachComponent {
            when(it) {
                is IMMessageComponent.Mention -> {
                    if(message.channel.getSelfUser() == it.target) {
                        mentioned = true
                        content.add("@${it.target.displayName} (${it.target.id}, you)")
                    }else {
                        content.add("@${it.target.displayName} (${it.target.id})")
                    }
                }
                is IMMessageComponent.TextMessage -> {
                    content.add(it.text)
                }
                is IMMessageComponent.FileMessage -> {
                    //todo: A tool should be designed to download the file to the disk. Also more info such as size should be passed in as well.
                }
                //todo: images goes before send
            }
        }
        val date = Date(message.timestamp)
        val attrs = mutableMapOf(
            "time" to "${date.hours}:${date.minutes}:${date.seconds}",
            "sender" to message.sender.id,
            "id" to message.id, // todo: optimization: try to use a mapping of numbers instead of chaotic strings for token's sake
            "chat" to message.chat.id,
        )
        if(message.replyTo != null) attrs["replyTo"] = message.replyTo
        val msg = Message.TextMessage(
            content.joinToString("\n"),
            "message",
            attrs
        )

        if(mentioned || agentConfig.boundInterfaces[message.chat.getInterfaceID()]?.handleMode == AgentHandleMode.ALWAYS) {
            agentLoop.send(msg)
        }else{
            agentLoop.queueMessage(msg)
        }
    }

    fun deactivate(){
        agents.remove(id)
        agentLoop.worker.cancel()
        //todo: when shut down: tool call-respond pair might break
    }
}
