package com.kiddolock.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.kiddolock.app.R

/**
 * BlockedActivity — shown to the child when violent / blacklisted content is
 * detected by the SafeKids content-filter. Designed to be non-scary.
 *
 * Parent override routes through AdminPinActivity (inherited from KiddoLock).
 */
class BlockedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked)

        val blockedReason = intent.getStringExtra("blocked_reason") ?: "keyword"
        val sourcePackage = intent.getStringExtra("blocked_source_package")

        val messageView = findViewById<TextView>(R.id.tvBlockMessage)
        messageView.text = when (blockedReason) {
            "escalation" -> getString(R.string.block_message_escalation)
            "blacklist" -> getString(R.string.block_message_blacklist)
            else -> getString(R.string.block_message)
        }

        findViewById<Button>(R.id.btnBackToSafe).setOnClickListener {
            // Return the child to YouTube's home/feed (NOT to the Android
            // launcher). The AccessibilityService already pressed BACK on
            // the player before we were shown, so YouTube's launcher intent
            // lands them on the feed/search rather than on the blocked
            // video.
            val ytIntent = sourcePackage
                ?.let { packageManager.getLaunchIntentForPackage(it) }
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }

            try {
                if (ytIntent != null) {
                    startActivity(ytIntent)
                } else {
                    // YouTube isn't installed (or we didn't receive a package) —
                    // fall back to the launcher so the child isn't stranded.
                    startActivity(
                        Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                }
            } catch (e: android.content.ActivityNotFoundException) {
                android.util.Log.e("BlockedActivity", "No target activity to return to", e)
            }
            finish()
        }

        findViewById<Button>(R.id.btnParentUnlock).setOnClickListener {
            startActivity(Intent(this, AdminPinActivity::class.java))
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Child must use the buttons — swallow the back press.
            }
        })
    }
}
