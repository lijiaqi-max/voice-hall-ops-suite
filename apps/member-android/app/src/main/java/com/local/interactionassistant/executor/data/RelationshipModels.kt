package com.local.interactionassistant.executor.data

import kotlinx.serialization.Serializable

enum class RelationshipStage(val value: String) {
    NEW_INTERACTION("new_interaction"),
    FOLLOWED("followed"),
    GIFTED("gifted"),
    FOLLOW_UP_DUE("follow_up_due"),
    ACTIVE("active"),
    PRIORITY("priority"),
    DORMANT("dormant"),
    DO_NOT_CONTACT("do_not_contact"),
}

enum class InteractionType(val value: String) {
    COMMENT("comment"),
    FOLLOW("follow"),
    GIFT("gift"),
    RETURN_VISIT("return_visit"),
    MANUAL_CONFIRMED("manual_confirmed"),
}

enum class ContactEligibility(val value: String) {
    INELIGIBLE("ineligible"),
    INTERACTION("interaction"),
    MANUAL_CONFIRMED("manual_confirmed"),
    DO_NOT_CONTACT("do_not_contact"),
}

@Serializable
data class PriorityBreakdown(
    val total: Int,
    val recency: Int,
    val frequency: Int,
    val followed: Int,
    val gifts: Int,
)

@Serializable
data class MessageRisk(
    val code: String,
    val label: String,
)

@Serializable
data class ContactLimitStatus(
    val allowed: Boolean,
    val reason: String,
    val userCountInSevenDays: Int,
    val globalCountToday: Int,
)
