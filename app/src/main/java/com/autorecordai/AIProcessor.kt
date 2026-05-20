package com.autorecordai

import android.util.Base64
import android.util.Log
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

object AIProcessor {

    private const val TAG = "AIProcessor"

    // 豆包 ARK API Key（同时用于语音转写和AI总结）
    private const val DOUBAO_API_KEY = "ark-a6c2e7aa-49d9-4303-b426-c71ca9c3cf3e-8d905"

    // 语音转写模型（支持音频输入）
    private const val ASR_MODEL = "doubao-seed-2-0-lite-260428"

    // AI总结模型
    private const val SUMMARY_MODEL = "doubao-1-5-lite-32k-250115"

    private const val ARK_API_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"

    /**
     * 语音转文字（使用豆包 seed-2.0-lite 模型，支持音频输入）
     * @param filePath 音频文件路径
     * @return 转写后的文字
     */
    fun transcribeAudio(filePath: String): String {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "音频文件不存在: $filePath")
                return ""
            }

            // 读取音频文件并 base64 编码
            val audioBytes = file.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            Log.d(TAG, "音频文件: ${file.length()} bytes, base64 长度: ${base64Audio.length}")

            // 构建请求 JSON（OpenAI 兼容格式，使用 input_audio）
            val audioFormat = getAudioFormat(filePath)
            val contentArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", "请仔细听这段音频，把里面的说话内容逐字转写成文字。如果是中文请输出简体中文，如果是英文请输出英文。只输出转写结果，不要加任何解释。")
                })
                put(JSONObject().apply {
                    put("type", "input_audio")
                    put("input_audio", JSONObject().apply {
                        put("data", base64Audio)
                        put("format", audioFormat)
                    })
                })
            }

            val payload = JSONObject().apply {
                put("model", ASR_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", contentArray)
                    })
                })
                // 降低温度，让输出更稳定
                put("temperature", 0.1)
            }

            Log.d(TAG, "发送语音转写请求到模型: $ASR_MODEL")

            // 发送 HTTP 请求
            val response = postJson(ARK_API_URL, payload.toString(), DOUBAO_API_KEY)
            Log.d(TAG, "API 响应: ${response.substring(0, Math.min(500, response.length))}")

            parseChatResponse(response)

        } catch (e: Exception) {
            Log.e(TAG, "语音转写失败: ${e.message}", e)
            ""
        }
    }

    /**
     * AI 总结（使用豆包 lite 模型）
     * @param text 要总结的文字
     * @return AI 总结结果
     */
    fun summarizeWithDoubao(text: String): String {
        return try {
            if (text.isBlank()) {
                Log.w(TAG, "总结内容为空，跳过")
                return "（无内容可总结）"
            }

            val prompt = """
                请用简洁的中文总结以下内容，不超过200字。
                内容：$text
            """.trimIndent()

            val payload = JSONObject().apply {
                put("model", SUMMARY_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", 0.3)
            }

            Log.d(TAG, "发送 AI 总结请求，内容长度: ${text.length}")

            val response = postJson(ARK_API_URL, payload.toString(), DOUBAO_API_KEY)
            parseChatResponse(response)

        } catch (e: Exception) {
            Log.e(TAG, "AI 总结失败: ${e.message}", e)
            "（AI 总结失败：${e.message}）"
        }
    }

    /**
     * 完整处理：录音文件 → 转文字 → AI 总结
     */
    fun processAudioFile(filePath: String): Pair<String, String> {
        Log.d(TAG, "开始处理音频文件: $filePath")

        // 第一步：语音转文字
        Log.d(TAG, "第一步：语音转文字...")
        val transcribedText = transcribeAudio(filePath)
        Log.d(TAG, "转写结果: $transcribedText")

        // 第二步：AI 总结
        Log.d(TAG, "第二步：AI 总结...")
        val summary = if (transcribedText.isNotBlank()) {
            summarizeWithDoubao(transcribedText)
        } else {
            "（语音转写失败，无法总结）"
        }
        Log.d(TAG, "总结结果: $summary")

        return Pair(transcribedText, summary)
    }

    // ==================== 私有方法 ====================

    private fun postJson(urlStr: String, jsonBody: String, apiKey: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true
        conn.connectTimeout = 60000  // 60秒超时（音频处理较慢）
        conn.readTimeout = 120000

        val writer = OutputStreamWriter(conn.outputStream, "UTF-8")
        writer.write(jsonBody)
        writer.flush()
        writer.close()

        val responseCode = conn.responseCode
        if (responseCode == 200) {
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            return response
        } else {
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            conn.disconnect()
            throw Exception("HTTP $responseCode: $error")
        }
    }

    private fun parseChatResponse(json: String): String {
        val root = JSONObject(json)
        if (root.has("error")) {
            val err = root.getJSONObject("error")
            throw Exception("API 错误: ${err.optString("message", err.toString())}")
        }
        val choices = root.getJSONArray("choices")
        if (choices.length() == 0) throw Exception("API 返回空结果")
        val message = choices.getJSONObject(0).getJSONObject("message")
        return message.optString("content", "").trim()
    }

    private fun getAudioFormat(filePath: String): String {
        val ext = filePath.substringAfterLast('.').lowercase()
        return when (ext) {
            "m4a" -> "m4a"
            "mp3" -> "mp3"
            "mp4" -> "mp4"
            "ogg" -> "ogg"
            "flac" -> "flac"
            else -> "wav"
        }
    }
}
