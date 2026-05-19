package com.autorecordai

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * AI处理模块
 * 整合讯飞语音转文字 + 豆包AI总结
 */
object AIProcessor {

    private const val TAG = "AIProcessor"

    // ========== 配置区 =========
    
    // 讯飞语音识别配置（需去 https://www.xfyun.cn/ 注册）
    private const val XUNFEI_APP_ID = "YOUR_XUNFEI_APP_ID"
    private const val XUNFEI_API_KEY = "YOUR_XUNFEI_API_KEY"
    private const val XUNFEI_API_SECRET = "YOUR_XUNFEI_API_SECRET"
    
    // 豆包/火山引擎配置（已硬编码）
    private const val DOUBAO_API_KEY = "ark-a6c2e7aa-49d9-4303-b426-c71ca9c3cf3e-8d905"
    private const val DOUBAO_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    private const val DOUBAO_MODEL = "doubao-pro-32k"
    
    // =====================================================

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 使用讯飞API将音频转为文字
     */
    fun transcribeWithXunfei(audioFilePath: String): String? {
        Log.d(TAG, "开始讯飞语音转文字: $audioFilePath")

        return try {
            val file = File(audioFilePath)
            if (!file.exists()) {
                Log.e(TAG, "音频文件不存在: $audioFilePath")
                return null
            }

            // 如果还没有配置讯飞API，返回模拟结果
            if (XUNFEI_APP_ID == "YOUR_XUNFEI_APP_ID") {
                Log.w(TAG, "讯飞APP_ID未配置，返回模拟转写结果")
                return simulateTranscription(file)
            }

            // 读取音频文件并转为Base64
            val audioBytes = file.readBytes()
            val base64Audio = Base64.getEncoder().encodeToString(audioBytes)

            // 构建讯飞请求参数
            val params = JSONObject().apply {
                put("engine_type", "sms16k")
                put("aue", "raw")
                put("sample_rate", "16000")
            }
            val paramBase64 = Base64.getEncoder().encodeToString(params.toString().toByteArray())

            // 生成时间
            val curTime = (System.currentTimeMillis() / 1000).toString()

            // 生成签名
            val sign = generateXunfeiSign(XUNFEI_API_KEY, XUNFEI_API_SECRET, curTime, paramBase64)

            // 构建请求
            val jsonBody = JSONObject().apply {
                put("audio", base64Audio)
                put("encoding", "base64")
                put("sample_rate", 16000)
                put("language", "zh_cn")
            }

            val request = Request.Builder()
                .url("https://api.xf-yun.cn/v1/private/xxxxx/recognitions")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Appid", XUNFEI_APP_ID)
                .addHeader("X-CurTime", curTime)
                .addHeader("X-Param", paramBase64)
                .addHeader("X-CheckSum", sign)
                .post(RequestBody.create("application/json".toMediaType(), jsonBody.toString().toByteArray()))
                .build()

            val response = httpClient.newCall(request).execute()
            val result = response.body?.string()
            
            Log.d(TAG, "讯飞响应: $result")
            parseXunfeiResult(result)

        } catch (e: Exception) {
            Log.e(TAG, "讯飞转写失败: ${e.message}", e)
            simulateTranscription(File(audioFilePath))
        }
    }

