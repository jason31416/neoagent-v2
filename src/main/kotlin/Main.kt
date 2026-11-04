package cn.jason31416

import cn.jason31416.database.DatabaseHandler
import cn.jason31416.panel.startWebServer

fun main(){
    println("http://localhost:8080/register?token=${NeoagentRuntime.secret}")
    DatabaseHandler.init()
    startWebServer()
}
