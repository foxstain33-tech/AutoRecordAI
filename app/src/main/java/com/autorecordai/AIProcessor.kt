package com.autorecordai

import android.util.Log
import java.io.File
import kotlin.Pair
import org.json.JSONObject
import org.json.JSONArray

object AIProcessor {
    private const val TAG = "AIProcessor"
    
    // 豆包 API 配置（修正变量名）
    private const val DOUBAO_API_KEY = "ark-a6c2e7aa-49d9-4303-b426-c71ca9c3cf3e-8d905"
    private const val DOUBAO_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    private const val DOUBAO_ENDPOINT_ID = "ep-20260521105536-btrqp"
    
    // Groq Whisper 配置
    private const val GROQ_API_KEY = "gsk_free_placeholder"
    private const val GROQ_WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    
    /**
     * 语音转文字 - 使用 Groq Whisper
     */
    fun transcribeAudio(filePath: String): String {
        Log.d(TAG, "transcribeAudio: $filePath")
        
        try {
            val audioFile = File(filePath)
            if (!audioFile.exists()) {
                Log.e(TAG, "录音文件不存在: $filePath")
                return "[错误] 录音文件不存在"
            }
            
            Log.d(TAG, "开始语音转文字，文件大小: ${audioFile.length()} bytes")
            
            val result = sendGroqWhisperRequest(audioFile)
            
            if (result.isNotEmpty() && !result.startsWith("[错误]")) {
                Log.d(TAG, "转写成功，长度: ${result.length}")
                return result
            } else {
                Log.w(TAG, "转写失败: $result")
                return "[错误] 转写失败: $result"
            }
        } catch (e: Exception) {
            Log.e(TAG, "转写失败: ${e.message}")
            e.printStackTrace()
            return "[错误] 转写异常: ${e.message}"
        }
    }
    
    /**
     * AI 总结 - 使用豆包大模型
     */
    fun summarizeWithDoubao(text: String): String {
        Log.d(TAG, "summarizeWithDoubao, text length: ${text.length}")
        
        if (text.isEmpty()) {
            Log.w(TAG, "输入文本为空，跳过总结")
            return "[错误] 输入文本为空"
        }
        
        try {
            val prompt = buildPrompt(text)
            Log.d(TAG, "发送到豆包进行总结...")
            
            val result = sendDoubaoRequest(prompt)
            
            if (result.isNotEmpty() && !result.startsWith("[错误]")) {
                Log.d(TAG, "总结成功，长度: ${result.length}")
                return result
            } else {
                Log.w(TAG, "总结失败: $result")
                return "[错误] 总结失败: $result"
            }
        } catch (e: Exception) {
            Log.e(TAG, "总结失败: ${e.message}")
            e.printStackTrace()
            return "[错误] 总结异常: ${e.message}"
        }
    }
    
    /**
     * 完整处理：录音文件 → 转文字 → AI 总结
     */
    fun processAudioFile(filePath: String): Pair<String, String> {
        Log.d(TAG, "processAudioFile: $filePath")
        
        // Step1: 语音转文字
        val transcribedText = transcribeAudio(filePath)
        
        // Step2: AI 总结
        var summary = ""
        var textForSummary = transcribedText
        if (textForSummary.startsWith("[错误]") || textForSummary.isEmpty()) {
            Log.w(TAG, "转写结果有误，使用模拟文本测试豆包总结")
            textForSummary = "[测试] 今天的通话讨论了项目进度，约定下周三开会确认方案，张三负责准备材料，李四负责联系客户。"
        }
        summary = summarizeWithDoubao(textForSummary)
        
        return Pair(transcribedText, summary)
    }
    
    /**
     * 构建 Prompt
     */
    private fun buildPrompt(text: String): String {
        return """
            请对以下通话内容进行智能总结：
            
            $text
            
            请按以下格式输出：
            1. **通话主题**：一句话概括这次通话的主要内容
            2. **关键信息**：提取重要信息点（如时间、地点、金额、事项等）
            3. **待办事项**：列出需要跟进或处理的事项
            4. **备注**：其他需要记录的信息
        """.trimIndent()
    }
    
    /**
     * 发送请求到 Groq Whisper API
     */
    private fun sendGroqWhisperRequest(audioFile: File): String {
        val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
        val url = java.net.URL(GROQ_WHISPER_URL)
        val conn = url.openConnection() as java.net.HttpURLConnection
        
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Authorization", "Bearer $GROQ_API_KEY")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            
            val outputStream = conn.outputStream
            
            // 添加 file 字段
            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n".toByteArray())
            outputStream.write("Content-Type: audio/m4a\r\n\r\n".toByteArray())
            outputStream.write(audioFile.readBytes())
            outputStream.write("\r\n".toByteArray())
            
            // 添加 model 字段
            outputStream.write("--$boundary\r\n".toByteArray())
            outputStream.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".toByteArray())
            outputStream.write("whisper-large-v3-turbo".toByteArray())
            outputStream.write("\r\n".toByteArray())
            
            // 结束标记
            outputStream.write("--$boundary--\r\n".toByteArray())
            outputStream.flush()
            outputStream.close()
            
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                
                val textStart = response.indexOf("\"text\":\"") + 8
                val textEnd = response.indexOf("\"", textStart)
                
                if (textStart > 7 && textEnd > textStart) {
                    return response.substring(textStart, textEnd)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                }
            } else {
                Log.e(TAG, "Whisper API 错误: $responseCode")
                val error = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "错误详情: $error")
                return "[错误] Whisper API $responseCode: $error"
            }
        } finally {
            conn.disconnect()
        }
        
        return "[错误] Whisper 请求失败"
    }
    
    /**
     * 发送请求到豆包 API（使用 JSONObject 正确构建 JSON）
     */
    private fun sendDoubaoRequest(prompt: String): String {
        val url = java.net.URL(DOUBAO_URL)
        val conn = url.openConnection() as java.net.HttpURLConnection
        
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $DOUBAO_API_KEY")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            
            // 使用 JSONObject 正确构建 JSON（避免字符串拼接错误）
            val json = JSONObject()
            json.put("model", DOUBAO_ENDPOINT_ID)
            json.put("max_tokens", 2000)
            json.put("temperature", 0.7)
            
            val messages = JSONArray()
            val msg = JSONObject()
            msg.put("role", "user")
            msg.put("content", prompt)
            messages.put(msg)
            json.put("messages", messages)
            
            val requestBody = json.toString()
            Log.d(TAG, "请求体: $requestBody")
            
            conn.outputStream.write(requestBody.toByteArray(Charsets.UTF_8))
            conn.outputStream.flush()
            conn.outputStream.close()
            
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                Log.d(TAG, "响应: $response")
                
                // 正确解析 JSON 响应
                val jsonResp = JSONObject(response)
                
                // 检查是否有错误
                if (jsonResp.has("error")) {
                    val error = jsonResp.getJSONObject("error")
                    val errorMsg = error.getString("message")
                    Log.e(TAG, "豆包 API 错误: $errorMsg")
                    return "[错误] 豆包 API: $errorMsg"
                }
                
                val content = jsonResp.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                
                return content
            } else {
                Log.e(TAG, "豆包 API 错误: $responseCode")
                val error = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "错误详情: $error")
                return "[错误] 豆包 API $responseCode: $error"
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求失败: ${e.message}")
            e.printStackTrace()
            return "[错误] 请求异常: ${e.message}"
        } finally {
            conn.disconnect()
        }
        
        return "[错误] 未知错误"
    }
}
