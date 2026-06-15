package com.local.voicehall.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

object DatabaseFactory {
    fun create(env: Map<String, String> = System.getenv()): HikariDataSource {
        val jdbcUrl = env["DATABASE_URL"]
            ?: "jdbc:h2:file:./data/voicehall;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username = env["DATABASE_USER"] ?: if (jdbcUrl.startsWith("jdbc:h2:")) "sa" else "voicehall"
            password = env["DATABASE_PASSWORD"] ?: if (jdbcUrl.startsWith("jdbc:h2:")) "" else "change-me"
            driverClassName = if (jdbcUrl.startsWith("jdbc:h2:")) "org.h2.Driver" else "org.postgresql.Driver"
            maximumPoolSize = (env["DATABASE_POOL_SIZE"] ?: "10").toInt()
            minimumIdle = 1
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        return HikariDataSource(config).also { dataSource ->
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }
}

inline fun <T> DataSource.connection(block: (java.sql.Connection) -> T): T =
    connection.use(block)

inline fun <T> DataSource.transaction(block: (java.sql.Connection) -> T): T =
    connection { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

