package com.kiddolock.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kiddolock.app.R
import com.kiddolock.app.SafeLockApp
import com.kiddolock.app.content.ContentPreferences
import com.kiddolock.app.content.core.ContentClassifier
import com.kiddolock.app.content.entities.BlacklistedChannel
import com.kiddolock.app.content.entities.BlacklistedKeyword
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * ContentFilterActivity — parent-facing screen for configuring the SafeKids
 * content-filter sub-module of SafeLock.
 *
 * Features:
 *   - Toggle the filter on/off
 *   - Sensitivity level selector (strict / balanced / relaxed)
 *   - Add / remove blacklisted channels and keywords
 *   - View total block count
 *
 * Reached from AdminActivity's "Content Filter" tab.
 */
class ContentFilterActivity : AppCompatActivity() {

    private lateinit var prefs: ContentPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_filter)

        prefs = ContentPreferences(this)

        bindToggle()
        bindSensitivity()
        bindBlockCount()
        bindChannelsList()
        bindKeywordsList()
        bindAddButtons()
    }

    private fun bindToggle() {
        val toggle = findViewById<Switch>(R.id.switchContentFilter)
        toggle.isChecked = prefs.contentFilterEnabled
        toggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.contentFilterEnabled = isChecked
        }
    }

    private fun bindSensitivity() {
        val group = findViewById<RadioGroup>(R.id.groupSensitivity)
        val initial = when (prefs.sensitivityLevel) {
            ContentClassifier.SensitivityLevel.STRICT -> R.id.radioStrict
            ContentClassifier.SensitivityLevel.BALANCED -> R.id.radioBalanced
            ContentClassifier.SensitivityLevel.RELAXED -> R.id.radioRelaxed
        }
        group.check(initial)
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.sensitivityLevel = when (checkedId) {
                R.id.radioStrict -> ContentClassifier.SensitivityLevel.STRICT
                R.id.radioRelaxed -> ContentClassifier.SensitivityLevel.RELAXED
                else -> ContentClassifier.SensitivityLevel.BALANCED
            }
        }
    }

    private fun bindBlockCount() {
        val counter = findViewById<TextView>(R.id.tvBlockCount)
        counter.text = getString(R.string.content_filter_blocks_count, prefs.lifetimeBlockCount)
    }

    private fun bindChannelsList() {
        val container = findViewById<LinearLayout>(R.id.listChannels)
        val dao = SafeLockApp.get().database.blacklistDao()
        lifecycleScope.launch {
            dao.getAllChannels().collectLatest { channels ->
                container.removeAllViews()
                channels.forEach { channel ->
                    container.addView(rowView(channel.channelName) {
                        lifecycleScope.launch(Dispatchers.IO) { dao.deleteChannel(channel) }
                    })
                }
            }
        }
    }

    private fun bindKeywordsList() {
        val container = findViewById<LinearLayout>(R.id.listKeywords)
        val dao = SafeLockApp.get().database.blacklistDao()
        lifecycleScope.launch {
            dao.getAllKeywords().collectLatest { keywords ->
                container.removeAllViews()
                keywords.forEach { keyword ->
                    container.addView(rowView(keyword.keyword) {
                        lifecycleScope.launch(Dispatchers.IO) { dao.deleteKeyword(keyword) }
                    })
                }
            }
        }
    }

    private fun rowView(label: String, onRemove: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = "• $label   ✕"
        tv.textSize = 16f
        tv.setTextColor(getColor(R.color.safelock_text_primary))
        tv.setPadding(24, 16, 24, 16)
        tv.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.content_filter_remove_confirm, label))
                .setPositiveButton(android.R.string.ok) { _, _ -> onRemove() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        return tv
    }

    private fun bindAddButtons() {
        val dao = SafeLockApp.get().database.blacklistDao()

        findViewById<MaterialButton>(R.id.btnAddChannel).setOnClickListener {
            promptForText(getString(R.string.content_filter_add_channel)) { value ->
                lifecycleScope.launch(Dispatchers.IO) {
                    dao.insertChannel(BlacklistedChannel(channelName = value))
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnAddKeyword).setOnClickListener {
            promptForText(getString(R.string.content_filter_add_keyword)) { value ->
                lifecycleScope.launch(Dispatchers.IO) {
                    dao.insertKeyword(BlacklistedKeyword(keyword = value))
                }
            }
        }
    }

    private fun promptForText(title: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) onConfirm(value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
