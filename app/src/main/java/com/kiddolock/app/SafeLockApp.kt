package com.kiddolock.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kiddolock.app.management.AdminPinManager
import com.kiddolock.app.management.SettingsSyncManager
import com.kiddolock.app.content.SafeLockDatabase

/**
 * SafeLock Application - merged from KiddoLock + SafeKids.
 *
 * Initializes:
 *  - Cloud settings sync (KiddoLock)
 *  - Room DB for YouTube content filter history (SafeKids)
 *  - Lifecycle observer to clear parent PIN session on background
 */
class SafeLockApp : Application() {

    companion object {
        @Volatile
        private var instance: SafeLockApp? = null
        fun get(): SafeLockApp = instance!!
    }

    val database: SafeLockDatabase by lazy {
        SafeLockDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initial cloud sync to restore settings if missing/reinstalled
        SettingsSyncManager(this).syncSettingsOnStart()

        // Warm up the content-filter DB
        database.openHelper.writableDatabase

        // Clear the parent PIN session when app goes to background
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AdminPinManager.clearSession()
            }
        })
    }
}
