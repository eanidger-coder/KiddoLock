package com.kiddolock.app
import android.content.Context
import android.util.Log

fun debugDumpPrefs(context: Context) {
    val prefs = context.getSharedPreferences("kiddolock_schedule_prefs", Context.MODE_PRIVATE)
    Log.d("KiddoDebug", "daily_time_limit_enabled: ${prefs.getBoolean("daily_time_limit_enabled", false)}")
    Log.d("KiddoDebug", "daily_time_limit_minutes: ${prefs.getInt("daily_time_limit_minutes", -1)}")
}
