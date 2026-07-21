package com.example.registarappointmentsystem

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.registarappointmentsystem.ui.auth.LoginActivity
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.ui.profile.ProfileActivity
import com.example.registarappointmentsystem.utils.BackgroundSyncScheduler

/**
 * Entry Activity that simply redirects to LoginActivity.
 * This keeps MainActivity as a thin launcher while the real UI lives in feature Activities.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authPrefs = getSharedPreferences("auth", MODE_PRIVATE)
        val isLoggedIn = authPrefs.getInt("user_id", -1) > 0
        val mustChangePassword = authPrefs.getBoolean("must_change_password", false)

        if (isLoggedIn) {
            BackgroundSyncScheduler.start(this)
            if (mustChangePassword) {
                startActivity(Intent(this, ProfileActivity::class.java).apply {
                    putExtra("forcePasswordChange", true)
                })
            } else {
                startActivity(Intent(this, StudentDashboardActivity::class.java))
            }
        } else {
            BackgroundSyncScheduler.stop(this)
            startActivity(Intent(this, LoginActivity::class.java))
        }
        finish()
    }
}