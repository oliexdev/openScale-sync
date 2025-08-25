package com.health.openscale.sync.core.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLoggingTree(
    private val context: Context,
    private val maxSizeBytes: Long = 10L * 1024L * 1024L // 10 MB
) : Timber.Tree() {

    companion object {
        const val BASE_NAME = "openscale_sync_log.txt"
        private val TS_HUMAN = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    private val lock = Any()
    private val logDir: File by lazy { File(context.filesDir, "logs").apply { mkdirs() } }
    private val logFile: File by lazy {
        File(logDir, BASE_NAME).apply {
            if (!exists()) writeHeader(this)
        }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val ts = TS_HUMAN.format(Date())
        val lvl = when (priority) {
            Log.VERBOSE -> "VERBOSE"
            Log.DEBUG   -> "DEBUG"
            Log.INFO    -> "INFO"
            Log.WARN    -> "WARN"
            Log.ERROR   -> "ERROR"
            else        -> "UNKNOWN"
        }

        val sb = StringBuilder()
            .append(ts).append(' ')
            .append('[').append(lvl).append(']').append(' ')
            .append(tag ?: "openScale-sync").append(": ")
            .append(message).append('\n')

        if (t != null) sb.append(Log.getStackTraceString(t)).append('\n')

        val payload = sb.toString()
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)

        synchronized(lock) {
            rotateIfNeeded(payloadBytes.size)
            runCatching { FileWriter(logFile, true).use { it.write(payload) } }
        }
    }

    fun file(): File = logFile

    private fun rotateIfNeeded(incomingBytes: Int) {
        val currentBytes = if (logFile.exists()) logFile.length() else 0L
        if (currentBytes + incomingBytes <= maxSizeBytes) return

        val now = TS_HUMAN.format(Date())
        val note = "NOTE: Previous log exceeded ${formatBytes(maxSizeBytes)} at $now; started new log.\n\n"

        if (logFile.exists()) runCatching { logFile.delete() }
        writeHeader(logFile)
        runCatching { FileWriter(logFile, true).use { it.write(note) } }
    }

    fun writeHeader(target: File) {
        val pm = context.packageManager
        val pkg = context.packageName
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        val appName = context.applicationInfo.loadLabel(pm).toString()
        val versionName = info.versionName ?: "?"
        val versionCode = info.longVersionCode
        val started = TS_HUMAN.format(Date())
        val device = "${Build.MANUFACTURER} ${Build.MODEL}"
        val androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

        val header = buildString {
            appendLine("============================================================")
            appendLine("openScale Sync Log")
            appendLine("Application : $appName")
            appendLine("Package     : $pkg")
            appendLine("Version     : $versionName ($versionCode)")
            appendLine("Device      : $device")
            appendLine("OS          : $androidVersion")
            appendLine("Log started : $started")
            appendLine("============================================================")
            appendLine()
        }
        FileWriter(target, true).use { it.write(header) }
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}

object LogManager {
    private const val PREF_KEY = "loggingEnabled"
    private var fileTree: FileLoggingTree? = null
    private const val MAX_SIZE_BYTES: Long = 10L * 1024L * 1024L // 10 MB
    private val lock = Any()

    fun init(context: Context, prefs: SharedPreferences) {
        if (isEnabled(prefs)) enableInternal(context) else disableInternal()
    }

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_KEY, false)

    fun setEnabled(context: Context, prefs: SharedPreferences, enabled: Boolean) {
        synchronized(lock) {
            val wasEnabled = isEnabled(prefs)
            prefs.edit().putBoolean(PREF_KEY, enabled).apply()

            if (enabled && !wasEnabled) {
                // Transition off -> on: fresh file
                disableInternal()
                freshLogFile(context)
                enableInternal(context)
                Timber.i("Logging enabled (fresh start)")
            } else if (!enabled && wasEnabled) {
                // Transition on -> off
                disableInternal()
                Timber.i("Logging disabled")
            } else {
            }
        }
    }

    private fun enableInternal(context: Context) {
        if (fileTree == null) {
            fileTree = FileLoggingTree(context.applicationContext, MAX_SIZE_BYTES)
            Timber.plant(fileTree!!)
        }
    }

    private fun disableInternal() {
        fileTree?.let {
            Timber.uproot(it)
            fileTree = null
        }
    }

    fun logFile(context: Context): File =
        (fileTree ?: FileLoggingTree(context, MAX_SIZE_BYTES)).file()

    fun hasLogFile(context: Context): Boolean {
        val f = logFile(context)
        return f.exists() && f.length() > 0
    }

    private fun freshLogFile(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, FileLoggingTree.BASE_NAME)
        if (f.exists()) runCatching { f.delete() }
        FileLoggingTree(context).writeHeader(target = f)
    }

    fun clearLog(context: Context) {
        synchronized(lock) {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, FileLoggingTree.BASE_NAME)
            if (f.exists()) runCatching { f.delete() }
            FileLoggingTree(context).writeHeader(target = f)
        }
    }
}
