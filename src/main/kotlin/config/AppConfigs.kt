package cn.jason31416.config

import cn.jason31416.framework.ChannelConfig
import cn.jason31416.framework.Config
import cn.jason31416.framework.ConfigSection

object SystemConfig : Config("config/system.yml") {
    object panel : ConfigSection("panel", "面板", SystemConfig) {
        val panelTitle = text("panelTitle", "面板标题", default = "NeoAgent WebUI")
        val language = select(
            "language", "语言",
            options = listOf(
                "zh-CN" to "简体中文",
                "en" to "English",
                "auto" to "跟随系统",
            ),
            default = "zh-CN",
        )
        val openOnStart = switch(
            "openOnStart", "启动时打开浏览器",
            default = true,
            help = "只在桌面环境下生效",
        )
        val sessionTimeout = number("sessionTimeout", "会话有效期", default = 7, unit = "天")
    }


    override val sections = listOf(panel)
}

object AppConfigs {
    val system: SystemConfig = SystemConfig

    // todo: remove
    val channels: List<ChannelConfig> = listOf(
    )
}
