package com.kiddolock.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kiddolock.app.R
import com.kiddolock.app.management.UsageStatsAggregator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modern 2026 Parent Insights screen summarizing child activity based on real usage data.
 */
class ParentInsightsActivity : AppCompatActivity() {
    
    private lateinit var tvUsageSummary: TextView
    private lateinit var tvPersonalTip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_insights)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "דוח שימוש יומי"
        
        tvUsageSummary = findViewById(R.id.tvUsageSummary)
        tvPersonalTip = findViewById(R.id.tvPersonalTip)
        
        loadDataReport()
    }

    private fun loadDataReport() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Aggregate usage statistics for today
                val aggregator = UsageStatsAggregator(this@ParentInsightsActivity)
                val usageMap = aggregator.getAggregatedUsage()
                
                // 2. Format a simple text report
                val reportBuilder = StringBuilder()
                if (usageMap.isEmpty()) {
                    reportBuilder.append("אין נתוני שימוש להיום עדיין.")
                } else {
                    reportBuilder.append("סיכום שימוש לפי קטגוריות:\n\n")
                    usageMap.forEach { usage ->
                        reportBuilder.append("• ${usage.category}: ${usage.totalMinutes} דקות\n")
                    }
                }
                
                val reportText = reportBuilder.toString()
                
                // 3. Update UI on main thread
                withContext(Dispatchers.Main) {
                    tvUsageSummary.text = reportText
                    
                    // Simple logic for personal tip based on most used category
                    val topCategory = usageMap.maxByOrNull { it.totalMinutes }?.category ?: "Other"
                    tvPersonalTip.text = when (topCategory) {
                        "Social" -> "זמן רב ברשתות חברתיות. מומלץ לשים לב."
                        "Entertainment" -> "שימוש משמעותי באפליקציות בידור."
                        "Games" -> "זמן משחק מוגבר נרשם היום."
                        "Education" -> "כל הכבוד על ההשקעה בלמידה!"
                        "Browsing" -> "שימוש רב בדפדפן היום."
                        else -> "שימוש מאוזן באפליקציות."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvUsageSummary.text = "שגיאה בטעינת הנתונים: ${e.message}"
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
