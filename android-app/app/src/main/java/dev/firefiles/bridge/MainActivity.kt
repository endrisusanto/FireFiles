package dev.firefiles.bridge

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var statusBadge: TextView
    private lateinit var statusDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set elegant dark background for the whole window
        window.decorView.setBackgroundColor(Color.parseColor("#0F172A")) // Slate 900

        val prefs = getSharedPreferences("smb", MODE_PRIVATE)

        // Utility extensions
        fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

        // Reusable UI components builders
        fun createCard(title: String): Pair<LinearLayout, LinearLayout> {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B")) // Slate 800
                    cornerRadius = 16.dp().toFloat()
                }
                setPadding(16.dp(), 16.dp(), 16.dp(), 16.dp())
                val layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                layoutParams.setMargins(0, 0, 0, 16.dp())
                this.layoutParams = layoutParams
            }

            val cardTitle = TextView(this).apply {
                text = title
                textColor("#94A3B8") // Slate 400
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, 12.dp())
                this.layoutParams = params
            }
            card.addView(cardTitle)

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            card.addView(content)

            return Pair(card, content)
        }

        // Main Layout inside ScrollView
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp(), 56.dp(), 20.dp(), 32.dp()) // Safe area padding at the top
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(rootLayout)

        // Header Section
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 24.dp())
            layoutParams = params
        }

        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val appTitle = TextView(this).apply {
            text = "FireFiles"
            textColor("#FFFFFF")
            textSize = 28f
            typeface = Typeface.create("sans-serif-condensed-light", Typeface.BOLD)
        }
        titleLayout.addView(appTitle)

        val appSubtitle = TextView(this).apply {
            text = "Bridge Uploader"
            textColor("#64748B") // Slate 500
            textSize = 14f
        }
        titleLayout.addView(appSubtitle)
        headerLayout.addView(titleLayout)

        // Status Badge Pill
        statusBadge = TextView(this).apply {
            text = "INACTIVE"
            textColor("#FFFFFF")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(12.dp(), 6.dp(), 12.dp(), 6.dp())
            updateBadgeState(false)
        }
        headerLayout.addView(statusBadge)
        rootLayout.addView(headerLayout)

        // Cards Definition
        val (smbCard, smbContent) = createCard("SAMBA SHARE CONFIGURATION")
        val host = input("Ubuntu Host / IP", prefs.getString("host", "") ?: "")
        val share = input("Share Name", prefs.getString("share", "FirmwareInbox") ?: "FirmwareInbox")
        val user = input("Username", prefs.getString("user", "firmware") ?: "firmware")
        val pass = input("Password", prefs.getString("pass", "") ?: "", isPassword = true)
        val folder = input("Remote Folder Path", prefs.getString("folder", "") ?: "")

        listOf(host, share, user, pass, folder).forEach(smbContent::addView)
        rootLayout.addView(smbCard)

        val (serverCard, serverContent) = createCard("SERVER SETUP SYNCHRONIZATION")
        val monitor = input("Monitor Heartbeat URL", prefs.getString("monitor", "https://files.endrisusanto.my.id/api/heartbeat") ?: "https://files.endrisusanto.my.id/api/heartbeat")
        val token = input("Monitor Access Token", prefs.getString("token", "change-me") ?: "change-me", isPassword = true)
        serverContent.addView(monitor)
        serverContent.addView(token)

        // Fetch Button inside Server Card
        val fetchBtn = createStyledButton("Fetch Config From Server", "#3B82F6").apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8.dp(), 0, 0)
            layoutParams = params
        }
        serverContent.addView(fetchBtn)
        rootLayout.addView(serverCard)

        // Details Status info
        statusDetail = TextView(this).apply {
            text = "Folder: /sdcard/FirmwareBridge"
            textColor("#94A3B8")
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8.dp(), 0, 16.dp())
            layoutParams = params
        }
        rootLayout.addView(statusDetail)

        // Bottom Action Stack
        val grantBtn = createStyledButton("Grant All Files Access", "#475569")
        val saveBtn = createStyledButton("Save & Start Uploader", "#10B981").apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 12.dp(), 0, 0)
            layoutParams = params
        }

        rootLayout.addView(grantBtn)
        rootLayout.addView(saveBtn)

        // Action Handlers
        fetchBtn.setOnClickListener {
            val baseUrl = monitor.text.toString().substringBeforeLast("/api/").trimEnd('/')
            val configUrl = "$baseUrl/api/smb-config"
            val tok = token.text.toString()
            statusDetail.text = "Fetching configurations..."
            Thread {
                try {
                    val conn = URL(configUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("x-monitor-token", tok)
                    conn.connect()
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()

                    fun get(key: String) = Regex(""""$key"\s*:\s*"([^"]*)"""")
                        .find(body)?.groupValues?.get(1) ?: ""

                    val h = get("host")
                    val sh = get("share")
                    val u = get("user")
                    val p = get("pass")
                    val f = get("folder")

                    runOnUiThread {
                        if (h.isNotBlank()) {
                            host.setText(h)
                            share.setText(sh)
                            user.setText(u)
                            pass.setText(p)
                            folder.setText(f)
                            statusDetail.text = "✓ Configuration synced from server!"
                        } else {
                            statusDetail.text = "Server configuration is empty."
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusDetail.text = "Fetch failed: ${e.message}"
                    }
                }
            }.start()
        }

        saveBtn.setOnClickListener {
            prefs.edit()
                .putString("host", host.text.toString())
                .putString("share", share.text.toString())
                .putString("user", user.text.toString())
                .putString("pass", pass.text.toString())
                .putString("folder", folder.text.toString().trim('/'))
                .putString("monitor", monitor.text.toString())
                .putString("token", token.text.toString())
                .apply()

            try {
                startForegroundService(Intent(this, UploadService::class.java))
                statusDetail.text = "Uploader running actively."
                updateBadgeState(true)
            } catch (e: Exception) {
                statusDetail.text = "Failed to start service: ${e.message}"
            }
        }

        grantBtn.setOnClickListener {
            if (!Environment.isExternalStorageManager()) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                statusDetail.text = "Files permission already granted!"
            }
        }

        // Initial running status check
        updateBadgeState(isServiceRunning(UploadService::class.java))

        setContentView(scrollView)
    }

    private fun updateBadgeState(running: Boolean) {
        val colorHex = if (running) "#10B981" else "#475569" // Emerald 500 or Slate 600
        statusBadge.text = if (running) "ACTIVE" else "INACTIVE"
        statusBadge.background = GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = 99f // Pill shape
        }
    }

    private fun TextView.textColor(hex: String) {
        setTextColor(Color.parseColor(hex))
    }

    private fun input(hintStr: String, valStr: String, isPassword: Boolean = false) = EditText(this).apply {
        hint = hintStr
        setText(valStr)
        setSingleLine(true)
        setHintTextColor(Color.parseColor("#475569"))
        setTextColor(Color.parseColor("#E2E8F0"))
        
        // Premium minimal input styling
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#0F172A"))
            cornerRadius = 8f * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#334155"))
        }
        
        val padH = (12 * resources.displayMetrics.density).toInt()
        val padV = (10 * resources.displayMetrics.density).toInt()
        setPadding(padH, padV, padH, padV)

        if (isPassword) {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, 10 * resources.displayMetrics.density.toInt())
        layoutParams = params
    }

    private fun createStyledButton(txt: String, bgHex: String) = Button(this).apply {
        text = txt
        textColor("#FFFFFF")
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        textSize = 14f

        background = GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = 10f * resources.displayMetrics.density
        }

        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
