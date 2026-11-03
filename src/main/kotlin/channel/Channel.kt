package cn.jason31416.channel

import cn.jason31416.agent.IMMessagesTable
import cn.jason31416.database.db
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

data class IMUser(val channel: Channel, val id: String, val displayName: String?=null)

fun IMUser.getKey(): String {
    return channel.id+":"+id
}

/**
 * The content of the message in a chat.
 */
sealed interface IMMessageComponent {
    data class TextMessage(val content: String): IMMessageComponent
    data class ImageMessage(val imageFile: File): IMMessageComponent
    data class FileMessage(val fileName: String, val fileFetcher: suspend ()->File?): IMMessageComponent

    //todo: forward message (basically a list of IMMessageComponent but all with sender)
}

// only for display.
fun IMMessageComponent.asString() = when (this) {
    is IMMessageComponent.TextMessage -> content
    is IMMessageComponent.FileMessage -> "[file:${fileName}]"
    is IMMessageComponent.ImageMessage -> "[image]"
}

/**
 * A single chat bubble in a chat
 */
data class IMMessage(val id: String, val chat: IMChat, val sender: IMUser, val timestamp: Long, val component: IMMessageComponent, val replyTo: String?=null)

/**
 * A single chat in the im service
 * Can be a private chat or a group chat
 *
 * This should be implemented by the channels to provide the functionalities.
 */
interface IMChat {
    val channel: Channel
    val id: String
    val isGroup: Boolean

    // Must implement functions
    fun _sendMessage(message: IMMessageComponent, replyTo: String?=null): IMMessage

    // helper
    fun sendMessage(message: IMMessageComponent, replyTo: String?=null): IMMessage {
        val ret = _sendMessage(message, replyTo)
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

    // Optional functions
    //todo
}



fun IMChat.getKey(): String {
    return channel.id+":"+id
}

/**
 * One interaction interface of the core agent.
 * Basic functionalities include sending/receiving messages from different users and group chats and identification of users
 * When applicable: fetching potential information about the IM account, fetching information about group chats, etc.
 * Examples containing the wechat/qq/feishu integrations and the webui channel.
 *
 * The channel is responsible of calling [cn.jason31416.agent.MessageHandler.receiveMessage] when it received any chat message that are NOT sent by the bot itself.
 *
 * The web ui is a special instance of this that only provide certain functionalities
 */
interface Channel {
    val id: String
    fun getAvailableChats(): List<IMChat>
    fun getSelfUser(): IMUser


}
