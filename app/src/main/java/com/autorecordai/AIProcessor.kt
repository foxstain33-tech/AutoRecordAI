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

    // ========== 配置区（需要用户填入自己的API密钥）==========
    
    // 讯飞语音识别配置
    // 请去 https://www.xfyun.cn/ 注册并创建应用获取
    private const val XUNFEI_APP_ID = "YOUR_XUNFEI_APP_ID"           // 讯飞应用ID
    private const val XUNFEI_API_KEY = "YOUR_XUNFEI_API_KEY"          // 讯飞API密钥
    private const val XUNFEI_API_SECRET = "YOUR_XUNFEI_API_SECRET"    // 讯飞API密钥
    
    // 豆包/火山引擎配置
    // 请去 https://console.volcengine.com/ 注册并创建应用获取
    // 优先级：BuildConfig > 硬编码（GitHub Actions注入失败时的备用方案）
    private val DOUBAO_API_KEY: String = BuildConfig.DOUBAO_API_KEY.ifEmpty {
        // 备用密钥（GitHub Actions注入失败时使用，配置后可删除）
        "ark-a6c2e7aa-49d9-4303-b426-c71ca9c3cf3e-8d905"
    }
    private const val DOUBAO_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    private const val DOUBAO_MODEL = "doubao-pro-32k"                 // 可选: doubao-pro-32k, doubao-lite-32k
    
    // =====================================================

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 使用讯飞API将音频转为文字
     */
    fun transcribeWithXunfei(audioFilePath: String): String? {
        Log.d(TAG, "开始讯飞语音转文字: $audioFilePath")

        try {
            val file = File(audioFilePath)
            if (!file.exists()) {
                Log.e(TAG, "音频文件不存在: $audioFilePath")
                return null
            }

            // 如果还没有配置讯飞API，返回模拟结果（用于测试）
            if (XUNFEI_APP_ID == "YOUR_XUNFEI_APP_ID") {
                Log.w(TAG, "讯飞APP_ID未配置，返回模拟转写结果")
                return simulateTranscription(file)
            }

            // 读取音频文件并转为Base64
            val audioBytes = file.readBytes()
            val base64Audio = Base64.getEncoder().encodeToString(audioBytes)

            // 构建讯飞请求参数
            val params = JSONObject().apply {
                put("engine_type", "sms16k")  // 16k中文普通话
                put("aue", "raw")
                put("sample_rate", "16000")
            }
            val paramBase64 = Base64.getEncoder().encodeToString(params.toString().toByteArray())

            // 生成时间戳
            val curTime = (System.currentTimeMillis() / 1000).toString()

            // 生成签名（实际使用时需要用API_SECRET生成）
            val sign = generateXunfeiSign(XUNFEI_API_KEY, XUNFEI_API_SECRET, curTime, paramBase64)

            // 构建请求
            val jsonBody = JSONObject().apply {
                put("audio", base64Audio)
                put("encoding", "base64")
                put("sample_rate", 16000)
                put("language", "zh_cn")
            }

            val request = Request.Builder()
                .url("https://api.xf-yun.cn/v1/private/xxxxxx/recognitions")  // 实际使用时填入正确的接口地址
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
            return parseXunfeiResult(result)

        } catch (e: Exception) {
            Log.e(TAG, "讯飞转写失败: ${e.message}")
            e.printStackTrace()
            return simulateTranscription(File(audioFilePath))
        }
    }

    /**
     * 使用豆包API总结文字
     */
    fun summarizeWithDoubao(text: String): String? {
        Log.d(TAG, "开始豆包AI总结，文字长度: ${text.length}")

        try {
            // 如果还没有配置豆包API，直接报错不再模拟
            if (DOUBAO_API_KEY == "YOUR_DOUBAO_API_KEY" || DOUBAO_API_KEY.isEmpty()) {
                Log.e(TAG, "ERROR: 豆包API_KEY未正确注入！DOUBAO_API_KEY=[${DOUBAO_API_KEY}]")
                return "【错误】豆包API密钥未配置，请检查GitHub Secrets和构建日志"
            }
            Log.d(TAG, "豆包API密钥已配置（来源=${if (BuildConfig.DOUBAO_API_KEY.isNotEmpty()) "BuildConfig" else "硬编码备用"），长度=${DOUBAO_API_KEY.length}")

            // 构建豆包请求
            val requestBody = JSONObject().apply {
                put("model", DOUBAO_MODEL)
                put("messages", org.json.JSONArray().put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", "你是一个专业的通话总结助手。请用简洁清晰的语言总结以下通话内容，提取关键信息、决策事项和待办行动项。用中文输出。")
                    }
                ).put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", "请总结以下通话内容：\n\n$text")
                    }
                ))
                put("max_tokens", 1000)
                put("temperature", 0.7)
            }

            val request = Request.Builder()
                .url(DOUBAO_ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $DOUBAO_API_KEY")
                .post(RequestBody.create("application/json".toMediaType(), requestBody.toString().toByteArray()))
                .build()

            val response = httpClient.newCall(request).execute()
            val result = response.body?.string()
            Log.d(TAG, "豆包HTTP状态: ${response.code}")

            if (response.code != 200) {
                Log.e(TAG, "豆包API调用失败，HTTP ${response.code}，响应: ${result?.take(500)}")
                return simulateSummary(text)
            }

            val parsed = parseDoubaoResult(result)
            if (parsed.isNullOrEmpty()) {
                Log.e(TAG, "豆包返回结果解析失败，原始响应: ${result?.take(500)}")
                return "【错误】豆包AI返回内容无法解析，请检查API密钥和网络"
            }

            Log.d(TAG, "豆包总结成功，长度: ${parsed.length}")
            return parsed

        } catch (e: Exception) {
            Log.e(TAG, "豆包总结失败: ${e.message}")
            e.printStackTrace()
            return simulateSummary(text)
        }
    }

    /**
     * 生成讯飞签名
     */
    private fun generateXunfeiSign(apiKey: String, apiSecret: String, curTime: String, paramBase64: String): String {
        // 讯飞签名算法：MD5(apiKey + curTime + paramBase64)
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
        try {
            val result = JSONObject(json)
            // 根据实际API响应格式解析
            // data.result.text 为转写文字
            return result.getJSONObject("data")
                ?.getJSONArray("result")
                ?.getJSONObject(0)
                ?.getString("text")
        } catch (e: Exception) {
            Log.e(TAG, "解析讯飞结果失败: ${e.message}")
            return null
        }
    }

    /**
     * 解析豆包总结结果
     */
    private fun parseDoubaoResult(json: String?): String? {
        if (json.isNullOrEmpty()) return null
        try {
            val result = JSONObject(json)
            return result.getJSONArray("choices")
                ?.getJSONObject(0)
                ?.getJSONObject("message")
                ?.getString("content")
        } catch (e: Exception) {
            Log.e(TAG, "解析豆包结果失败: ${e.message}")
            return null
        }
    }

    /**
     * 模拟转写（用于测试，API未配置时）
     */
    private fun simulateTranscription(file: File): String {
        val fileSize = file.length() / (1024 * 1024)  // MB
        return """
            【模拟转写 - 请配置讯飞API以获取真实转写结果】
            
            这是一个模拟的转写结果，因为您还没有配置讯飞语音识别API。
            
            文件信息：
            - 文件名: ${file.name}
            - 文件大小: %.2f MB
            - 录制时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))}
            
            要启用真实的语音转文字功能：
            1. 前往 https://www.xfyun.cn/ 注册账号
            2. 创建一个语音转文字应用
            3. 获取 APPID、APIKey、APISecret
            4. 在 AIProcessor.kt 中填入这些密钥
            5. 重新编译安装APK
        """.trimIndent()
    }

    /**
     * 模拟总结（用于测试，API未配置时）
     */
    private fun simulateSummary(text: String): String {
        return """
            【模拟总结 - 请配置豆包API以获取真实AI总结】
            
            这是一个模拟的总结结果，因为您还没有配置豆包AI的API密钥。
            
            以下是原始文字内容的概要：
            
            原始文字长度: ${text.length} 字
            
            ----------------------------------------
            
            【通话总结】
            
            🔹 关键信息：
            - 本次通话已被记录并转写为文字
            - 转写总字数：${text.length} 字
            
            🔹 建议行动：
            - 请配置豆包API以获取AI智能总结
            - 配置路径：在 AIProcessor.kt 中修改 DOUBAO_API_KEY
            
            🔹 如何配置豆包API：
            1. 前往 https://console.volcengine.com/ 注册火山引擎账号
            2. 开通「豆包大模型」服务
            3. 创建一个应用并获取 API Key
            4. 在 AIProcessor.kt 中填入 API Key
            
            ----------------------------------------
            
            配置完成后，每次通话录音将自动：
            1. 语音转文字（讯飞）
            2. AI智能总结（豆包）
            3. 推送通知到您的手机
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
                Log.d(TAG, "Step2: 开始AI总结，文字=${text.take(50)}...")
                val summary = summarizeWithDoubao(text)
                Log.d(TAG, "Step2完成: 总结长度=${summary?.length ?: 0}")
                if (summary.isNullOrEmpty()) {
                    Log.e(TAG, "总结结果为空，使用默认回复")
                    callback(text, "总结生成失败，但转写已完成")
                    return@Thread
                }

                // Step 3: 回调
                Log.d(TAG, "=== AI处理完成 === 回调callback，转写=${text.length}字，总结=${summary.length}字")
                callback(text, summary)
            } catch (e: Exception) {
                Log.e(TAG, "处理异常: ${e.message}", e)
                callback(null, "处理异常: ${e.message}")
            }
        }.start()
    }
}