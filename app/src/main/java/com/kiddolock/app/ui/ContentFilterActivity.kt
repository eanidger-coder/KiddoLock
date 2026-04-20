package com.kiddolock.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.kiddolock.app.R
import com.kiddolock.app.content.ContentPreferences
import com.kiddolock.app.content.SafeLockDatabase
import com.kiddolock.app.content.core.ContentClassifier
import com.kiddolock.app.content.entities.BlacklistedChannel
import com.kiddolock.app.content.entities.BlacklistedKeyword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ContentFilterActivity — Parent-facing configuration UI for the SafeKids
 * content filter sub-module inside SafeLock.
 *
 * Features:
 *  - Enable/disable toggle for the YouTube filter engine
 *  - Sensitivity selector (STRICT / BALANCED / RELAXED)
 *  - Lifetime block count display
 *  - Managed list of blacklisted channels
 *  - Managed list of blacklisted keywords
 *
 * Called from AdminActivity after PIN verification, so this screen itself
 * does not re-verify the PIN.
 */
class ContentFilterActivity : AppCompatActivity() {

    private lateinit var prefs: ContentPreferences
    private lateinit var db: SafeLockDatabase

    private lateinit var switchEnabled: SwitchCompat
    private lateinit var tvFilterStatus: TextView
    private lateinit var tvBlockCount: TextView
    private lateinit var rgSensitivity: RadioGroup
    private lateinit var rbStrict: RadioButton
    private lateinit var rbBalanced: RadioButton
    private lateinit var rbRelaxed: RadioButton

    private lateinit var etNewChannel: EditText
    private lateinit var btnAddChannel: MaterialButton
    private lateinit var containerChannels: LinearLayout
    private lateinit var tvChannelsEmpty: TextView

    private lateinit var etNewKeyword: EditText
    private lateinit var btnAddKeyword: MaterialButton
    private lateinit var containerKeywords: LinearLayout
    private lateinit var tvKeywordsEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_filter)

        prefs = ContentPreferences(this)
        db = SafeLockDatabase.getInstance(this)

        bindViews()
        applyInitialState()
        wireListeners()
        observeBlacklists()
    }

    private fun bindViews() {
        switchEnabled = findViewById(R.id.switchFilterEnabled)
        tvFilterStatus = findViewById(R.id.tvFilterStatus)
        tvBlockCount = findViewById(R.id.tvBlockCount)
        rgSensitivity = findViewById(R.id.rgSensitivity)
        rbStrict = findViewById(R.id.rbStrict)
        rbBalanced = findViewById(R.id.rbBalanced)
        rbRelaxed = findViewById(R.id.rbRelaxed)

        etNewChannel = findViewById(R.id.etNewChannel)
        btnAddChannel = findViewById(R.id.btnAddChannel)
        containerChannels = findViewById(R.id.containerChannels)
        tvChannelsEmpty = findViewById(R.id.tvChannelsEmpty)

        etNewKeyword = findViewById(R.id.etNewKeyword)
        btnAddKeyword = findViewById(R.id.btnAddKeyword)
        containerKeywords = findViewById(R.id.containerKeywords)
        tvKeywordsEmpty = findViewById(R.id.tvKeywordsEmpty)
    }

    private fun applyInitialState() {
        val enabled = prefs.contentFilterEnabled
        switchEnabled.isChecked = enabled
        tvFilterStatus.setText(
            if (enabled) R.string.content_filter_enabled
            else R.string.content_filter_disabled
        )

        when (prefs.sensitivityLevel) {
            ContentClassifier.SensitivityLevel.STRICT -> rbStrict.isChecked = true
            ContentClassifier.SensitivityLevel.BALANCED -> rbBalanced.isChecked = true
            ContentClassifier.SensitivityLevel.RELAXED -> rbRelaxed.isChecked = true
        }

        tvBlockCount.text = getString(
            R.string.content_filter_blocks_count,
            prefs.lifetimeBlockCount
        )
    }

    private fun wireListeners() {
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.contentFilterEnabled = isChecked
            tvFilterStatus.setText(
                if (isChecked) R.string.content_filter_enabled
                else R.string.content_filter_disabled
            )
        }

        rgSensitivity.setOnCheckedChangeListener { _, checkedId ->
            prefs.sensitivityLevel = when (checkedId) {
                R.id.rbStrict -> ContentClassifier.SensitivityLevel.STRICT
                R.id.rbRelaxed -> ContentClassifier.SensitivityLevel.RELAXED
                else -> ContentClassifier.SensitivityLevel.BALANCED
            }
        }

        btnAddChannel.setOnClickListener {
            val name = etNewChannel.text.toString().trim()
            if (name.isEmpty()) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                db.blacklistDao().insertChannel(BlacklistedChannel(channelName = name))
                withContext(Dispatchers.Main) { etNewChannel.text.clear() }
            }
        }

        btnAddKeyword.setOnClickListener {
            val kw = etNewKeyword.text.toString().trim()
            if (kw.isEmpty()) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                db.blacklistDao().insertKeyword(BlacklistedKeyword(keyword = kw))
                withContext(Dispatchers.Main) { etNewKeyword.text.clear() }
            }
        }
    }

    private fun observeBlacklists() {
        lifecycleScope.launch {
            db.blacklistDao().getAllChannels().collectLatest { channels ->
                renderChannels(channels)
            }
        }
        lifecycleScope.launch {
            db.blacklistDao().getAllKeywords().collectLatest { keywords ->
                renderKeywords(keywords)
            }
        }
    }

    private fun renderChannels(channels: List<BlacklistedChannel>) {
        containerChannels.removeAllViews()
        if (channels.isEmpty()) {
            tvChannelsEmpty.visibility = View.VISIBLE
            return
        }
        tvChannelsEmpty.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (channel in channels) {
            val row = inflater.inflate(R.layout.row_blacklist_item, containerChannels, false)
            row.findViewById<TextView>(R.id.tvItemLabel).text = channel.channelName
            row.findViewById<View>(R.id.btnRemoveItem).setOnClickListener {
                confirmRemoveChannel(channel)
            }
            containerChannels.addView(row)
        }
    }

    private fun renderKeywords(keywords: List<BlacklistedKeyword>) {
        containerKeywords.removeAllViews()
        if (keywords.isEmpty()) {
            tvKeywordsEmpty.visibility = View.VISIBLE
            return
        }
        tvKeywordsEmpty.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (kw in keywords) {
            val row = inflater.inflate(R.layout.row_blacklist_item, containerKeywords, false)
            row.findViewById<TextView>(R.id.tvItemLabel).text = kw.keyword
            row.findViewById<View>(R.id.btnRemoveItem).setOnClickListener {
                confirmRemoveKeyword(kw)
            }
            containerKeywords.addView(row)
        }
    }

    private fun confirmRemoveChannel(channel: BlacklistedChannel) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.content_filter_remove_confirm, channel.channelName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.blacklistDao().deleteChannel(channel)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveKeyword(keyword: BlacklistedKeyword) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.content_filter_remove_confirm, keyword.keyword))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.blacklistDao().deleteKeyword(keyword)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
