package cn.jason31416.config

import cn.jason31416.framework.ChannelConfig
import cn.jason31416.framework.Config
import cn.jason31416.framework.ConfigSection

// TODO: ALL DATA IN THIS FILE IS ONLY MOCK DATA.

object SystemConfig : Config("system", "config/system.yml") {
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

    object runtime : ConfigSection("runtime", "运行时", SystemConfig) {
        val dataDir = readonly(
            "dataDir", "数据目录",
            value = "~/Library/Application Support/NeoAgent/data",
            disabled = true,
        )
        val maxAgents = number(
            "maxAgents", "最大并发 Agent",
            default = 4,
            unit = "个",
            help = "超出后排队，不会丢弃请求",
        )
        val idleTimeout = number("idleTimeout", "空闲回收", default = 30, unit = "分钟")
        val gcInterval = number("gcInterval", "清理间隔", default = 10, unit = "分钟")
    }

    object network : ConfigSection("network", "网络", SystemConfig) {
        val listen = text(
            "listen", "监听地址",
            default = "0.0.0.0:8080",
            help = "修改后需要重启生效",
        )
        val proxy = text(
            "proxy", "出站代理",
            default = "",
            placeholder = "http://127.0.0.1:7890",
        )
        val timeout = number("timeout", "请求超时", default = 60, unit = "秒")
        val allowedOrigins = tags(
            "allowedOrigins", "跨域来源",
            default = listOf("http://localhost:5173"),
            help = "留空表示只允许同源",
            placeholder = "https://panel.example.com",
        )
    }

    object log : ConfigSection("log", "日志", SystemConfig) {
        val level = select(
            "level", "日志级别",
            options = listOf(
                "debug" to "debug",
                "info" to "info",
                "warn" to "warn",
                "error" to "error",
            ),
            default = "info",
        )
        val retentionDays = number("retentionDays", "保留天数", default = 14, unit = "天")
        val logBody = switch(
            "logBody", "记录请求体",
            default = false,
            help = "请求体可能包含密钥",
        )
    }

    object security : ConfigSection("security", "安全", SystemConfig) {
        val maxAttempts = number("maxAttempts", "登录失败锁定", default = 5, unit = "次")
        val allowRegister = switch(
            "allowRegister", "允许注册",
            default = false,
            help = "由环境变量 NEOAGENT_ALLOW_REGISTER 控制",
            disabled = true,
        )
        val trustProxy = switch(
            "trustProxy", "信任反向代理",
            default = true,
            help = "放在 Nginx 后面时打开，用于取真实 IP",
        )
    }

    override val sections = listOf(panel, runtime, network, log, security)
}

object TelegramConfig :
    ChannelConfig("telegram", "Telegram", "config/channels/telegram.yml", defaultEnabled = true) {

    object basic : ConfigSection("basic", "基础", TelegramConfig) {
        val botToken = secret(
            "botToken", "Bot Token",
            default = "7123456789:AAH6xkQ2mB0pLr4tZ8wYdEfGhIjKlMnOpQ",
            help = "从 @BotFather 获取，保存后只显示末四位",
            required = true,
        )
        val apiBase = text(
            "apiBase", "接口地址",
            default = "https://api.telegram.org",
            help = "自建反代时改这里",
        )
        val proxy = text(
            "proxy", "代理",
            default = "",
            placeholder = "http://127.0.0.1:7890",
        )
        val pollTimeout = number("pollTimeout", "轮询超时", default = 25, unit = "秒")
    }

    object message : ConfigSection("message", "消息", TelegramConfig) {
        val allowedChats = tags(
            "allowedChats", "允许的会话",
            default = listOf("@neoagent_group", "1029384756"),
            help = "留空表示接受全部会话",
            placeholder = "@your_channel",
        )
        val allowGroups = switch("allowGroups", "接受群组消息", default = true)
        val parseMode = select(
            "parseMode", "消息格式",
            options = listOf(
                "markdown" to "MarkdownV2",
                "html" to "HTML",
                "plain" to "纯文本",
            ),
            default = "markdown",
        )
        val welcomeText = textarea(
            "welcomeText", "欢迎语",
            default = "",
            placeholder = "留空则不发送",
        )
    }

    object advanced : ConfigSection("advanced", "高级", TelegramConfig) {
        val webhookUrl = readonly(
            "webhookUrl", "回调地址",
            value = "https://panel.example.com/api/channels/telegram/webhook",
            disabled = true,
        )
        val disablePreview = switch("disablePreview", "关闭链接预览", default = true)
        val rateLimit = number(
            "rateLimit", "速率上限",
            default = 60,
            unit = "条/分",
            help = "超过后排队等待，不会丢消息",
        )
    }

    override val sections = listOf(basic, message, advanced)
}

