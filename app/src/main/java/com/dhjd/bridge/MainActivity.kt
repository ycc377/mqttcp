package com.dhjd.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var idText: TextView
    private lateinit var startBtn: Button
    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        idText = TextView(this).apply {
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(16, 32, 16, 8)
        }

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(16, 8, 16, 24)
        }

        startBtn = Button(this).apply {
            text = "启动服务"
            setOnClickListener { toggleService() }
        }

        val linear = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            addView(idText)
            addView(statusText)
            addView(startBtn)
        }

        setContentView(linear)
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        idText.text = "设备ID:\n${genID()}"
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }

    private fun toggleService() {
        if (isRunning) {
            stopService(Intent(this, BridgeService::class.java))
            isRunning = false
            startBtn.text = "启动服务"
            statusText.text = "已停止"
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(Intent(this, BridgeService::class.java))
            else
                startService(Intent(this, BridgeService::class.java))
            isRunning = true
            startBtn.text = "停止服务"
            statusText.text = "正在启动..."
            Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show()
        }
    }

    private fun genID(): String {
        return try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val m = en.nextElement().hardwareAddress ?: continue
                if (m.isNotEmpty()) {
                    val d = java.security.MessageDigest.getInstance("MD5").digest(m)
                    var n = (d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8) or
                            ((d[2].toInt() and 0xFF) shl 16) or ((d[3].toInt() and 0xFF) shl 24)
                    if (n < 0) n = -n
                    return String.format("%09d", n % 1000000000)
                }
            }
            "000000001"
        } catch (_: Exception) { "000000001" }
    }
}
