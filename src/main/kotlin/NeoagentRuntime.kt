package cn.jason31416

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object NeoagentRuntime {
    val launchTime = System.currentTimeMillis()
    val version = object {}.javaClass.`package`.implementationVersion?:"unknown"

    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}