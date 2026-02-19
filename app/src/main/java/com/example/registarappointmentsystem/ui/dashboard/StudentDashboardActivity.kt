package com.example.registarappointmentsystem.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import com.example.registarappointmentsystem.R
import com.example.registarappointmentsystem.ui.requests.RequestCredentialsActivity
import com.example.registarappointmentsystem.ui.profile.ProfileActivity
import com.example.registarappointmentsystem.ui.auth.LoginActivity
import com.google.android.material.navigation.NavigationView

class StudentDashboardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_dashboard)

        val drawerLayout: DrawerLayout = findViewById(R.id.drawerLayout)
        val buttonMenu: ImageButton = findViewById(R.id.buttonMenu)
        val navigationView: NavigationView = findViewById(R.id.navigationView)

        buttonMenu.setOnClickListener {
            drawerLayout.openDrawer(Gravity.START)
        }

        navigationView.setNavigationItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    // Already on dashboard, just close the drawer.
                }
                R.id.nav_request_credentials -> {
                    startActivity(Intent(this, RequestCredentialsActivity::class.java))
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, ProfileActivity::class.java))
                }
                R.id.nav_logout -> {
                    // Placeholder for future PHP-backed logout.
                    // When backend is ready, call AuthViewModel.logout()
                    // before navigating back to LoginActivity.
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