object DiscordConfig :
    ChannelConfig("discord", "Discord", "config/channels/discord.yml", defaultEnabled = true) {

    object basic : ConfigSection("basic", "基础", DiscordConfig) {
        val botToken = secret(
            "botToken", "Bot Token",
            default = "MTIzNDU2Nzg5:MOCK.DISCORD.TOKEN",
            help = "在开发者后台的 Bot 页面获取",
            required = true,
        )
        val proxy = text(
            "proxy", "代理",
            default = "",
            placeholder = "http://127.0.0.1:7890",
        )
    }

    object message : ConfigSection("message", "消息", DiscordConfig) {
        val allowedChannels = tags(
            "allowedChannels", "允许的频道",
            default = listOf("neoagent"),
        )
        val commandPrefix = text("commandPrefix", "命令前缀", default = "!")
    }

    object advanced : ConfigSection("advanced", "高级", DiscordConfig) {
        val shardId = number("shardId", "分片编号", default = 0, help = "只有大型机器人需要")
    }

    override val sections = listOf(basic, message, advanced)
}

object SlackConfig :
    ChannelConfig("slack", "Slack", "config/channels/slack.yml", defaultEnabled = false) {

    object basic : ConfigSection("basic", "基础", SlackConfig) {
        val botToken = secret(
            "botToken", "Bot Token",
            default = "",
            placeholder = "xoxb-...",
            required = true,
        )
        val signingSecret = secret(
            "signingSecret", "Signing Secret",
            default = "",
            required = true,
        )
    }

    object message : ConfigSection("message", "消息", SlackConfig) {
        val defaultChannel = text("defaultChannel", "默认频道", default = "#general")
    }

    override val sections = listOf(basic, message)
}

object LarkConfig :
    ChannelConfig("lark", "飞书", "config/channels/lark.yml", defaultEnabled = true) {

    object basic : ConfigSection("basic", "基础", LarkConfig) {
        val appId = text("appId", "App ID", default = "cli_mock_app_id", required = true)
        val appSecret = secret("appSecret", "App Secret", default = "", required = true)
    }

    object message : ConfigSection("message", "消息", LarkConfig) {
        val encryptKey = secret("encryptKey", "加密 Key", default = "")
        val allowedGroups = tags("allowedGroups", "允许的群", default = emptyList())
    }

    override val sections = listOf(basic, message)
}

object WebhookConfig :
    ChannelConfig("webhook", "自建 Webhook", "config/channels/webhook.yml", defaultEnabled = false) {

    object basic : ConfigSection("basic", "基础", WebhookConfig) {
        val endpoint = readonly(
            "endpoint", "接收路径",
            value = "/api/channels/webhook/incoming",
            disabled = true,
        )
    }

    object advanced : ConfigSection("advanced", "高级", WebhookConfig) {
        val secret = secret("secret", "签名密钥", default = "")
        val allowedIps = tags("allowedIps", "允许的来源 IP", default = emptyList())
    }

    override val sections = listOf(basic, advanced)
}

object AppConfigs {
    val system: SystemConfig = SystemConfig

    val channels: List<ChannelConfig> = listOf(
        TelegramConfig,
        DiscordConfig,
        SlackConfig,
        LarkConfig,
        WebhookConfig,
    )
}
