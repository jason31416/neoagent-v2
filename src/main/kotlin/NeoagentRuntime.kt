package cn.jason31416

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.security.SecureRandom
import java.util.UUID

object NeoagentRuntime {
    val launchTime = System.currentTimeMillis()
    val version = object {}.javaClass.`package`.implementationVersion?:"unknown"

    // Log this to console. Only active until a successful register/login occured
    var secret: String? = generateSecret()

    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun generateSecret(): String{
        val ret = StringBuilder()
        val chars = "QWERTYUIOPASDFGHJKLZXCVBNMqwertyuiopasdfghjklzxcvbnm1234567890"
        for(i in 0..16){
            ret.append(chars[SecureRandom().nextInt(chars.length)])
        }
        return ret.toString()
    }
}
