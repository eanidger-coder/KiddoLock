package com.kiddolock.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
        bindDefaultKeywords()
    }

    /**
     * Render the hard-coded keyword database so the parent can review every
     * built-in word and un-block individual items (e.g. "battle" when the
     * child is watching Pokémon). Toggling a word writes to
     * [ContentPreferences.allowedOverrides]; [ContentClassifier] skips
     * anything present in that set at classify time.
     */
    private fun bindDefaultKeywords() {
        val container = findViewById<LinearLayout>(R.id.listDefaults) ?: return
        container.removeAllViews()

        val classifier = ContentClassifier()
        val defaults: List<Pair<String, List<String>>> =
            classifier.defaultKeywords().map { (cat, words) ->
                categoryLabel(cat) to words
            } + listOf(
                getString(R.string.content_filter_category_violent_shows) to classifier.defaultViolentShows()
            )

        defaults.forEach { (title, words) ->
            container.addView(buildCategorySection(title, words))
        }
    }

    private fun categoryLabel(category: ContentClassifier.Category): String = when (category) {
        ContentClassifier.Category.VIOLENCE_PHYSICAL ->
            getString(R.string.content_filter_category_violence_physical)
        ContentClassifier.Category.VIOLENCE_VERBAL ->
            getString(R.string.content_filter_category_violence_verbal)
        ContentClassifier.Category.HORROR_KIDS ->
            getString(R.string.content_filter_category_horror_kids)
        ContentClassifier.Category.ELSAGATE ->
            getString(R.string.content_filter_category_elsagate)
        ContentClassifier.Category.WEAPONS ->
            getString(R.string.content_filter_category_weapons)
        ContentClassifier.Category.DARK_THEMES ->
            getString(R.string.content_filter_category_dark_themes)
        ContentClassifier.Category.DANGEROUS_ACTIVITIES ->
            getString(R.string.content_filter_category_dangerous_activities)
    }

    private fun buildCategorySection(title: String, words: List<String>): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }

        val header = TextView(this).apply {
            text = "▸  $title  (${words.size})"
            textSize = 16f
            setTextColor(getColor(R.color.safelock_text_primary))
            setPadding(8, 12, 8, 12)
        }
        wrap.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(8, 0, 8, 0)
        }
        words.forEach { word -> body.addView(buildWordRow(word)) }
        wrap.addView(body)

        header.setOnClickListener {
            val collapsed = body.visibility == View.GONE
            body.visibility = if (collapsed) View.VISIBLE else View.GONE
            header.text = (if (collapsed) "▾  " else "▸  ") + title + "  (${words.size})"
        }
        return wrap
    }

    private fun buildWordRow(word: String): View {
        val row = TextView(this).apply {
            textSize = 15f
            setPadding(24, 10, 24, 10)
            gravity = Gravity.START
        }
        applyWordRowState(row, word)
        row.setOnClickListener {
            val current = prefs.allowedOverrides
            val key = word.lowercase().trim()
            prefs.allowedOverrides = if (key in current) current - key else current + key
            applyWordRowState(row, word)
        }
        return row
    }

    private fun applyWordRowState(row: TextView, word: String) {
        val allowed = word.lowercase().trim() in prefs.allowedOverrides
        row.text = if (allowed) "✗  $word  (מותר)" else "✓  $word  (חסום)"
        row.setTextColor(
            getColor(if (allowed) R.color.safelock_text_secondary else R.color.safelock_text_primary)
        )
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
