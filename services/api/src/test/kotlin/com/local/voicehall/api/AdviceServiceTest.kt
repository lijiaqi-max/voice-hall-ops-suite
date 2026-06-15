package com.local.voicehall.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdviceServiceTest {
    private val service = AdviceService(null, null, "test")

    @Test
    fun `uses a safe rule template when ai is not configured`() {
        val result = service.generate(
            AdviceRequest(
                relationshipStage = "active",
                interactionRecencyBucket = "3天内",
                valueLevel = "important",
                taskPurpose = "回访问候",
                tone = "温和",
            ),
        )
        assertTrue(result.fallbackUsed)
        assertTrue(result.humanConfirmationRequired)
        assertTrue(result.advice.contains("不急着回复"))
        assertTrue(result.riskTags.isEmpty())
    }

    @Test
    fun `detects prohibited pressure and false intimacy`() {
        val risks = service.detectRisks("宝贝赶紧充值刷礼物，不然让我失望，怎么不回？")
        assertEquals(
            setOf("消费施压", "索礼或情绪施压", "虚假亲密", "频繁催促"),
            risks.toSet(),
        )
        assertFalse(risks.isEmpty())
    }
}
