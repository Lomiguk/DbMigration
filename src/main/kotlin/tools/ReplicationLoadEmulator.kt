package tools

import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random

/**
 * Эмулятор транзакционной нагрузки для тестирования CDC (Logical Replication)
 */
class ReplicationLoadEmulator(
    private val sourceDataSource: DataSource
) {
    // Храним сгенерированные ключи для UPDATE и DELETE
    private val activeUsers = mutableListOf<UUID>()
    private val activeProfiles = mutableListOf<UUID>()

    fun runMixedLoad(targetOperations: Int, batchSize: Int = 100) {
        println("Starting load emulator: $targetOperations operations...")

        var operationsDone = 0
        while (operationsDone < targetOperations) {
            val opType = Random.nextInt(100)

            when {
                // 70% нагрузки - вставки (создание пользователей и их профилей)
                opType < 70 -> {
                    generateInserts(batchSize)
                    operationsDone += batchSize
                }
                // 20% нагрузки - обновления (смена email)
                opType < 90 -> {
                    if (activeUsers.isNotEmpty()) {
                        generateUpdates(Math.min(batchSize, activeUsers.size))
                        operationsDone += batchSize
                    }
                }
                // 10% нагрузки - удаления (удаление профилей)
                else -> {
                    if (activeProfiles.isNotEmpty()) {
                        generateDeletes(Math.min(batchSize / 10, activeProfiles.size))
                        operationsDone += (batchSize / 10)
                    }
                }
            }
        }
        println("Load generation completed.")
    }

    private fun generateInserts(count: Int) {
        sourceDataSource.connection.use { conn ->
            conn.autoCommit = false

            val userStmt = conn.prepareStatement("INSERT INTO users (id, email) VALUES (?, ?)")
            val profileStmt = conn.prepareStatement("INSERT INTO profiles (id, user_id, bio) VALUES (?, ?, ?)")

            for (i in 0 until count) {
                val userId = UUID.randomUUID()
                val profileId = UUID.randomUUID()

                activeUsers.add(userId)
                activeProfiles.add(profileId)

                userStmt.setObject(1, userId)
                userStmt.setString(2, "load_${UUID.randomUUID().toString().take(8)}@test.com")
                userStmt.addBatch()

                profileStmt.setObject(1, profileId)
                profileStmt.setObject(2, userId)
                profileStmt.setString(3, "Load testing bio info")
                profileStmt.addBatch()
            }

            userStmt.executeBatch()
            profileStmt.executeBatch()
            conn.commit()
        }
    }

    private fun generateUpdates(count: Int) {
        sourceDataSource.connection.use { conn ->
            conn.autoCommit = false
            val updateStmt = conn.prepareStatement("UPDATE users SET email = ? WHERE id = ?")

            for (i in 0 until count) {
                val userId = activeUsers.random()
                val uniqueHash = UUID.randomUUID().toString().take(8)
                updateStmt.setString(1, "updated_$uniqueHash@test.com")
                updateStmt.setObject(2, userId)
                updateStmt.addBatch()
            }

            updateStmt.executeBatch()
            conn.commit()
        }
    }

    private fun generateDeletes(count: Int) {
        sourceDataSource.connection.use { conn ->
            conn.autoCommit = false
            val deleteStmt = conn.prepareStatement("DELETE FROM profiles WHERE id = ?")

            for (i in 0 until count) {
                if (activeProfiles.isEmpty()) break
                val profileId = activeProfiles.random()
                activeProfiles.remove(profileId)

                deleteStmt.setObject(1, profileId)
                deleteStmt.addBatch()
            }

            deleteStmt.executeBatch()
            conn.commit()
        }
    }
}