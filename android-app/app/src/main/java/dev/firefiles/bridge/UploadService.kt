package dev.firefiles.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.EnumSet
import kotlin.concurrent.thread

class UploadService : Service() {
    private val root = File(Environment.getExternalStorageDirectory(), "FirmwareBridge")
    private val statusDir = File(root, "status")
    @Volatile private var running = true

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("Waiting for firmware"))
        root.mkdirs()
        statusDir.mkdirs()
        thread(name = "firefiles-uploader") { loop() }
        thread(name = "firefiles-heartbeat") { heartbeatLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loop() {
        while (running) {
            root.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".tar.md5") }
                ?.sortedBy { it.name }
                ?.firstOrNull()
                ?.let { uploadWithRetry(it) }
            Thread.sleep(5000)
        }
    }

    private fun uploadWithRetry(file: File) {
        repeat(3) { attempt ->
            try {
                if (!waitStable(file)) return
                writeStatus(file, "uploading", null)
                upload(file)
                writeStatus(file, "uploaded", null)
                return
            } catch (e: Exception) {
                writeStatus(file, "failed", e.message ?: e.javaClass.simpleName)
                Thread.sleep((attempt + 1) * 5000L)
            }
        }
    }

    private fun waitStable(file: File): Boolean {
        var last = file.length()
        var stable = 0
        while (running && file.exists()) {
            Thread.sleep(2000)
            val size = file.length()
            if (size == last) stable += 2 else stable = 0
            last = size
            if (stable >= 10) return true
        }
        return false
    }

    private fun upload(local: File) {
        val prefs = getSharedPreferences("smb", MODE_PRIVATE)
        val host = prefs.getString("host", "")!!.ifBlank { error("missing host") }
        val shareName = prefs.getString("share", "FirmwareInbox")!!
        val user = prefs.getString("user", "firmware")!!
        val pass = prefs.getString("pass", "")!!
        val folder = prefs.getString("folder", "")!!.trim('/')
        val remote = if (folder.isBlank()) local.name else "$folder/${local.name}"
        val tmp = "$remote.uploading"

        SMBClient().use { client ->
            client.connect(host).use { connection ->
                val ac = AuthenticationContext(user, pass.toCharArray(), "")
                val session = connection.authenticate(ac)
                (session.connectShare(shareName) as DiskShare).use { share ->
                    local.inputStream().use { input ->
                        val remoteFile = share.openFile(
                            tmp,
                            EnumSet.of(AccessMask.GENERIC_ALL),
                            EnumSet.noneOf(FileAttributes::class.java),
                            SMB2ShareAccess.ALL,
                            SMB2CreateDisposition.FILE_OVERWRITE_IF,
                            EnumSet.noneOf(SMB2CreateOptions::class.java)
                        )
                        remoteFile.use { out -> input.copyTo(out.outputStream) }
                    }
                    val remoteSize = share.getFileInformation(tmp).standardInformation.endOfFile
                    check(remoteSize == local.length()) { "remote size $remoteSize != ${local.length()}" }
                    if (share.fileExists(remote)) share.rm(remote)
                    share.openFile(
                        tmp,
                        EnumSet.of(AccessMask.GENERIC_ALL),
                        EnumSet.noneOf(FileAttributes::class.java),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.noneOf(SMB2CreateOptions::class.java)
                    ).use { it.rename(remote, true) }
                }
            }
        }
    }

    private fun writeStatus(file: File, state: String, error: String?) {
        statusDir.mkdirs()
        val escapedError = error?.replace("\\", "\\\\")?.replace("\"", "\\\"")
        File(statusDir, "${file.name}.json").writeText(
            buildString {
                append("{\"file\":\"").append(file.name).append("\",\"state\":\"").append(state).append("\",\"size\":").append(file.length())
                if (escapedError != null) append(",\"error\":\"").append(escapedError).append("\"")
                append("}")
            }
        )
    }

    private fun heartbeatLoop() {
        while (running) {
            try {
                postHeartbeat()
            } catch (_: Exception) {
            }
            Thread.sleep(30000)
        }
    }

    private fun postHeartbeat() {
        val prefs = getSharedPreferences("smb", MODE_PRIVATE)
        val monitor = prefs.getString("monitor", "")!!.ifBlank { return }
        val token = prefs.getString("token", "change-me")!!
        val mem = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mem)
        val storage = StatFs(Environment.getExternalStorageDirectory().path)
        val free = storage.availableBytes
        val total = storage.totalBytes
        val pending = root.listFiles()?.count { it.isFile && it.name.endsWith(".tar.md5") } ?: 0
        val body = """{"id":"android","name":"${android.os.Build.MODEL}","kind":"android-apk","status":"running","cpu_percent":0,"ram_percent":${100 * (mem.totalMem - mem.availMem) / mem.totalMem},"ram_free_bytes":${mem.availMem},"storage_percent":${100 * (total - free) / total},"storage_free_bytes":$free,"detail":"pending $pending"}"""
        val conn = URL(monitor).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-monitor-token", token)
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.inputStream.close()
    }

    private fun notification(text: String): Notification {
        val channel = "uploads"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, "Uploads", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, channel)
            .setContentTitle("FireFiles Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
    }
}
