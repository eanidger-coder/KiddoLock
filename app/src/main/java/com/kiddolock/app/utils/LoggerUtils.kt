package com.kiddolock.app.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LoggerUtils {
    private const val LOG_FILE_NAME = "kiddolock_debug.log"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun log(context: Context, message: String) {
        try {
            val logFile = File(context.getExternalFilesDir(null), LOG_FILE_NAME)
            val timestamp = dateFormat.format(Date())
            val logEntry = "[$timestamp] $message\n"
            
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
            }
            Log.d("KiddoLockLogger", message)
        } catch (e: Exception) {
            Log.e("KiddoLockLogger", "Failed to write log: ${e.message}")
        }
    }

    fun clearLog(context: Context) {
        val logFile = File(context.getExternalFilesDir(null), LOG_FILE_NAME)
        if (logFile.exists()) {
            logFile.delete()
        }
    }
}
