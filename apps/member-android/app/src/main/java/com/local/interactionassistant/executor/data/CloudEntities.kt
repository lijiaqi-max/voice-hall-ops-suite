package com.local.interactionassistant.executor.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cloud_config")
data class CloudConfigEntity(
    @PrimaryKey val id: Int = 1,
    val baseUrl: String = "",
    val username: String = "",
    val accessToken: String = "",
    val accountId: String = "",
    val displayName: String = "",
    val role: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "cloud_tasks",
    indices = [Index("state"), Index("customerId"), Index("updatedAtEpochMs")],
)
data class CloudTaskEntity(
    @PrimaryKey val id: String,
    val roomId: String? = null,
    val customerId: String,
    val customerName: String,
    val title: String,
    val brief: String,
    val state: String,
    val priority: Int,
    val assignedAccountId: String? = null,
    val resultChannel: String? = null,
    val resultNote: String? = null,
    val nextFollowUpAtEpochMs: Long? = null,
    val valueLevel: String = "standard",
    val visibleRevenueCents: Long? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "cloud_outbox",
    indices = [Index("createdAtEpochMs"), Index(value = ["dedupeKey"], unique = true)],
)
data class CloudOutboxEntity(
    @PrimaryKey val id: String,
    val method: String,
    val path: String,
    val bodyJson: String,
    val dedupeKey: String,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)
