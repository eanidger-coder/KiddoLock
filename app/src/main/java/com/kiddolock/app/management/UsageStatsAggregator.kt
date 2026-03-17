package com.kiddolock.app.management

import android.content.Context
import android.util.Log

/**
 * Aggregates raw per-app usage data from PerAppTimeTracker into 
 * meaningful categories for AI analysis and parent reports.
 */
class UsageStatsAggregator(private val context: Context) {

    companion object {
        private const val TAG = "UsageStatsAggregator"

        val CATEGORY_MAP = mapOf(
            // Social Media
            "com.facebook.katana" to "Social",
            "com.instagram.android" to "Social",
            "com.twitter.android" to "Social",
            "com.zhiliaoapp.musically" to "Social",
            "com.snapchat.android" to "Social",
            
            // Video & Streaming
            "com.google.android.youtube" to "Entertainment",
            "tv.twitch.android.app" to "Entertainment",
            "com.netflix.mediaclient" to "Entertainment",
            
            // Games
            "com.king.candycrushsaga" to "Games",
            "com.roblox.client" to "Games",
            "com.mojang.minecraftpe" to "Games",
            
            // Education
            "com.duolingo" to "Education",
            "com.google.android.apps.classroom" to "Education",
            "org.khanacademy.android" to "Education",
            
            // Browsers
            "com.android.chrome" to "Browsing",
            "org.mozilla.firefox" to "Browsing"
        )

        const val CAT_OTHER = "Other"
    }

    data class CategoryUsage(
        val category: String,
        val totalMinutes: Int,
        val apps: List<String>
    )

    fun getAggregatedUsage(): List<CategoryUsage> {
        return try {
            val tracker = PerAppTimeTracker(context)
            val rawUsage = tracker.getAllUsageToday()
            val aggregation = mutableMapOf<String, MutableList<Pair<String, Int>>>()

            for ((pkg, minutes) in rawUsage) {
                if (pkg == null) continue
                val category = CATEGORY_MAP[pkg] ?: CAT_OTHER
                aggregation.getOrPut(category) { mutableListOf() }.add(pkg to minutes)
            }

            aggregation.map { (cat, list) ->
                CategoryUsage(
                    category = cat,
                    totalMinutes = list.sumOf { it.second },
                    apps = list.map { it.first }
                )
            }.sortedByDescending { it.totalMinutes }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to aggregate usage stats", e)
            emptyList()
        }
    }
}
