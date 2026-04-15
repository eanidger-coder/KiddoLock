package com.kiddolock.app.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kiddolock.app.R

/**
 * Modern 2026 Help & About screen.
 * Provides user guidance on app features and technical info.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        supportActionBar?.apply {
            title = "עזרה ואודות"
            setDisplayHomeAsUpEnabled(true)
        }

        setupHelpContent()
    }

    private fun setupHelpContent() {
        // App Blocking Help
        findViewById<TextView>(R.id.tvHelpBlocking).text = 
            "חוסם אפליקציות שנבחרו ברשימה השחורה באופן מיידי."

        // Time Limits Help
        findViewById<TextView>(R.id.tvHelpLimits).text = 
            "מאפשר להגדיר מכסת זמן יומית לשימוש במכשיר. בסיום הזמן, המכשיר יינעל."

        // Bedtime Help
        findViewById<TextView>(R.id.tvHelpBedtime).text = 
            "קובע שעות שקטות (למשל בלילה) שבהן כל האפליקציות חסומות אוטומטית למעט שיחות חירום ושימוש מורשה."

        // AI Insights Help
        findViewById<TextView>(R.id.tvHelpAi).text = 
            "שימוש בתובנות AI מתקדמות לניתוח הרגלי שימוש, זיהוי אנומליות ומתן המלצות מבוססות נתונים להורים."

        // Device Admin / Anti-Uninstall Help (New)
        findViewById<TextView>(R.id.tvHelpAdmin).text = 
            "הפעלת מנהל מכשיר מונעת מהילד להסיר את האפליקציה או לעקוף את ההגבלות ללא קוד הורים."

        // Parent PIN Help (New)
        findViewById<TextView>(R.id.tvHelpPin).text = 
            "קוד הורים סודי המאפשר לבטל חסימות באופן זמני, לשנות הגדרות ולפתוח את המכשיר במקרה הצורך."

        // About Info
        val version = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            pInfo.versionName
        } catch (e: Exception) {
            "1.0.0"
        }
        
        findViewById<TextView>(R.id.tvAboutApp).text = 
            "SafeLock v$version\n" +
            "פתרון חכם ובטוח לניהול זמן מסך.\n" +
            "נבנה עבור הדור הבא."
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
