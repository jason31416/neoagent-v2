package cn.jason31416.agent

import cn.jason31416.channel.IMChat
import cn.jason31416.channel.IMUser

class Command(val permission: String?=null, val executor: (user: IMUser, chat: IMChat, args: List<String>) -> String?)

object CommandHandler {
    val UNKNOWN_COMMAND_ERROR = "Unknown command %cmd%! Use /help for available commands."
    val registeredCommands = hashMapOf<String, Command>()

    /**
     * Takes in the command input
     * (Command is stripped with the leading `/`)
     *
     * Returns an optional String to be sent to the user that executed the command.
     */
    fun executeCommand(command: String, chat: IMChat, user: IMUser): String? {
        val parts = command.split(" ")
        if(registeredCommands.containsKey(parts[0]) && user.hasPermission(registeredCommands[parts[0]]!!.permission) ) {
            return registeredCommands[parts[0]]!!.executor.invoke(user, chat, parts.drop(1))
        }
        return UNKNOWN_COMMAND_ERROR.replace("%cmd%", "/"+command)
    }

    fun registerCommand(id: String, executor: Command) {
        registeredCommands[id] = executor
    }

//    fun example(){
//        registerCommand("qwq", Command("command.qwq") { user, chat, args ->
//            val hi: String = "hi"
//            if(user.id=="x.x"){
//                return@Command hi
//            }
//            return@Command "qwq"
//        })
//    }
}