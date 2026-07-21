package com.example.registarappointmentsystem.utils

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("session_timeout", Context.MODE_PRIVATE)
    private val INACTIVITY_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
    private val LAST_ACTIVITY_KEY = "last_activity_time"

    /**
     * Update the last activity timestamp to now
     */
    fun updateLastActivityTime() {
        prefs.edit().putLong(LAST_ACTIVITY_KEY, System.currentTimeMillis()).apply()
    }

    /**
     * Check if the session has expired due to inactivity
     * @return true if session has expired, false if still valid
     */
    fun isSessionExpired(): Boolean {
        val lastActivity = prefs.getLong(LAST_ACTIVITY_KEY, 0L)
        if (lastActivity == 0L) return false // No previous session
        
        val elapsedTime = System.currentTimeMillis() - lastActivity
        return elapsedTime > INACTIVITY_TIMEOUT_MS
    }

    /**
     * Get remaining time in seconds before session expires
     */
    fun getRemainingTimeSeconds(): Long {
        val lastActivity = prefs.getLong(LAST_ACTIVITY_KEY, 0L)
        if (lastActivity == 0L) return INACTIVITY_TIMEOUT_MS / 1000
        
        val elapsedTime = System.currentTimeMillis() - lastActivity
        val remaining = (INACTIVITY_TIMEOUT_MS - elapsedTime) / 1000
        return if (remaining > 0) remaining else 0
    }

    /**
     * Clear the session timeout tracking (call on logout)
     */
    fun clearSessionTimeout() {
        prefs.edit().remove(LAST_ACTIVITY_KEY).apply()
    }
}
