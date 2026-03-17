package com.kiddolock.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kiddolock.app.management.AdminPinManager

class KiddoLockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initial sync from cloud to restore settings if missing/reinstalled
        com.kiddolock.app.management.SettingsSyncManager(this).syncSettingsOnStart()

        // Register observer to lock the app when it goes into the background
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                // App backgrounded - clear the PIN session
                AdminPinManager.clearSession()
            }
        })
    }
}
