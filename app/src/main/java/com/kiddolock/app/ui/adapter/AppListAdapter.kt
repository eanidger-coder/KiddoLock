package com.kiddolock.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.kiddolock.app.R
import com.kiddolock.app.management.AppManager

class AppListAdapter(
    private var apps: List<AppManager.AppInfo>,
    private val onAppToggled: (String, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {

    fun updateApps(newApps: List<AppManager.AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_admin, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount(): Int = apps.size

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        private val tvName: TextView = view.findViewById(R.id.tvAppName)
        private val tvPackage: TextView = view.findViewById(R.id.tvAppStatus)
        private val tvSystemBadge: TextView = view.findViewById(R.id.tvSystemBadge)
        private val swBlock: SwitchCompat = view.findViewById(R.id.swAppBlock)
        private val ivLockStatus: ImageView = view.findViewById(R.id.ivLockStatus)

        fun bind(app: AppManager.AppInfo) {
            tvName.text = app.appName
            tvPackage.text = app.packageName
            
            tvSystemBadge.visibility = if (app.isSystemProtected) View.VISIBLE else View.GONE
            
            // Set App Icon
            try {
                val icon = itemView.context.packageManager.getApplicationIcon(app.packageName)
                ivIcon.setImageDrawable(icon)
            } catch (e: Exception) {
                ivIcon.setImageResource(R.drawable.ic_launcher_foreground)
            }

            // Blocking switch
            swBlock.setOnCheckedChangeListener(null)
            swBlock.isChecked = app.isBlacklisted
            swBlock.isEnabled = !app.isSystemProtected
            
            swBlock.setOnCheckedChangeListener { _, isChecked ->
                onAppToggled(app.packageName, isChecked)
            }

            // Lock Indicator
            if (app.isBlacklisted) {
                ivLockStatus.setImageResource(R.drawable.ic_lock_closed)
                ivLockStatus.imageTintList = android.content.res.ColorStateList.valueOf(itemView.context.getColor(R.color.danger_red))
            } else {
                ivLockStatus.setImageResource(R.drawable.ic_lock_open)
                ivLockStatus.imageTintList = android.content.res.ColorStateList.valueOf(itemView.context.getColor(R.color.success_green))
            }

            if (app.isSystemProtected) {
                ivLockStatus.alpha = 0.3f
            } else {
                ivLockStatus.alpha = 1.0f
            }

            // Visual feedback for blocked apps
            if (app.isBlacklisted && !app.isSystemProtected) {
                itemView.setBackgroundColor(itemView.context.getColor(R.color.glass_white_10))
            } else {
                itemView.setBackgroundColor(0x00000000) // Transparent
            }

            // Visual feedback for system protected apps
            if (app.isSystemProtected) {
                itemView.alpha = 0.5f
            } else {
                itemView.alpha = 1.0f
            }
        }
    }
}
