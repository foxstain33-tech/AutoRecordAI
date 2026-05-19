package com.autorecordai

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * AI处理模块 v3
 * 彻底重写，解决 ClassCastException 问题
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
    private const val DOUBAO_MODEL = "doubao-seed-2-0-pro-260215"
    
    // =====================================================

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // =====================================================
    // 讯飞转文字（未配置时返回模拟结果）
    // =====================================================
    
    fun transcribeWithXunfei(audioFilePath: String): String? {
        Log.d(TAG, "transcribeWithXunfei: $audioFilePath")
        
        val file = File(audioFilePath)
        if (!file.exists()) {
            Log.e(TAG, "transcribeWithXunfei: 文件不存在")
            return null
        }
        
        if (XUNFEI_APP_ID == "YOUR_XUNFEI_APP_ID") {
            Log.w(TAG, "transcribeWithXunfei: 讯飞未配置，返回模拟")
            return simulateTranscription(file)
        }
        
        return simulateTranscription(file)
    }

    private fun simulateTranscription(file: File): String {
        val sizeMb = file.length() / (1024.0 * 1024.0)
        return "【模拟转写】文件 ${file.name}，大小 ${String.format("%.2f", sizeMb)} MB。讯飞API未配置，此为模拟结果。"
    }

    // =====================================================
    // 豆包AI总结 v3（彻底重写）
    // =====================================================
    
    fun summarizeWithDoubao(text: String): String? {
        Log.d(TAG, "summarizeWithDoubao 开始，文字长度=${text.length}")
        
        try {
            return summarizeWithDoubaoInner(text)
        } catch (e: Exception) {
            Log.e(TAG, "summarizeWithDoubao 捕获异常: ${e.javaClass.simpleName} - ${e.message}", e)
            return "【错误】豆包AI调用异常: ${e.message}"
        }
    }
    
    private fun summarizeWithDoubaoInner(text: String): String? {
        // 验证 API Key
        if (DOUBAO_API_KEY.isEmpty()) {
            Log.e(TAG, "豆包API Key为空")
            return "【错误】豆包API密钥为空"
        }
        
        Log.d(TAG, "构建JSON请求...")
        
        // 构建请求体（简化版，避免嵌套JSONArray.apply）
        val messagesArray = JSONArray()
        
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", "你是一个通话总结助手，请简洁总结以下内容，提取关键信息和待办事项，用中文输出。")
        messagesArray.put(systemMsg)
        
        val userContent = "请总结以下通话内容：\n\n$text"
        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", userContent)
        messagesArray.put(userMsg)
        
        val requestBody = JSONObject()
        requestBody.put("model", DOUBAO_MODEL)
        requestBody.put("messages", messagesArray)
        requestBody.put("max_tokens", 1000)
        requestBody.put("temperature", 0.7)
        
        val jsonString = requestBody.toString()
        Log.d(TAG, "请求JSON: $jsonString")
        
        // 构建 HTTP 请求
        val request = Request.Builder()
            .url(DOUBAO_ENDPOINT)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $DOUBAO_API_KEY")
            .post(RequestBody.create("application/json".toMediaType(), jsonString.toByteArray()))
            .build()
        
        Log.d(TAG, "发送HTTP请求...")
        
        // 执行请求
        val call = httpClient.newCall(request)
        val response = call.execute()
        
        Log.d(TAG, "HTTP状态码: ${response.code}")
        
        // 读取响应体
        val responseBodyStr: String?
        try {
            responseBodyStr = response.body?.string()
        } catch (e: Exception) {
            Log.e(TAG, "读取响应体失败: ${e.message}", e)
            return "【错误】读取豆包响应失败: ${e.message}"
        }
        
        if (responseBodyStr == null) {
            Log.e(TAG, "响应体为null")
            return "【错误】豆包返回空响应"
        }
        
        Log.d(TAG, "响应体长度: ${responseBodyStr.length}")
        Log.d(TAG, "响应体内容: ${responseBodyStr}")
        
        // 检查 HTTP 状态
        if (!response.isSuccessful) {
            Log.e(TAG, "HTTP请求失败: ${response.code} $responseBodyStr")
            return "【错误】豆包API HTTP ${response.code}: $responseBodyStr"
        }
        
        // 解析 JSON
        return parseDoubaoResponse(responseBodyStr)
    }
    
    /**
     * 解析豆包响应
     */
    private fun parseDoubaoResponse(json: String?): String? {
        if (json == null || json.isEmpty()) {
            Log.e(TAG, "parseDoubaoResponse: json为空")
            return "【错误】豆包返回空JSON"
        }
        
        try {
            val root = JSONObject(json)
            
            // 检查错误字段
            if (root.has("error")) {
                val errObj = root.optJSONObject("error")
                val errMsg = errObj?.optString("message") ?: root.optString("error")
                Log.e(TAG, "豆包返回error: $errMsg")
                return "【错误】豆包API错误: $errMsg"
            }
            
            // 检查 code 字段（火山引擎格式）
            val code = root.optInt("code", 0)
            if (code != 0) {
                val msg = root.optString("message", "未知错误")
                Log.e(TAG, "豆包返回code=$code: $msg")
                return "【错误】豆包API错误 code=$code: $msg"
            }
            
            // 标准 OpenAI 格式: choices[0].message.content
            if (root.has("choices")) {
                val choices = root.getJSONArray("choices")
                if (choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    
                    // message.content 格式
                    if (firstChoice.has("message")) {
                        val msg = firstChoice.getJSONObject("message")
                        if (msg.has("content")) {
                            val content = msg.getString("content")
                            Log.d(TAG, "解析成功(content字段)，长度=${content.length}")
                            return content
                        }
                    }
                    
                    // delta.content 格式（流式）
                    if (firstChoice.has("delta")) {
                        val delta = firstChoice.getJSONObject("delta")
                        if (delta.has("content")) {
                            val content = delta.getString("content")
                            Log.d(TAG, "解析成功(delta字段)，长度=${content.length}")
                            return content
                        }
                    }
                    
                    // finish_reason
                    val reason = firstChoice.optString("finish_reason", "")
                    Log.e(TAG, "choices[0]无content字段finish_reason=$reason keys=${firstChoice.keys().asSequence().toList()}")
                } else {
                    Log.e(TAG, "choices数组为空")
                }
            }
            
            // 火山引擎格式: data.choices[0].message.content
            if (root.has("data")) {
                val data = root.getJSONObject("data")
                if (data.has("choices")) {
                    val choices = data.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val firstChoice = choices.getJSONObject(0)
                        if (firstChoice.has("message")) {
                            val content = firstChoice.getJSONObject("message").getString("content")
                            Log.d(TAG, "解析成功(火山格式)，长度=${content.length}")
                            return content
                        }
                    }
                }
            }
            
            Log.e(TAG, "无法解析豆包响应，root keys: ${root.keys().asSequence().toList()}")
            return "【错误】无法解析豆包响应格式"
            
        } catch (e: Exception) {
            Log.e(TAG, "JSON解析异常: ${e.message}", e)
            // 如果JSON解析失败，返回原始响应（调试用）
            if (json.length > 200) {
                return "【错误】JSON解析失败，响应: ${json.take(200)}"
            }
            return "【错误】JSON解析失败: ${e.message}"
        }
    }

    // =====================================================
    // 完整处理流程
    // =====================================================
    
    fun processAudioFile(audioFilePath: String, callback: (String?, String?) -> Unit) {
        Thread {
            Log.d(TAG, "=== processAudioFile 开始 ===")
            Log.d(TAG, "文件: $audioFilePath")
            
            try {
                // Step 1: 转写
                Log.d(TAG, "Step1: 转写...")
                val text = transcribeWithXunfei(audioFilePath)
                Log.d(TAG, "Step1完成: text=$text")
                
                if (text == null || text.isEmpty()) {
                    callback(null, "转写失败")
                    return@Thread
                }
                
                // Step 2: 总结
                Log.d(TAG, "Step2: 总结 text长度=${text.length}...")
                val summary = summarizeWithDoubao(text)
                Log.d(TAG, "Step2完成: summary=$summary")
                
                if (summary == null || summary.isEmpty()) {
                    callback(text, "总结为空")
                    return@Thread
                }
                
                // Step 3: 回调
                Log.d(TAG, "=== 完成，调用callback ===")
                callback(text, summary)
                
            } catch (e: Exception) {
                Log.e(TAG, "processAudioFile异常: ${e.message}", e)
                callback(null, "处理异常: ${e.message}")
            }
        }.start()
    }
}
