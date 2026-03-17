package com.kiddolock.app.management

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppBlockManagerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockAppContext: Context

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Mock
    private lateinit var mockAppPrefs: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    @Mock
    private lateinit var mockAppEditor: SharedPreferences.Editor

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        `when`(mockContext.applicationContext).thenReturn(mockAppContext)
        
        // Mock SharedPreferences
        `when`(mockAppContext.getSharedPreferences("kiddolock_prefs", Context.MODE_PRIVATE)).thenReturn(mockPrefs)
        `when`(mockAppContext.getSharedPreferences("kiddolock_app_prefs", Context.MODE_PRIVATE)).thenReturn(mockAppPrefs)
        `when`(mockAppContext.getSharedPreferences("device_id_prefs", Context.MODE_PRIVATE)).thenReturn(mockPrefs)

        // Mock Editors
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockAppPrefs.edit()).thenReturn(mockAppEditor)
        
        // Setup fluent editor calls
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putLong(anyString(), anyLong())).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putStringSet(anyString(), any())).thenReturn(mockEditor)
        
        `when`(mockAppEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockAppEditor)
        `when`(mockAppEditor.putString(anyString(), anyString())).thenReturn(mockAppEditor)
        `when`(mockAppEditor.putLong(anyString(), anyLong())).thenReturn(mockAppEditor)
        `when`(mockAppEditor.putInt(anyString(), anyInt())).thenReturn(mockAppEditor)
        `when`(mockAppEditor.putStringSet(anyString(), any())).thenReturn(mockAppEditor)
        
        // Ensure AppBlockManager is in a clean state
        AppBlockManager.invalidateCache()
    }

    @Test
    fun testAppManagerCaching() {
        // Initial call should create a new manager
        val firstManager = AppBlockManager.getAppManager(mockContext)
        assertNotNull(firstManager)

        // Second call immediately should return the SAME cached instance
        val secondManager = AppBlockManager.getAppManager(mockContext)
        assertSame("Should return cached instance", firstManager, secondManager)
    }

    @Test
    fun testCacheInvalidation() {
        val firstManager = AppBlockManager.getAppManager(mockContext)
        
        AppBlockManager.invalidateCache()
        
        val thirdManager = AppBlockManager.getAppManager(mockContext)
        assertNotSame("Should return a new instance after invalidation", firstManager, thirdManager)
    }

    @Test
    fun testKidsModeMasterToggle() {
        // Mock KidsModeManager to be DISABLED
        `when`(mockAppContext.getSharedPreferences("kids_mode_prefs", Context.MODE_PRIVATE)).thenReturn(mockPrefs)
        `when`(mockPrefs.getBoolean("kids_mode_enabled", false)).thenReturn(false)
        
        // Even if an app is blacklisted, it should be ALLOWED if Kids Mode is OFF
        `when`(mockAppPrefs.getStringSet("blacklisted_apps", null)).thenReturn(setOf("com.instagram.android"))
        
        val isBlocked = AppBlockManager.isAppBlocked(mockContext, "com.instagram.android")
        assertFalse("App should be ALLOWED when Kids Mode is DISABLED", isBlocked)
    }

    @Test
    fun testKidsModeMasterToggleEnabled() {
        // Mock KidsModeManager to be ENABLED
        `when`(mockAppContext.getSharedPreferences("kids_mode_prefs", Context.MODE_PRIVATE)).thenReturn(mockPrefs)
        `when`(mockPrefs.getBoolean("kids_mode_enabled", false)).thenReturn(true)
        
        // If an app is blacklisted, it should be BLOCKED if Kids Mode is ON
        `when`(mockAppPrefs.getStringSet("blacklisted_apps", null)).thenReturn(setOf("com.instagram.android"))
        
        val isBlocked = AppBlockManager.isAppBlocked(mockContext, "com.instagram.android")
        assertTrue("App should be BLOCKED when Kids Mode is ENABLED and app is blacklisted", isBlocked)
    }
}
