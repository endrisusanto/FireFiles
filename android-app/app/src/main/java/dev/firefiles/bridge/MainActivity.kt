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

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("smb", MODE_PRIVATE)
        val host = input("Ubuntu host/IP", prefs.getString("host", "") ?: "")
        val share = input("Share", prefs.getString("share", "FirmwareInbox") ?: "FirmwareInbox")
        val user = input("Username", prefs.getString("user", "firmware") ?: "firmware")
        val pass = input("Password", prefs.getString("pass", "") ?: "")
        val folder = input("Remote folder", prefs.getString("folder", "") ?: "")
        val status = TextView(this)
        status.text = "Folder: /sdcard/FirmwareBridge"

        val save = Button(this)
        save.text = "Save + Start"
        save.setOnClickListener {
            prefs.edit()
                .putString("host", host.text.toString())
                .putString("share", share.text.toString())
                .putString("user", user.text.toString())
                .putString("pass", pass.text.toString())
                .putString("folder", folder.text.toString().trim('/'))
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
        listOf(host, share, user, pass, folder, access, save, status).forEach(layout::addView)
        setContentView(layout)
    }

    private fun input(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
    }
}
