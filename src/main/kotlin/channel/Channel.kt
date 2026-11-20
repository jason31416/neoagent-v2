package cn.jason31416.channel

import cn.jason31416.agent.Agent
import cn.jason31416.agent.CommandHandler
import cn.jason31416.agent.hasPermission
import cn.jason31416.database.db
import cn.jason31416.framework.ChannelConfig
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File


object IMMessagesTable : Table("im_messages") {
    val id = text("id")
    val user = text("user")
    val chat = text("chat")
    val timestamp = long("timestamp")
    val message = text("message") // original content if TEXT, else it will only be a placeholder text such as `[image]` or `[file:test.txt]`.

    override val primaryKey = PrimaryKey(chat, id)
}

class IMUser(val channel: Channel, val id: String, val displayName: String?=null) {
    override fun equals(other: Any?): Boolean {
        if(other is IMUser) {
            return channel.id == other.channel.id && id == other.id
        }
        return false
    }

    override fun hashCode(): Int {
        var result = channel.hashCode()
        result = 31 * result + id.hashCode()
        return result
    }

    fun getKey(): String {
        return channel.id+":"+id
    }
}


/**
 * A single section of a message that is the same type
 */
interface IMMessageComponent {
    // example: @jason31416
    data class Mention(val target: IMUser) : IMMessageComponent
    data class TextMessage(val text: String): IMMessageComponent
//    data class ImageMessage(val imageFile: File): IMMessageComponent
    data class FileMessage(val fileName: String, val fileFetcher: suspend ()->File?): IMMessageComponent

    //todo: forward message (basically a list of IMMessageComponent but all with sender)
}

/**
 * The content of a message. Only created when a message is going to be sent or when a message is received.
 */
class IMMessageContent(val content: List<IMMessageComponent> = listOf<IMMessageComponent>()){
    fun text(x: String) = IMMessageContent(content+IMMessageComponent.TextMessage(x))
    // todo: image
    fun file(file: File) = IMMessageContent(content+IMMessageComponent.FileMessage(file.name) {file})

    // only for log display
    fun asString(): String {
        return content.map {
            when (it) {
                is IMMessageComponent.Mention -> "@${it.target.id}"
                is IMMessageComponent.TextMessage -> it.text
                is IMMessageComponent.FileMessage -> "[file:${it.fileName}]"
                else -> "[unknown]"
            }
        }.joinToString("\n")
    }

    fun forEachComponent(f: (IMMessageComponent) -> Unit) {
        for (item in content) {
            f(item)
        }
    }
}


/**
 * A single sent message in a chat. Only created by the channel.
 * When agent needs to fetch a message, fetch stringified content from database.
 */
class IMMessage(val id: String, val chat: IMChat, val sender: IMUser, val timestamp: Long, val content: IMMessageContent, val replyTo: String?=null){
    val channel: Channel
        get() = chat.channel
}

class NotSupportedException(msg: String): Exception(msg)

interface AgentInterface {
    fun getInterfaceID(): String
    // Functions that the agent can actively call, for both IMChat and Channel
    fun executeFunction(operation: String, parameters: Map<String, String>): String {
        throw NotSupportedException("Operation not supported: $operation")
    }
}

/**
 * A single chat in the im service
 * Can be a private chat or a group chat
 *
 * This should be implemented by the channels to provide the functionalities.
 */
interface IMChat: AgentInterface {
    val channel: Channel
    val id: String
    // Some group chats can be set to require @ for the agent to be summoned
    val isGroup: Boolean

    fun getKey(): String {
        return channel.id+":"+id
    }

    override fun getInterfaceID(): String = "chat-"+getKey()

    // Must implement functions
    suspend fun _sendMessage(message: IMMessageContent, replyTo: String?=null): IMMessage

    // helper
    fun sendMessage(message: IMMessageContent, replyTo: String?=null): IMMessage {
        val ret = runBlocking { _sendMessage(message, replyTo) }
        transaction(db) {
            IMMessagesTable.insert {
                it[IMMessagesTable.id] = ret.id
                it[IMMessagesTable.user] = channel.getSelfUser().getKey()
                it[IMMessagesTable.chat] = getKey()
                it[IMMessagesTable.timestamp] = timestamp
                it[IMMessagesTable.message] = message.asString()
            }
        }
        return ret
    }
}




data class IMEvent(val chat: IMChat?, val type: String, val content: String)

/**
 * The channel, which is an instance representing a platform connected to the Neoagent.
 * Actual handling is done by [ChannelHandler], while this class essentially acts as a bridge between the agent side and the channel side.
 */
class Channel(val id: String,
              val config: ChannelConfig,
              private var channelHandler: ChannelHandler): AgentInterface {
    init {
        channelHandler.setChannel(this)
    }

    override fun getInterfaceID(): String = "channel-$id"

    companion object {
        val channels = listOf<Channel>()

        fun constructChannelFromConfig(config: ChannelConfig) = Channel(
            config.id,
            config,
            ChannelHandler.createChannelHandler(config.type!!)
        )
    }

    fun getSelfUser(): IMUser = channelHandler.getSelfUser()
    fun getUserFromId(id: String) = channelHandler.getUserFromId(id)
    fun getChatFromId(id: String): IMChat? = channelHandler.getChatFromId(id)

    fun receiveMessage(message: IMMessage){
        if(message.sender.hasPermission("blacklisted")) return

        transaction(db) {
            IMMessagesTable.insert {
                it[IMMessagesTable.id] = message.id
                it[IMMessagesTable.user] = message.sender.getKey()
                it[IMMessagesTable.chat] = message.chat.getKey()
                it[IMMessagesTable.timestamp] = message.timestamp
                it[IMMessagesTable.message] = message.content.asString()
            }
        }

        if(message.content.content.size == 1) {
            val component = message.content.content[0]
            if(component is IMMessageComponent.TextMessage && component.text.startsWith("/")) {
                CommandHandler.executeCommand(component.text.drop(1), message.chat, message.sender)
                    ?.let { message.chat.sendMessage(IMMessageContent().text(it), replyTo = message.id) }
                return
            }
        }

        Agent.getListeningAgents(message.chat).forEach { agent -> agent.handleMessage(message)}
    }

    fun receiveEvent(event: IMEvent){
        // Event可以是account-level（即无绑定imchat）或者是chat-level（即存在绑定imchat），分别分配给不同agent处理
        // todo: agent不仅能绑定到imchat上，也可以绑定到channel上（也就是操控账号层级的内容，例如加好友等）
    }
}

/**
 * The handler for messages, implemented by providers.
 *
 * Basic functionalities include sending/receiving messages from different users and group chats and identification of users
 * When applicable: fetching potential information about the IM account, fetching information about group chats, etc.
 * Examples containing the wechat/qq/feishu integrations and the webui channel.
 *
 * Responsible for calling [Channel.receiveMessage] when received a message.
 * Responsible for calling [Channel.receiveEvent] when received a non-message event (such as someone trying to add the bot, or someone just joined a group chat that the bot is in).
 *
 * The web ui is a special instance of this that only provide certain functionalities
 */
interface ChannelHandler {
    companion object {
        private val channelHandlers = mutableMapOf<String, ()->ChannelHandler>()
        fun registerChannelHandler(id: String, channelHandler: ()->ChannelHandler) {
            channelHandlers[id] = channelHandler
        }
        fun createChannelHandler(id: String): ChannelHandler {
            return channelHandlers[id]!!()
        }
    }
    fun setChannel(channel: Channel)

    fun getChatFromId(id: String): IMChat?
    fun getUserFromId(id: String): IMUser?
    fun getSelfUser(): IMUser
}