    /**
     * 使用豆包API总结文字
     */
    fun summarizeWithDoubao(text: String): String? {
        Log.d(TAG, "开始豆包AI总结，文字长度: ${text.length}")

        if (DOUBAO_API_KEY.isEmpty() || DOUBAO_API_KEY == "YOUR_DOUBAO_API_KEY") {
            Log.e(TAG, "ERROR: 豆包API_KEY未配置！")
            return "【错误】豆包API密钥未配置，请在AIProcessor.kt中填入真实密钥"
        }

        return try {
            Log.d(TAG, "豆包API密钥已配置，长度=${DOUBAO_API_KEY.length}")
            Log.d(TAG, "构建请求，文字前100字: ${text.take(100)}")

            // 构建消息数组
            val messagesArray = org.json.JSONArray()
            
            val systemMsg = JSONObject()
            systemMsg.put("role", "system")
            systemMsg.put("content", "你是一个专业的通话总结助手。请用简洁清晰的语言总结以下通话内容，提取关键信息、决策事项和待办行动项。用中文输出。")
            messagesArray.put(systemMsg)
            
            val userMsg = JSONObject()
            userMsg.put("role", "user")
            userMsg.put("content", "请总结以下通话内容：\n\n$text")
            messagesArray.put(userMsg)

            val requestBody = JSONObject()
            requestBody.put("model", DOUBAO_MODEL)
            requestBody.put("messages", messagesArray)
            requestBody.put("max_tokens", 1000)
            requestBody.put("temperature", 0.7)

            val jsonString = requestBody.toString()
            Log.d(TAG, "请求JSON长度: ${jsonString.length}")

            val request = Request.Builder()
                .url(DOUBAO_ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $DOUBAO_API_KEY")
                .post(RequestBody.create("application/json".toMediaType(), jsonString.toByteArray()))
                .build()

            Log.d(TAG, "发送豆包API请求...")
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body
            val result = responseBody?.string()
            
            Log.d(TAG, "豆包HTTP状态: ${response.code}")
            Log.d(TAG, "豆包响应长度: ${result?.length ?: 0}")
            Log.d(TAG, "豆包响应前200字: ${result?.take(200)}")

            if (!response.isSuccessful) {
                Log.e(TAG, "豆包API调用失败，HTTP ${response.code}，响应: ${result?.take(500)}")
                return "【错误】豆包AI返回错误，HTTP ${response.code}，请检查API密钥是否有效"
            }

            if (result.isNullOrEmpty()) {
                Log.e(TAG, "豆包返回空响应")
                return "【错误】豆包AI返回空响应"
            }

            val parsed = parseDoubaoResult(result)
            if (parsed.isNullOrEmpty()) {
                Log.e(TAG, "豆包返回结果解析失败，原始响应: ${result.take(500)}")
                return "【错误】豆包AI返回内容无法解析，请检查API响应格式"
            }

            Log.d(TAG, "豆包总结成功，长度: ${parsed.length}")
            parsed

        } catch (e: Exception) {
            Log.e(TAG, "豆包总结失败: ${e.message}", e)
            "【错误】豆包AI调用异常: ${e.message}"
        }
    }

    /**
     * 生成讯飞签名
     */
    private fun generateXunfeiSign(apiKey: String, apiSecret: String, curTime: String, paramBase64: String): String {
        val input = "$apiKey$curTime$paramBase64"
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 解析讯飞转写结果
     */
    private fun parseXunfeiResult(json: String?): String? {
        if (json.isNullOrEmpty()) return null
        return try {
            val result = JSONObject(json)
            result.getJSONObject("data")
                ?.getJSONArray("result")
                ?.getJSONObject(0)
                ?.getString("text")
        } catch (e: Exception) {
            Log.e(TAG, "解析讯飞结果失败: ${e.message}")
            null
        }
    }

    /**
     * 解析豆包总结结果 - 增强版
     */
    private fun parseDoubaoResult(json: String?): String? {
        if (json.isNullOrEmpty()) return null
        return try {
            Log.d(TAG, "开始解析豆包响应...")
            val result = JSONObject(json)
            
            // 打印完整响应结构（用于调试）
            Log.d(TAG, "豆包响应keys: ${result.keys().asSequence().toList()}")
            
            // 检查是否有错误字段
            if (result.has("error")) {
                val err = result.getJSONObject("error")
                val errMsg = err.optString("message", "未知错误")
                Log.e(TAG, "豆包API返回错误: $errMsg")
                return null
            }
            
            // 标准格式: choices[0].message.content
            if (result.has("choices")) {
                val choices = result.getJSONArray("choices")
                if (choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    if (firstChoice.has("message")) {
                        val message = firstChoice.getJSONObject("message")
                        if (message.has("content")) {
                            val content = message.getString("content")
                            Log.d(TAG, "解析成功（标准格式），内容长度: ${content.length}")
                            return content
                        }
                    }
                    // 可能的 delta 格式
                    if (firstChoice.has("delta")) {
                        val delta = firstChoice.getJSONObject("delta")
                        if (delta.has("content")) {
                            val content = delta.getString("content")
                            Log.d(TAG, "解析成功（delta格式），内容长度: ${content.length}")
                            return content
                        }
                    }
                }
            }
            
            // 流式格式: data: {...}
            if (result.has("data")) {
                val data = result.get("data")
                Log.d(TAG, "data字段类型: ${data::class.java.simpleName}")
            }
            
            Log.e(TAG, "无法从豆包响应中提取content字段")
            Log.e(TAG, "响应结构: ${result.toString().take(300)}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "解析豆包JSON失败: ${e.message}", e)
            null
        }
    }

    /**
     * 模拟转写（用于测试）
     */
    private fun simulateTranscription(file: File): String {
        val fileSize = file.length() / (1024 * 1024)
        return """
            【模拟转写 - 请配置讯飞API以获取真实转写结果】
            
            这是一个模拟的转写结果，因为您还没有配置讯飞语音识别API。
            
            文件信息：
            - 文件名：${file.name}
            - 文件大小: ${"%.2f".format(fileSize)} MB
            
            要启用真实的语音转文字功能，请配置讯飞API密钥后重新编译。
        """.trimIndent()
    }

    /**
     * 同时进行转写和总结（完整流程）
     */
    fun processAudioFile(audioFilePath: String, callback: (String?, String?) -> Unit) {
        Thread {
            Log.d(TAG, "=== AI处理开始 === 文件: $audioFilePath")
            try {
                // Step 1: 转写
                Log.d(TAG, "Step1: 开始转写...")
                val text = transcribeWithXunfei(audioFilePath)
                Log.d(TAG, "Step1完成: 转写长度=${text?.length ?: 0}")
                
                if (text.isNullOrEmpty()) {
                    Log.e(TAG, "转写结果为空")
                    callback(null, "转写失败：未能获取文字内容")
                    return@Thread
                }

                // Step 2: 总结
                Log.d(TAG, "Step2: 开始AI总结，文字: ${text.take(50)}...")
                val summary = summarizeWithDoubao(text)
                Log.d(TAG, "Step2完成: 总结长度=${summary?.length ?: 0}")
                
                if (summary.isNullOrEmpty()) {
                    Log.e(TAG, "总结结果为空")
                    callback(text, "总结生成失败，但转写已完成")
                    return@Thread
                }

                // Step 3: 回调
                Log.d(TAG, "=== AI处理完成 ===")
                callback(text, summary)
                
            } catch (e: Exception) {
                Log.e(TAG, "处理异常: ${e.message}", e)
                callback(null, "处理异常: ${e.message}")
            }
        }.start()
    }
}
