package com.autorecordai

import android.util.Log
import java.io.File
import kotlin.Pair

object AIProcessor {
    private const val TAG = "AIProcessor"
    
    // 豆包 API 配置
    private const val DOUBAO_API_KEY = "ark-a6c2e7aa-49d9-4303-b426-c71ca9c3cf3e-8d905"
    private const val DOUBAO_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    private const val DOUBAO_MODEL = "doubao-pro-32k"
    
    // Groq Whisper 配置
    private const val GROQ_API_KEY = "gsk_free_placeholder"  // Groq 免费API，无需真实key
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
                return ""
            }
            
            Log.d(TAG, "开始语音转文字，文件大小: ${audioFile.length()} bytes")
            
            // 使用 OkHttp 发送请求
            val result = sendGroqWhisperRequest(audioFile)
            
            if (result.isNotEmpty()) {
                Log.d(TAG, "转写成功，长度: ${result.length}")
                return result
            } else {
                Log.w(TAG, "转写结果为空")
                return ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "转写失败: ${e.message}")
            e.printStackTrace()
            return ""
        }
    }

    /**
     * AI 总结 - 使用豆包大模型
     */
    fun summarizeWithDoubao(text: String): String {
        Log.d(TAG, "summarizeWithDoubao")
        
        if (text.isEmpty()) {
            Log.w(TAG, "输入文本为空，跳过总结")
            return ""
        }
        
        try {
            val prompt = buildPrompt(text)
            Log.d(TAG, "发送到豆包进行总结...")
            
            val result = sendDoubaoRequest(prompt)
            
            if (result.isNotEmpty()) {
                Log.d(TAG, "总结成功，长度: ${result.length}")
                return result
            } else {
                Log.w(TAG, "总结结果为空")
                return ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "总结失败: ${e.message}")
            e.printStackTrace()
            return ""
        }
    }

    /**
     * 完整处理：录音文件 → 转文字 → AI 总结
     */
    fun processAudioFile(filePath: String): Pair<String, String> {
        Log.d(TAG, "processAudioFile: $filePath")
        
        // Step 1: 语音转文字
        val transcribedText = transcribeAudio(filePath)
        
        // Step 2: AI 总结
        var summary = ""
        if (transcribedText.isNotEmpty()) {
            summary = summarizeWithDoubao(transcribedText)
        }
        
        return Pair(transcribedText, summary)
    }

    /**
     * 构建 Prompt
     */
    private fun buildPrompt(text: String): String {
        return """请对以下通话内容进行智能总结：

$text

请按以下格式输出：
1. **通话主题**：一句话概括这次通话的主要内容
2. **关键信息**：提取重要信息点（如时间、地点、金额、事项等）
3. **待办事项**：列出需要跟进或处理的事项
4. **备注**：其他需要记录的信息"""
    }

    /**
     * 发送请求到 Groq Whisper API
     */
    private fun sendGroqWhisperRequest(audioFile: File): String {
        // 使用原生 HTTP 实现
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
            
            // 构建请求体
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
            
            // 读取响应
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                
                // 解析 JSON 响应
                val textStart = response.indexOf("\"text\":\"") + 8
                val textEnd = response.indexOf("\"", textStart)
                
                if (textStart > 7 && textEnd > textStart) {
                    return response.substring(textStart, textEnd)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                }
            } else {
                Log.e(TAG, "Whisper API 错误: $responseCode")
                val errorStream = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "错误详情: $errorStream")
            }
        } finally {
            conn.disconnect()
        }
        
        return ""
    }

    /**
     * 发送请求到豆包 API
     */
    private fun sendDoubaoRequest(prompt: String): String {
        val url = java.net.URL(DOUBAO_ENDPOINT)
        val conn = url.openConnection() as java.net.HttpURLConnection
        
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $DOUBAO_API_KEY")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            
            // 构建请求体
            val requestBody = """{
                "model": "$DOUBAO_MODEL",
                "messages": [
                    {"role": "user", "content": "$prompt"}
                ],
                "max_tokens": 2000,
                "temperature": 0.7
            }"""
            
            conn.outputStream.write(requestBody.toByteArray(Charsets.UTF_8))
            conn.outputStream.flush()
            conn.outputStream.close()
            
            // 读取响应
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                
                // 简单解析 JSON 提取 content
                val contentStart = response.indexOf("\"content\":\"") + 11
                val contentEnd = response.indexOf("\"", contentStart)
                
                if (contentStart > 10 && contentEnd > contentStart) {
                    return response.substring(contentStart, contentEnd)
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                }
            } else {
                Log.e(TAG, "豆包 API 错误: $responseCode")
                val errorStream = conn.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "错误详情: $errorStream")
            }
        } finally {
            conn.disconnect()
        }
        
        return ""
    }
}
