package suwayomi.tachidesk.fork.database

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction

interface ForkMigration {
    val version: Int
    val name: String

    fun JdbcTransaction.apply()
}
