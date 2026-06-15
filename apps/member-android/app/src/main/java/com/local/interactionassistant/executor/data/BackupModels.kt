package com.local.interactionassistant.executor.data

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val createdAtEpochMs: Long,
    val dataSha256: String,
    val assetHashes: List<String>,
    val plaintextWarning: String,
)

@Serializable
data class BackupSnapshot(
    val candidates: List<CandidateEntity>,
    val tasks: List<TaskEntity>,
    val taskAssets: List<TaskAssetEntity>,
    val templates: List<MessageTemplateEntity>,
    val templateLines: List<TemplateLineEntity>,
    val assets: List<MediaAssetEntity>,
    val blacklist: List<BlacklistEntity>,
    val history: List<SendHistoryEntity>,
    val logs: List<RunLogEntity>,
    val settings: AppSettingsEntity,
    val profiles: List<AutomationProfileEntity>,
    val profileAssets: List<ProfileAssetEntity>,
    val blockRules: List<BlockRuleEntity>,
    val runSessions: List<RunSessionEntity>,
    val interactionEvents: List<InteractionEventEntity> = emptyList(),
    val relationshipStageHistory: List<RelationshipStageHistoryEntity> = emptyList(),
)
