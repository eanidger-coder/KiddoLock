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

        val messageView = findViewById<TextView>(R.id.tvBlockMessage)
        messageView.text = when (blockedReason) {
            "escalation" -> getString(R.string.block_message_escalation)
            "blacklist" -> getString(R.string.block_message_blacklist)
            else -> getString(R.string.block_message)
        }

        findViewById<Button>(R.id.btnBackToSafe).setOnClickListener {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
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
