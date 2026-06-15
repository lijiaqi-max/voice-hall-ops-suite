package com.local.voicehall.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI

class AdviceService(
    private val baseUrl: String?,
    private val apiKey: String?,
    private val model: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun generate(request: AdviceRequest): AdviceResult {
        validate(request)
        val fallback = fallback(request)
        if (baseUrl.isNullOrBlank() || apiKey.isNullOrBlank()) {
            return AdviceResult(fallback, emptyList(), fallbackUsed = true)
        }
        val generated = runCatching { requestRemote(request) }.getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return AdviceResult(fallback, emptyList(), fallbackUsed = true)
        val risks = detectRisks(generated)
        return if (risks.isEmpty()) {
            AdviceResult(generated.take(600), emptyList(), fallbackUsed = false)
        } else {
            AdviceResult(fallback, risks, fallbackUsed = true)
        }
    }

    internal fun detectRisks(text: String): List<String> {
        val normalized = text.lowercase()
        return linkedSetOf<String>().apply {
            if (listOf("刷礼物", "送礼物", "充值", "消费", "榜单", "流水").any(normalized::contains)) {
                add("消费施压")
            }
            if (listOf("不送", "不支持", "让我失望", "证明诚意", "必须").any(normalized::contains)) {
                add("索礼或情绪施压")
            }
            if (listOf("老公", "老婆", "宝贝", "只爱你", "只有你").any(normalized::contains)) {
                add("虚假亲密")
            }
            if (listOf("怎么不回", "赶紧回复", "马上回复", "一直催").any(normalized::contains)) {
                add("频繁催促")
            }
        }.toList()
    }

    private fun validate(request: AdviceRequest) {
        require(request.relationshipStage.length in 1..40)
        require(request.interactionRecencyBucket.length in 1..40)
        require(request.valueLevel.length in 1..40)
        require(request.taskPurpose.length in 1..120)
        require(request.tone.length in 1..30)
    }

    private fun fallback(request: AdviceRequest): String = when {
        request.taskPurpose.contains("感谢") ->
            "谢谢你最近的陪伴和互动，看到你来我很开心。之后有空再来坐坐，按自己的节奏就好。"
        request.taskPurpose.contains("回访") ->
            "最近还好吗？想起之前在直播间的互动，来和你打个招呼。有空时再聊，不急着回复。"
        request.taskPurpose.contains("提醒") ->
            "和你说一声近期的直播安排，时间合适再来就好。也请注意休息，不方便时不用回复。"
        else ->
            "谢谢你之前的互动，今天来简单问候一下。希望你最近顺利，有空再聊。"
    }

    private fun requestRemote(request: AdviceRequest): String {
        val endpoint = baseUrl!!.trimEnd('/') + "/chat/completions"
        val payload = buildJsonObject {
            put("model", model)
            put("temperature", 0.4)
            put("messages", buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            "你是直播关系维护文案助手。只输出一段不超过120字的中文建议。" +
                                "禁止索礼、消费施压、虚假亲密、频繁催促，不假设用户财富。" +
                                "输入已脱敏，不要求姓名、ID、联系方式或精确金额。",
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            "关系阶段=${request.relationshipStage}; " +
                                "最近互动=${request.interactionRecencyBucket}; " +
                                "价值等级=${request.valueLevel}; " +
                                "任务目的=${request.taskPurpose}; 语气=${request.tone}",
                        )
                    },
                )
            })
        }
        val connection = URI.create(endpoint).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }
            check(connection.responseCode in 200..299) { "AI service returned ${connection.responseCode}" }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.parseToJsonElement(body).jsonObject
                .getValue("choices").jsonArray.first().jsonObject
                .getValue("message").jsonObject
                .getValue("content").jsonPrimitive.content
        } finally {
            connection.disconnect()
        }
    }
}
