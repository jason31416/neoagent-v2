package cn.jason31416.agent

import cn.jason31416.channel.IMMessage
import cn.jason31416.channel.IMMessageComponent
import cn.jason31416.channel.asString
import cn.jason31416.channel.getKey
import cn.jason31416.database.db
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction


object IMMessagesTable : Table("im_messages") {
    val id = text("id")
    val user = text("user")
    val chat = text("chat")
    val timestamp = long("timestamp")
    val message = text("message") // original content if TEXT, else it will only be a placeholder text such as `[image]` or `[file:test.txt]`.

    override val primaryKey = PrimaryKey(chat, id)
}

/**
 * The central handler for all incoming messages.
 *
 * Takes from channels and feed them to the corresponding agents.
 */
object MessageHandler {
    fun receiveMessage(message: IMMessage) {
        if(message.sender.hasPermission("blacklisted")) return

        transaction(db) {
            IMMessagesTable.insert {
                it[IMMessagesTable.id] = message.id
                it[IMMessagesTable.user] = message.sender.getKey()
                it[IMMessagesTable.chat] = message.chat.getKey()
                it[IMMessagesTable.timestamp] = message.timestamp
                it[IMMessagesTable.message] = message.component.asString()
            }
        }

        if(message.component is IMMessageComponent.TextMessage && message.component.content.startsWith("/")) {
            CommandHandler.executeCommand(message.component.content.drop(1), message.chat, message.sender)
                ?.let { message.chat.sendMessage(IMMessageComponent.TextMessage(it), replyTo = message.id) }
        }

        //todo
    }
}