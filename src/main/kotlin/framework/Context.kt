package cn.jason31416.framework

/**
 * Context is made out of two parts:
 * - One that is always at head
 * - One rolling history
 */
interface Context {
    fun getActiveContext(): List<ContextEntry>
    fun getLastContext(): ContextEntry?
    fun addEntry(entry: ContextEntry)
    fun advanceHead()
}

class MemoryContext(
    var systemMessage: String?=null
): Context {
    val messageContext: MutableList<ContextEntry> = mutableListOf()

    override fun getActiveContext(): List<ContextEntry> {
        if(systemMessage.isNullOrBlank()) return messageContext
        return mutableListOf(ContextEntry(Role.SYSTEM, mutableListOf(ContentBlock(ContentType.TEXT, systemMessage!!))))+messageContext
    }

    override fun getLastContext(): ContextEntry? {
        return messageContext.lastOrNull()
    }

    override fun addEntry(entry: ContextEntry) {
        messageContext.add(entry)
    }

    override fun advanceHead() {
        messageContext.clear()
    }
}
