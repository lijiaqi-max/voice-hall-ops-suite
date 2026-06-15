package com.local.interactionassistant.executor

import com.local.interactionassistant.executor.data.CandidateEntity
import com.local.interactionassistant.executor.data.BlockRuleEntity
import com.local.interactionassistant.executor.data.ContactEligibility
import com.local.interactionassistant.executor.data.ContactLimitStatus
import com.local.interactionassistant.executor.data.InteractionType
import com.local.interactionassistant.executor.data.MessageRisk
import com.local.interactionassistant.executor.data.PriorityBreakdown
import com.local.interactionassistant.executor.data.RelationshipStage
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object StandalonePolicy {
    private val solicitationPatterns = listOf(
        Regex("刷.{0,3}(礼物|票|榜)"),
        Regex("(送|补|上).{0,3}(礼物|票|榜)"),
        Regex("(消费|充值|打赏).{0,3}(一点|一下|支持)"),
    )
    private val pressurePatterns = listOf(
        Regex("不.{0,3}(送|来|回).{0,4}(伤心|失望|生气)"),
        Regex("(最后|马上|赶紧|必须).{0,5}(送|充值|打赏)"),
    )
    private val deceptiveIntimacyPatterns = listOf(
        Regex("(只爱你|只对你|唯一的).{0,4}(宝贝|老公|亲爱的)"),
        Regex("(私下见面|现实见面).{0,5}(先|只要).{0,5}(送|充值|打赏)"),
    )
    private val repeatedUrgingPatterns = listOf(
        Regex("(怎么还不|快点|再催一次).{0,6}(回|来|送)"),
    )

    fun normalizeAssetSelection(assetIds: List<String>): List<String> {
        val normalized = assetIds.map(String::trim).filter(String::isNotEmpty).distinct()
        require(normalized.size <= 3) { "每条任务最多选择 3 张图片" }
        return normalized
    }

    fun chooseMessage(
        lines: List<String>,
        randomize: Boolean,
        random: Random = Random.Default,
    ): String {
        val available = lines.map(String::trim).filter(String::isNotBlank)
        require(available.isNotEmpty()) { "话术模板至少需要一条非空内容" }
        return if (randomize) available[random.nextInt(available.size)] else available.first()
    }

    fun chooseMessage(
        templateLines: String,
        randomize: Boolean,
        random: Random = Random.Default,
    ): String = chooseMessage(templateLines.lineSequence().toList(), randomize, random)

    fun randomDelaySeconds(min: Int, max: Int, random: Random = Random.Default): Int {
        require(min in 0..3 && max in min..3) { "发送延迟必须在 0–3 秒之间" }
        return if (min == max) min else random.nextInt(min, max + 1)
    }

    fun approvalExpired(expiresAtEpochMs: Long?, nowEpochMs: Long): Boolean =
        expiresAtEpochMs == null || expiresAtEpochMs <= nowEpochMs

    fun priorityBreakdown(
        nowEpochMs: Long,
        lastInteractionAtEpochMs: Long?,
        interactionCountInThirtyDays: Int,
        followed: Boolean,
        giftEventCount: Int,
    ): PriorityBreakdown {
        val ageDays = lastInteractionAtEpochMs?.let {
            TimeUnit.MILLISECONDS.toDays((nowEpochMs - it).coerceAtLeast(0))
        }
        val recency = when {
            ageDays == null -> 0
            ageDays <= 1 -> 40
            ageDays <= 3 -> 30
            ageDays <= 7 -> 20
            ageDays <= 14 -> 10
            else -> 0
        }
        val frequency = (interactionCountInThirtyDays.coerceAtLeast(0) * 3).coerceAtMost(30)
        val followScore = if (followed) 10 else 0
        val gifts = (giftEventCount.coerceAtLeast(0) * 5).coerceAtMost(20)
        return PriorityBreakdown(
            total = (recency + frequency + followScore + gifts).coerceIn(0, 100),
            recency = recency,
            frequency = frequency,
            followed = followScore,
            gifts = gifts,
        )
    }

    fun contactEligibilityFor(
        interactionType: String,
        doNotContact: Boolean = false,
    ): String = when {
        doNotContact -> ContactEligibility.DO_NOT_CONTACT.value
        interactionType == InteractionType.MANUAL_CONFIRMED.value ->
            ContactEligibility.MANUAL_CONFIRMED.value
        interactionType in InteractionType.entries
            .filterNot { it == InteractionType.MANUAL_CONFIRMED }
            .map(InteractionType::value) -> ContactEligibility.INTERACTION.value
        else -> ContactEligibility.INELIGIBLE.value
    }

    fun stageForInteraction(type: String): String = when (type) {
        InteractionType.FOLLOW.value -> RelationshipStage.FOLLOWED.value
        InteractionType.GIFT.value -> RelationshipStage.GIFTED.value
        InteractionType.RETURN_VISIT.value -> RelationshipStage.ACTIVE.value
        InteractionType.COMMENT.value,
        InteractionType.MANUAL_CONFIRMED.value,
        -> RelationshipStage.NEW_INTERACTION.value
        else -> RelationshipStage.NEW_INTERACTION.value
    }

    fun requireRelationshipStageChange(targetStage: String, priorityScore: Int) {
        val target = RelationshipStage.entries.firstOrNull { it.value == targetStage }
            ?: error("关系阶段无效")
        require(priorityScore in 0..100) { "互动综合分必须在 0–100" }
        if (target == RelationshipStage.PRIORITY) {
            require(priorityScore >= 70) { "互动综合分达到 70 后才能标记重点维护" }
        }
    }

    fun contactLimitStatus(
        userCountInSevenDays: Int,
        globalCountToday: Int,
        perUserLimit: Int = 2,
        dailyLimit: Int = 20,
    ): ContactLimitStatus = when {
        userCountInSevenDays >= perUserLimit -> ContactLimitStatus(
            false,
            "同一用户 7 天内最多联系 $perUserLimit 次",
            userCountInSevenDays,
            globalCountToday,
        )
        globalCountToday >= dailyLimit -> ContactLimitStatus(
            false,
            "今日最多联系 $dailyLimit 位",
            userCountInSevenDays,
            globalCountToday,
        )
        else -> ContactLimitStatus(
            true,
            "允许进入逐条双确认",
            userCountInSevenDays,
            globalCountToday,
        )
    }

    fun messageRisks(message: String): List<MessageRisk> {
        val text = message.trim()
        if (text.isEmpty()) return emptyList()
        return buildList {
            if (solicitationPatterns.any { it.containsMatchIn(text) }) {
                add(MessageRisk("solicitation", "疑似索礼或诱导消费"))
            }
            if (pressurePatterns.any { it.containsMatchIn(text) }) {
                add(MessageRisk("pressure", "疑似消费施压或情绪绑架"))
            }
            if (deceptiveIntimacyPatterns.any { it.containsMatchIn(text) }) {
                add(MessageRisk("deceptive_intimacy", "疑似虚假亲密承诺"))
            }
            if (repeatedUrgingPatterns.any { it.containsMatchIn(text) }) {
                add(MessageRisk("repeated_urging", "疑似频繁催促"))
            }
        }
    }

    fun candidateMatches(
        candidate: CandidateEntity,
        gender: String,
        minConsumption: Long?,
        maxConsumption: Long?,
        source: String,
        unknownFieldPolicy: String = "retain",
    ): Boolean {
        require(unknownFieldPolicy in setOf("retain", "exclude")) { "未知字段策略无效" }
        if (gender != "all") {
            if (candidate.gender == null && unknownFieldPolicy == "exclude") return false
            if (candidate.gender != null && candidate.gender != gender) return false
        }
        if (source != "all" && candidate.source != source) return false
        if ((minConsumption != null || maxConsumption != null) &&
            candidate.consumption == null &&
            unknownFieldPolicy == "exclude"
        ) {
            return false
        }
        candidate.consumption?.let { value ->
            if (minConsumption != null && value < minConsumption) return false
            if (maxConsumption != null && value > maxConsumption) return false
        }
        return true
    }

    fun normalizeBlockValue(type: String, value: String): String {
        require(type in setOf("external_id", "nickname_keyword")) { "屏蔽规则类型无效" }
        val normalized = value.trim().lowercase()
        require(normalized.isNotEmpty()) { "屏蔽内容不能为空" }
        return normalized
    }

    fun matchingBlockRule(
        externalUserId: String,
        displayName: String,
        rules: List<BlockRuleEntity>,
    ): BlockRuleEntity? {
        val normalizedId = externalUserId.trim().lowercase()
        val normalizedName = displayName.trim().lowercase()
        return rules.firstOrNull { rule ->
            if (!rule.enabled) {
                false
            } else {
                when (rule.type) {
                    "external_id" -> normalizedId == rule.normalizedValue
                    "nickname_keyword" -> normalizedName.contains(rule.normalizedValue)
                    else -> false
                }
            }
        }
    }

    fun requireSessionTransition(from: String, to: String) {
        val allowed = when (from) {
            "draft" -> setOf("running", "cancelled")
            "running" -> setOf("paused", "completed", "cancelled")
            "paused" -> setOf("running", "cancelled")
            "completed", "cancelled" -> emptySet()
            else -> error("未知批次状态: $from")
        }
        require(to in allowed) { "批次不能从 $from 切换到 $to" }
    }

    fun failureCode(reason: String): String = when {
        reason.contains("过期") -> "approval_expired"
        reason.contains("图片") && reason.contains("缺") -> "asset_missing"
        reason.contains("版本") -> "version_mismatch"
        reason.contains("页面") -> "unknown_page"
        reason.contains("收件人") || reason.contains("用户") -> "recipient_mismatch"
        reason.contains("话术") || reason.contains("文字") -> "message_mismatch"
        reason.contains("取消") -> "user_cancelled"
        else -> "execution_failed"
    }
}
