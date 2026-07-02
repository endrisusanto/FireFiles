package dev.firefiles.bridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("smb", MODE_PRIVATE)
        val host = input("Ubuntu host/IP", prefs.getString("host", "") ?: "")
        val share = input("Share", prefs.getString("share", "FirmwareInbox") ?: "FirmwareInbox")
        val user = input("Username", prefs.getString("user", "firmware") ?: "firmware")
        val pass = input("Password", prefs.getString("pass", "") ?: "")
        val folder = input("Remote folder", prefs.getString("folder", "") ?: "")
        val monitor = input("Monitor URL", prefs.getString("monitor", "https://files.endrisusanto.my.id/api/heartbeat") ?: "https://files.endrisusanto.my.id/api/heartbeat")
        val token = input("Monitor token", prefs.getString("token", "change-me") ?: "change-me")
        val status = TextView(this)
        status.text = "Folder: /sdcard/FirmwareBridge"

        // ponytail: derive base URL dari heartbeat URL, fetch SMB config
        val fetchBtn = Button(this)
        fetchBtn.text = "Fetch from Server"
        fetchBtn.setOnClickListener {
            val baseUrl = monitor.text.toString()
                .substringBeforeLast("/api/")
                .trimEnd('/')
            val configUrl = "$baseUrl/api/smb-config"
            val tok = token.text.toString()
            status.text = "Fetching…"
            Thread {
                try {
                    val conn = URL(configUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("x-monitor-token", tok)
                    conn.connect()
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    // parse minimal JSON — cari key:"value"
                    fun get(key: String) = Regex(""""$key"\s*:\s*"([^"]*)"""")
                        .find(body)?.groupValues?.get(1) ?: ""
                    val h = get("host"); val sh = get("share")
                    val u = get("user"); val p = get("pass"); val f = get("folder")
                    runOnUiThread {
                        if (h.isNotBlank()) {
                            host.setText(h); share.setText(sh)
                            user.setText(u); pass.setText(p); folder.setText(f)
                            status.text = "✓ Config dari server terisi!"
                        } else {
                            status.text = "Server belum punya config (setup Linux app dulu)"
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Fetch gagal: ${e.message}" }
                }
            }.start()
        }

        val save = Button(this)
        save.text = "Save + Start"
        save.setOnClickListener {
            prefs.edit()
                .putString("host", host.text.toString())
                .putString("share", share.text.toString())
                .putString("user", user.text.toString())
                .putString("pass", pass.text.toString())
                .putString("folder", folder.text.toString().trim('/'))
                .putString("monitor", monitor.text.toString())
                .putString("token", token.text.toString())
                .apply()
            startForegroundService(Intent(this, UploadService::class.java))
            status.text = "Uploader running"
        }

        val access = Button(this)
        access.text = "Grant Files Access"
        access.setOnClickListener {
            if (!Environment.isExternalStorageManager()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        listOf(host, share, user, pass, folder, monitor, token, fetchBtn, access, save, status).forEach(layout::addView)
        setContentView(layout)
    }

    private fun input(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
    }
}
