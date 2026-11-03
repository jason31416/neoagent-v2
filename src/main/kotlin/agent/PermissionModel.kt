package cn.jason31416.agent

import cn.jason31416.channel.IMUser
import cn.jason31416.channel.getKey
import cn.jason31416.database.db
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

object PermissionTable : Table("permissions") {
    val user = text("user")
    val permission = text("permission")

    override val primaryKey = PrimaryKey(user, permission)
}

fun IMUser.grantPermission(permission: String) = transaction (db) {
    PermissionTable.upsert {
        it[PermissionTable.user] = getKey()
        it[PermissionTable.permission] = permission
    }
}

fun IMUser.revokePermission(permission: String) = transaction (db) {
    PermissionTable.deleteWhere { (PermissionTable.permission eq permission) and (PermissionTable.user eq getKey()) }
}

fun IMUser.hasPermission(permission: String?): Boolean = transaction (db) {
    if(permission==null) true
    else PermissionTable.selectAll()
        .where {(PermissionTable.permission inList PermissionModel.resolveAncestors(permission)) and (PermissionTable.user eq getKey())}
        .firstOrNull()!=null
}

object PermissionModel {
    // child -> parent groups
    val parentPermissions = hashMapOf<String, MutableList<String>>()

    // registerGroup("group.admin", "bot.kick", "bot.ban", "bot.mute")
    fun registerGroup(group: String, vararg permissions: String) {
        permissions.forEach { perm ->
            parentPermissions.getOrPut(perm) { mutableListOf() }.add(group)
        }
    }

    fun resolveAncestors(permission: String): Set<String> {
        val result = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(permission)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (result.add(current)) {
                parentPermissions[current]?.forEach { queue.add(it) }
            }
        }
        return result
    }

    init {
        registerGroup("admin", )
    }
}
