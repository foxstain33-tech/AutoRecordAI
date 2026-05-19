package com.autorecordai

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AI处理模块 v4
 * 使用豆包多模态模型进行语音转文字 + 豆包总结
 */
object AIProcessor {

    private const val TAG = "AIProcessor"

    // ========== 配置区 =========
    private const val DOUBAO_API_KEY = "ark-a6c2e7aa-49d9-4303-b426-c71ca9c3cf3e-8d905"
    private const val DOUBAO_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    private const val DOUBAO_MODEL = "doubao-1-5-lite-32k-250115"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // =====================================================
    // 语音转文字 - 使用豆包多模态模型
    // =====================================================

    fun transcribeAudio(audioFilePath: String): String? {
        Log.d(TAG, "transcribeAudio: $audioFilePath")

        val file = File(audioFilePath)
        if (!file.exists()) {
            Log.e(TAG, "音频文件不存在: $audioFilePath")
            return null
        }

        return try {
            transcribeWithDoubaoMultimodal(file)
        } catch (e: Exception) {
            Log.e(TAG, "语音转文字异常: " + e.message, e)
            "【转写失败】" + e.message
        }
    }

    /**
     * 使用豆包多模态模型识别音频内容
     * 将音频 base64 编码后发给豆包，让模型直接识别语音
     */
    private fun transcribeWithDoubaoMultimodal(file: File): String? {
        val audioData = file.readBytes()
        val base64Audio = java.util.Base64.getEncoder().encodeToString(audioData)

        Log.d(TAG, "豆包多模态识别音频，文件大小=" + file.length() + " bytes")

        val contentArray = JSONArray()

        val textPart = JSONObject().apply {
            put("type", "text")
            put("text", "请完整转写这段音频中的所有语音内容，只输出转写文字，不要加任何说明。如果没有语音内容，请回复：无语音内容")
        }
        contentArray.put(textPart)

        val audioPart = JSONObject().apply {
            put("type", "input_audio")
            put("input_audio", JSONObject().apply {
                put("data", "data:audio/mp4;base64," + base64Audio)
                put("format", "mp4")
            })
        }
        contentArray.put(audioPart)

        val userMsg = JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        }

        val messagesArray = JSONArray()
        messagesArray.put(userMsg)

        val requestBody = JSONObject().apply {
            put("model", DOUBAO_MODEL)
            put("messages", messagesArray)
            put("max_tokens", 2000)
        }

        val request = Request.Builder()
            .url(DOUBAO_ENDPOINT)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer " + DOUBAO_API_KEY)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string()
        Log.d(TAG, "豆包多模态响应码: " + response.code + ", 长度: " + (body?.length ?: 0))

        if (!response.isSuccessful || body == null) {
            Log.e(TAG, "豆包多模态失败: " + response.code + " " + (body?.take(200) ?: ""))
            return "【转写失败】音频识别服务暂不可用（HTTP " + response.code + "）"
        }

        val json = JSONObject(body)

        if (json.has("error")) {
            val errMsg = json.optJSONObject("error")?.optString("message") ?: json.optString("error")
            Log.e(TAG, "豆包多模态错误: " + errMsg)
            return "【转写失败】" + errMsg
        }

        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val msg = choices.getJSONObject(0).optJSONObject("message")
            if (msg != null) {
                val content = msg.optString("content", "")
                if (content.isNotEmpty() && content != "无语音内容") {
                    return content
                }
            }
        }

        return "【转写失败】无法识别音频内容"
    }

    // =====================================================
    // 豆包AI总结
    // =====================================================

    fun summarizeWithDoubao(text: String): String? {
        Log.d(TAG, "summarizeWithDoubao 开始，文字长度=" + text.length)

        try {
            val messagesArray = JSONArray()

            val systemMsg = JSONObject()
            systemMsg.put("role", "system")
            systemMsg.put("content", "你是一个通话总结助手。请简洁总结以下通话内容，提取关键信息和待办事项，用中文输出。")
            messagesArray.put(systemMsg)

            val userMsg = JSONObject()
            userMsg.put("role", "user")
            userMsg.put("content", "请总结以下通话内容：\n\n" + text)
            messagesArray.put(userMsg)

            val requestBody = JSONObject()
            requestBody.put("model", DOUBAO_MODEL)
            requestBody.put("messages", messagesArray)
            requestBody.put("max_tokens", 1000)
            requestBody.put("temperature", 0.7)

            val jsonStr = requestBody.toString()
            val request = Request.Builder()
                .url(DOUBAO_ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + DOUBAO_API_KEY)
                .post(jsonStr.toByteArray().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBodyStr = response.body?.string()

            if (responseBodyStr == null) {
                return "【错误】豆包返回空响应"
            }

            if (!response.isSuccessful) {
                return "【错误】豆包API HTTP " + response.code + ": " + responseBodyStr
            }

            val root = JSONObject(responseBodyStr)

            if (root.has("error")) {
                val errObj = root.optJSONObject("error")
                val errMsg = errObj?.optString("message") ?: root.optString("error")
                return "【错误】豆包API错误: " + errMsg
            }

            if (root.has("choices")) {
                val choices = root.getJSONArray("choices")
                if (choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    if (firstChoice.has("message")) {
                        val msg = firstChoice.getJSONObject("message")
                        if (msg.has("content")) {
                            return msg.getString("content")
                        }
                    }
                }
            }

            return "【错误】无法解析豆包响应格式"
        } catch (e: Exception) {
            Log.e(TAG, "summarizeWithDoubao异常: " + e.message, e)
            return "【错误】豆包AI调用异常: " + e.message
        }
    }

    // =====================================================
    // 完整处理流程
    // =====================================================

    fun processAudioFile(audioFilePath: String, callback: (String?, String?) -> Unit) {
        Thread {
            Log.d(TAG, "=== processAudioFile 开始 ===")
            Log.d(TAG, "文件: " + audioFilePath)

            try {
                // Step 1: 语音转文字（豆包多模态）
                Log.d(TAG, "Step1: 语音转文字...")
                val text = transcribeAudio(audioFilePath)
                Log.d(TAG, "Step1完成: text长度=" + (text?.length ?: 0))

                if (text == null || text.isEmpty()) {
                    callback(null, "转写失败：无法识别音频内容")
                    return@Thread
                }

                // Step 2: AI总结
                Log.d(TAG, "Step2: AI总结...")
                val summary = summarizeWithDoubao(text)
                Log.d(TAG, "Step2完成: summary长度=" + (summary?.length ?: 0))

                callback(text, summary)

            } catch (e: Exception) {
                Log.e(TAG, "processAudioFile异常: " + e.message, e)
                callback(null, "处理异常: " + e.message)
            }
        }.start()
    }
}
