package com.kmk.slipstream.vpn.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private const val TAG = "SlipstreamVPN"
    private const val MAX_LOG_SIZE = 1000
    private const val LOG_FILE_NAME = "vpn_logs.txt"

    private val logBuffer = Collections.synchronizedList(LinkedList<String>())
    private val _logFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logFlow: SharedFlow<String> = _logFlow

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        loadLogsFromFile()
        i("SYSTEM", "Logger initialized. Previous logs loaded.")
    }

    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun w(tag: String, msg: String) = log("WARN", tag, msg)
    fun e(tag: String, msg: String) = log("ERROR", tag, msg)

    private fun log(level: String, tag: String, msg: String) {
        val timestamp = dateFormat.format(Date())
        val fullMsg = "[$timestamp] [$level] [$tag] $msg"
        
        // Log to Logcat
        when (level) {
            "INFO" -> Log.i(TAG, "[$tag] $msg")
            "WARN" -> Log.w(TAG, "[$tag] $msg")
            "ERROR" -> Log.e(TAG, "[$tag] $msg")
        }

        // Add to in-memory buffer
        synchronized(logBuffer) {
            if (logBuffer.size >= MAX_LOG_SIZE) {
                logBuffer.removeFirst()
            }
            logBuffer.add(fullMsg)
        }

        // Emit to flow for UI
        _logFlow.tryEmit(fullMsg)

        // Save to file (in a real app this should be optimized, but for debugging it's fine)
        appendToFile(fullMsg)
    }

    private fun appendToFile(msg: String) {
        try {
            logFile?.appendText("$msg\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    private fun loadLogsFromFile() {
        try {
            if (logFile?.exists() == true) {
                val lines = logFile!!.readLines().takeLast(MAX_LOG_SIZE)
                synchronized(logBuffer) {
                    logBuffer.clear()
                    logBuffer.addAll(lines)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load logs from file", e)
        }
    }

    fun getLogs(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

    fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.clear()
        }
        try {
            logFile?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete log file", e)
        }
        i("SYSTEM", "Logs cleared.")
    }
}
