package com.local.interactionassistant.executor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvCodecTest {
    @Test
    fun parsesRequiredAndOptionalColumns() {
        val result = CsvCodec.parseCandidates(
            """
            external_user_id,display_name,note,template_id,image_count,gender,consumption
            user-1,"测试,用户",备注,,3,female,1200
            """.trimIndent(),
        )
        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.rows.size)
        assertEquals("测试,用户", result.rows.single().displayName)
        assertEquals(3, result.rows.single().imageCount)
        assertEquals(1200L, result.rows.single().consumption)
    }

    @Test
    fun rejectsOutOfRangeImageCount() {
        val result = CsvCodec.parseCandidates(
            """
            external_user_id,display_name,note,template_id,image_count
            user-1,测试,,,4
            """.trimIndent(),
        )
        assertTrue(result.rows.isEmpty())
        assertEquals(1, result.errors.size)
    }

    @Test
    fun encodesQuotesAndNewlines() {
        val encoded = CsvCodec.encode(listOf(listOf("a,b", "a\"b", "a\nb")))
        assertEquals("\"a,b\",\"a\"\"b\",\"a\nb\"\r\n", encoded)
    }

    @Test
    fun parsesMultilinePasteWithCommaAndTabs() {
        val result = CsvCodec.parsePastedCandidates(
            """
            user-1,测试用户,备注,,2,female,1200
            user-2	第二位	备注二		0	male	800
            broken
            """.trimIndent(),
        )
        assertEquals(2, result.rows.size)
        assertEquals("user-2", result.rows[1].externalUserId)
        assertEquals(1, result.errors.size)
    }
}
