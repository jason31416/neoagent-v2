package cn.jason31416.database

import cn.jason31416.agent.AgentsTable
import cn.jason31416.agent.ContextTable
import cn.jason31416.agent.IMMessagesTable
import cn.jason31416.agent.PermissionTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

val db: Database = Database.connect("jdbc:sqlite:neoagent.db", driver = "org.sqlite.JDBC")

object MetaTable: Table("metadata") {
    val key = text("key")
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}

object DatabaseHandler {
    fun init() {
        transaction(db) {
            SchemaUtils.createMissingTablesAndColumns(PermissionTable)
            SchemaUtils.createMissingTablesAndColumns(ContextTable)
            SchemaUtils.createMissingTablesAndColumns(AgentsTable)
            SchemaUtils.createMissingTablesAndColumns(IMMessagesTable)
            SchemaUtils.createMissingTablesAndColumns(MetaTable)
        }
    }
}
