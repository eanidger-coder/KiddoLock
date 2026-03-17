package com.kiddolock.app.management

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.kiddolock.app.services.TimeScheduler
import com.kiddolock.app.utils.LoggerUtils
import com.kiddolock.app.utils.Prefs

/**
 * Shared logic for executing remote commands.
 * Used by both HeartbeatWorker (polling) and RealTimeMessenger (WebSockets).
 */
class RemoteCommandHandler(private val context: Context) {
    private val gson = Gson()

    fun execute(type: String?, payload: String?) {
        val prefs = Prefs(context)
        when (type) {
            "DISABLE_PROTECTION" -> {
                prefs.disable_all_filters = true
                LoggerUtils.log(context, "Remote Command: Protection Suspended")
            }
            "EMERGENCY_KILL_SWITCH" -> {
                prefs.disable_all_filters = true
                prefs.bypass_guard_enabled = false
                prefs.certified_uninstall_in_progress = true
                prefs.emergency_bypass_until = System.currentTimeMillis() + 3600_000 // 1 hour bypass
                
                // Broadcase emergency disable to hide overlay and reset state
                val intent = Intent("com.kiddolock.app.EMERGENCY_DISABLE").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
                
                LoggerUtils.log(context, "CRITICAL: Remote Emergency Kill Switch Activated")
                Log.w("RemoteCommandHandler", "EMERGENCY_KILL_SWITCH TRIGGERED FROM SERVER")
            }
            "ENABLE_PROTECTION" -> {
                prefs.disable_all_filters = false
                LoggerUtils.log(context, "Remote Command: Protection Reactivated")
            }
            "UPDATE_SCHEDULE" -> {
                payload?.let {
                    try {
                        val scheduleMap = gson.fromJson(it, Map::class.java)
                        val scheduler = TimeScheduler(context)
                        val config = TimeScheduler.ScheduleConfig(
                            quietHoursEnabled = (scheduleMap["quietHoursEnabled"] as? Boolean) ?: true,
                            quietHoursStart = (scheduleMap["quietHoursStart"] as? Double)?.toInt() ?: 22,
                            quietHoursEnd = (scheduleMap["quietHoursEnd"] as? Double)?.toInt() ?: 6,
                            dailyTimeLimitEnabled = (scheduleMap["dailyTimeLimitEnabled"] as? Boolean) ?: true,
                            dailyTimeLimitMinutes = (scheduleMap["dailyTimeLimitMinutes"] as? Double)?.toInt() ?: 120
                        )
                        scheduler.saveConfig(config)
                        LoggerUtils.log(context, "Remote Command: Schedule Updated")
                    } catch (e: Exception) {
                        Log.e("RemoteCommandHandler", "Error parsing schedule payload", e)
                    }
                }
            }
            "BLOCK_PACKAGE" -> {
                payload?.let {
                    try {
                        val data = gson.fromJson(it, Map::class.java)
                        val pkg = data["package"] as? String
                        if (pkg != null) {
                            AppManager(context).blacklistApp(pkg)
                            LoggerUtils.log(context, "Remote Command: Blocked $pkg")
                        }
                        Unit
                    } catch (e: Exception) {
                        Log.e("RemoteCommandHandler", "Error parsing block payload", e)
                    }
                }
                Unit
            }
            "UNBLOCK_PACKAGE" -> {
                payload?.let {
                    try {
                        val data = gson.fromJson(it, Map::class.java)
                        val pkg = data["package"] as? String
                        if (pkg != null) {
                            AppManager(context).whitelistApp(pkg)
                            LoggerUtils.log(context, "Remote Command: Unblocked $pkg")
                        }
                        Unit
                    } catch (e: Exception) {
                        Log.e("RemoteCommandHandler", "Error parsing unblock payload", e)
                    }
                }
                Unit
            }
        }
    }
}
