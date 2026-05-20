package com.autorecordai

import android.util.Log
import kotlin.Pair

object AIProcessor {
    private const val TAG = "AIProcessor"

    /**
     * 语音转文字（简化版，先返回空字符串测试编译）
     */
    fun transcribeAudio(filePath: String): String {
        Log.d(TAG, "transcribeAudio: $filePath")
        return ""
    }

    /**
     * AI 总结（简化版）
     */
    fun summarizeWithDoubao(text: String): String {
        Log.d(TAG, "summarizeWithDoubao")
        return ""
    }

    /**
     * 完整处理：录音文件 → 转文字 → AI 总结
     */
    fun processAudioFile(filePath: String): Pair<String, String> {
        Log.d(TAG, "processAudioFile: $filePath")
        return Pair("", "")
    }
}
