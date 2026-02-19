package com.example.registarappointmentsystem.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.ui.dashboard.StudentDashboardActivity
import com.example.registarappointmentsystem.ui.requests.RequestCredentialsActivity
import com.example.registarappointmentsystem.ui.auth.LoginActivity
import com.google.android.material.navigation.NavigationView

class ProfileActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val drawerLayout: DrawerLayout = findViewById(R.id.drawerLayoutProfile)
        val buttonMenu: ImageButton = findViewById(R.id.buttonMenuProfile)
        val buttonBack: ImageButton = findViewById(R.id.buttonBackProfile)
        val navigationView: NavigationView = findViewById(R.id.navigationViewProfile)

        buttonMenu.setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }

        buttonBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        navigationView.setNavigationItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    startActivity(Intent(this, StudentDashboardActivity::class.java))
                }
                R.id.nav_request_credentials -> {
                    startActivity(Intent(this, RequestCredentialsActivity::class.java))
                }
                R.id.nav_profile -> {
                    // Already on profile, do nothing.
                }
                R.id.nav_logout -> {
                    val intent = Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                }
            }
            drawerLayout.closeDrawer(Gravity.START)
            true
        }
    }
}

