package cn.jason31416

import org.yaml.snakeyaml.Yaml
import java.io.File

// todo: reconstruction
class Config(val map: Map<String, Any>) {
    companion object {
        private var configTree: Config = Config(mapOf())
        public fun init(){
            val configFile = File("config.yml")
            if(!configFile.exists()){
                @Suppress("JAVA_CLASS_ON_COMPANION")
                javaClass.classLoader.getResourceAsStream("config.yml")?.bufferedReader()?.use {
                    configFile.printWriter().use { out -> out.println(it.readText()) }
                }
            }
            configTree = Config(Yaml().load(configFile.inputStream()))
        }

        public fun <T> get(key: String): T?{
            try {
                return configTree.get(key) as T
            }catch (e: TypeCastException){
                return null
            }
        }

        public fun <T> get(key: String, default: T): T{
            return get(key) ?: default
        }
    }

    public fun get(key: String): Any? {
        if("." !in key){
            return map.getOrDefault(key, null)
        }
        var curMap = map;
        val keyList = key.split(".").subList(0, key.split(".").size - 1)
        for(key in keyList){
            if(curMap.containsKey(key) && curMap[key] is Map<*, *>){
                curMap = curMap.get(key) as Map<String, Any>
            }else {
                return null;
            }
        }
        return curMap.get(key.split(".").get(key.split(".").size - 1))
    }
}