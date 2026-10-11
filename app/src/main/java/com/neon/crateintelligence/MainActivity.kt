package com.neon.crateintelligence

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.neon.crateintelligence.service.FloatingCrateHudService

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for alerts", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestNotificationPermission()

        val btnToggleHud = findViewById<Button>(R.id.btnToggleHud)

        btnToggleHud.setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
            } else {
                toggleHudService(btnToggleHud)
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please grant 'Display over other apps' permission", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun toggleHudService(button: Button) {
        val serviceIntent = Intent(this, FloatingCrateHudService::class.java)

        if (!isServiceRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isServiceRunning = true
            button.text = "STOP FLOATING HUD"
            button.setBackgroundColor(android.graphics.Color.parseColor("#DC2626")) // Red
            Toast.makeText(this, "Neon HUD Launched! Open Game Now", Toast.LENGTH_SHORT).show()
        } else {
            stopService(serviceIntent)
            isServiceRunning = false
            button.text = "LAUNCH FLOATING HUD"
            button.setBackgroundColor(android.graphics.Color.parseColor("#2563EB")) // Blue
            Toast.makeText(this, "Neon HUD Stopped", Toast.LENGTH_SHORT).show()
        }
    }
}
