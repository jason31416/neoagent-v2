package cn.jason31416

import cn.jason31416.database.DatabaseHandler
import cn.jason31416.panel.startWebServer
import cn.jason31416.providers.ModelProviderManager

fun main(){
    println("http://localhost:8080/register?token=${NeoagentRuntime.secret}")
    DatabaseHandler.init()
    ModelProviderManager.reload()
    startWebServer()
}
