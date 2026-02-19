package com.example.registarappointmentsystem

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.registarappointmentsystem.ui.auth.LoginActivity

/**
 * Entry Activity that simply redirects to LoginActivity.
 * This keeps MainActivity as a thin launcher while the real UI lives in feature Activities.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Navigate directly to the login flow for now.
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}