package com.local.interactionassistant.executor.automation

import com.local.interactionassistant.executor.data.InteractionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngkeeInteractionParserTest {
    @Test
    fun parsesVisibleAndOcrInteractionText() {
        assertEquals(
            InteractionType.COMMENT.value,
            parseIngkeeInteractionType(listOf("小明", "说：今晚好热闹")),
        )
        assertEquals(
            InteractionType.FOLLOW.value,
            parseIngkeeInteractionType(listOf("小红关注了主播")),
        )
        assertEquals(
            InteractionType.GIFT.value,
            parseIngkeeInteractionType(listOf("小李送出礼物")),
        )
        assertEquals(
            InteractionType.RETURN_VISIT.value,
            parseIngkeeInteractionType(listOf("欢迎回来", "老朋友")),
        )
        assertNull(parseIngkeeInteractionType(listOf("在线观众 128")))
    }
}
