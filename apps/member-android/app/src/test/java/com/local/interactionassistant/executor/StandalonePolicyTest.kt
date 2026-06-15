package com.local.interactionassistant.executor

import kotlin.random.Random
import com.local.interactionassistant.executor.data.CandidateEntity
import com.local.interactionassistant.executor.data.BlockRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StandalonePolicyTest {
    @Test
    fun fixedTemplateUsesFirstNonBlankLine() {
        assertEquals(
            "第一条",
            StandalonePolicy.chooseMessage("\n第一条\n第二条", randomize = false),
        )
    }

    @Test
    fun randomTemplateUsesAvailableLines() {
        val result = StandalonePolicy.chooseMessage(
            "第一条\n第二条",
            randomize = true,
            random = Random(7),
        )
        check(result == "第一条" || result == "第二条")
    }

    @Test
    fun delayStaysInsideZeroToThreeSeconds() {
        repeat(20) {
            assertTrue(StandalonePolicy.randomDelaySeconds(0, 3, Random(it)) in 0..3)
        }
    }

    @Test
    fun unknownCandidateFieldsAreRetainedByFilters() {
        val candidate = CandidateEntity(
            id = "1",
            externalUserId = "user-1",
            displayName = "测试",
            gender = null,
            consumption = null,
        )
        assertTrue(StandalonePolicy.candidateMatches(candidate, "female", 100, 200, "all"))
        assertFalse(StandalonePolicy.candidateMatches(candidate.copy(source = "csv"), "all", null, null, "scan"))
        assertFalse(
            StandalonePolicy.candidateMatches(
                candidate,
                "female",
                100,
                200,
                "all",
                unknownFieldPolicy = "exclude",
            ),
        )
    }

    @Test
    fun approvalExpiresAtBoundary() {
        assertTrue(StandalonePolicy.approvalExpired(1_000, 1_000))
        assertFalse(StandalonePolicy.approvalExpired(1_001, 1_000))
    }

    @Test
    fun assetSelectionRemovesDuplicatesAndKeepsOrder() {
        assertEquals(
            listOf("a", "b", "c"),
            StandalonePolicy.normalizeAssetSelection(listOf(" a ", "b", "a", "", "c")),
        )
    }

    @Test
    fun assetSelectionRejectsMoreThanThreeImages() {
        assertThrows(IllegalArgumentException::class.java) {
            StandalonePolicy.normalizeAssetSelection(listOf("a", "b", "c", "d"))
        }
    }

    @Test
    fun blockRulesMatchExactIdAndNicknameKeyword() {
        val idRule = BlockRuleEntity(
            id = "1",
            type = "external_id",
            value = "USER-1",
            normalizedValue = "user-1",
        )
        val nameRule = BlockRuleEntity(
            id = "2",
            type = "nickname_keyword",
            value = "测试",
            normalizedValue = "测试",
        )
        assertEquals(
            idRule,
            StandalonePolicy.matchingBlockRule("user-1", "其他", listOf(idRule, nameRule)),
        )
        assertEquals(
            nameRule,
            StandalonePolicy.matchingBlockRule("user-2", "昵称测试号", listOf(idRule, nameRule)),
        )
    }

    @Test
    fun sessionTransitionsRejectTerminalResume() {
        StandalonePolicy.requireSessionTransition("draft", "running")
        StandalonePolicy.requireSessionTransition("running", "paused")
        StandalonePolicy.requireSessionTransition("paused", "running")
        StandalonePolicy.requireSessionTransition("running", "completed")
        assertThrows(IllegalArgumentException::class.java) {
            StandalonePolicy.requireSessionTransition("completed", "running")
        }
    }

    @Test
    fun failureReasonsBecomeStableCodes() {
        assertEquals("approval_expired", StandalonePolicy.failureCode("批准已过期"))
        assertEquals("asset_missing", StandalonePolicy.failureCode("任务图片缺失"))
        assertEquals("recipient_mismatch", StandalonePolicy.failureCode("当前收件人不一致"))
        assertEquals("execution_failed", StandalonePolicy.failureCode("点击失败"))
    }

    @Test
    fun priorityScoreIsCappedAndExplainable() {
        val now = 10_000_000_000L
        val score = StandalonePolicy.priorityBreakdown(
            nowEpochMs = now,
            lastInteractionAtEpochMs = now - 12 * 60 * 60 * 1_000,
            interactionCountInThirtyDays = 50,
            followed = true,
            giftEventCount = 20,
        )
        assertEquals(100, score.total)
        assertEquals(40, score.recency)
        assertEquals(30, score.frequency)
        assertEquals(10, score.followed)
        assertEquals(20, score.gifts)
    }

    @Test
    fun contactLimitsEnforceSevenDaysAndDailyCap() {
        assertFalse(StandalonePolicy.contactLimitStatus(2, 3).allowed)
        assertFalse(StandalonePolicy.contactLimitStatus(1, 20).allowed)
        assertTrue(StandalonePolicy.contactLimitStatus(1, 19).allowed)
    }

    @Test
    fun riskyMessagePatternsAreReported() {
        val risks = StandalonePolicy.messageRisks("赶紧充值支持一下，不然我会失望")
        assertTrue(risks.any { it.code == "solicitation" })
        assertTrue(risks.any { it.code == "pressure" })
        assertTrue(StandalonePolicy.messageRisks("谢谢你今天来直播间，早点休息。").isEmpty())
    }

    @Test
    fun priorityStageRequiresScoreAndDoNotContactIsAlwaysAvailable() {
        assertThrows(IllegalArgumentException::class.java) {
            StandalonePolicy.requireRelationshipStageChange("priority", 69)
        }
        StandalonePolicy.requireRelationshipStageChange("priority", 70)
        StandalonePolicy.requireRelationshipStageChange("do_not_contact", 0)
    }
}
