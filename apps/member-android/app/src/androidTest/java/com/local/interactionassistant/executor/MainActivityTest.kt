package com.local.interactionassistant.executor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun switchesBetweenAllStandalonePages() {
        val pages = listOf(
            "TASKS" to "活动任务",
            "CANDIDATES" to "手动确认已有关系",
            "TEMPLATES" to "新建话术模板",
            "ASSETS" to "从相册或文件导入图片",
            "HISTORY" to "统计",
            "SETTINGS" to "权限与入口",
        )
        pages.forEach { (tag, expectedText) ->
            rule.onNodeWithTag("tab-$tag")
                .performScrollTo()
                .performClick()
            rule.onNodeWithText(expectedText, substring = true).assertIsDisplayed()
        }
        rule.onNodeWithText("两次人工确认", substring = true).assertIsDisplayed()
    }
}
