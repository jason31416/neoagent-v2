package cn.jason31416

import cn.jason31416.agent.Agent
import cn.jason31416.database.DatabaseHandler
import cn.jason31416.panel.startWebServer
import cn.jason31416.providers.ModelProviderManager

fun main(){
    DatabaseHandler.init()
    ModelProviderManager.reload()
    Agent.loadAgents()
    println("http://localhost:8080/register?token=${NeoagentRuntime.secret}")
    startWebServer()
}
