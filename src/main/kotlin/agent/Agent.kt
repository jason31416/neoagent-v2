package cn.jason31416.agent

import cn.jason31416.agent.CommandHandler.UNKNOWN_COMMAND_ERROR
import cn.jason31416.channel.*
import cn.jason31416.database.db
import cn.jason31416.framework.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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

class AgentContext(val agentId: String): Context {
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

/**
 * A single persistent agent's instance.
 *
 * binds to several [IMChat] instances, holds an [AgentLoop] instance, and holds a list of available tools.
 * Multiple Agents could be active at the same time with different tools running in different chats.
 */
class Agent(
    val id: String
){
    companion object {
        val agents = hashMapOf<String, Agent>()
    }

    val context = AgentContext(id)
    val agentLoop = AgentLoop(
        model = TODO(),
        context = context,
        coroutineScope = TODO()
    )

    fun handleMessage(message: IMMessage) {
        //todo: Handle file, text, image messages

    }
}
