package com.newoether.agora.util

import android.content.Context
import android.os.Build
import com.newoether.agora.api.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Anonymous, opt-in crash reporting.
 *
 * On an uncaught [Throwable] (including [Error]) we persist the report to disk, fsync it,
 * and then let the platform's default handler terminate the process. The newest report is
 * also copied to [FILE] so the next launch can offer a one-tap, opt-in submission to
 * [ENDPOINT]. Dismissing or submitting that dialog removes only [FILE]. Every report stays
 * in the archive directory until the archive cap drops the oldest. Nothing is ever sent
 * without an explicit action.
 *
 * The report carries only a stack trace plus coarse, non-identifying environment data
 * (app version, Android API level, device model) — no user content, no device IDs.
 */
object CrashReporter {

    /** Cloudflare-fronted public endpoint. The origin server address is never referenced here. */
    private const val ENDPOINT = "https://newoether.space/crash"
    private const val DIR = "crash"
    private const val FILE = "pending.json"
    private const val ARCHIVE_DIR = "archive"
    private const val MAX_ARCHIVED_REPORTS = 40
    private const val MAX_TRACE_CHARS = 60_000

    /** Coarse, non-identifying app identity captured once at install time. */
    private data class AppInfo(val versionName: String, val versionCode: Long)

    @Volatile private var appInfo: AppInfo = AppInfo("?", 0)

    /** Rolling diagnostic trail attached to crash reports. Helps pin down crashes we can't
     *  reproduce locally (e.g. the foreground-service start-in-time timeout) by recording
     *  what happened just before, with timestamps. No user content — only coarse event tags. */
    private const val MAX_BREADCRUMBS = 60
    private val breadcrumbs = ConcurrentLinkedDeque<String>()

    /** Append a timestamped breadcrumb to the diagnostic trail (thread-safe, bounded). */
    fun note(message: String) {
        breadcrumbs.addLast("${System.currentTimeMillis()} $message")
        while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.pollFirst()
    }

    /**
     * Registers the global uncaught-exception handler. Call once, as early as possible
     * (Application.onCreate), so crashes during startup are captured too.
     */
    fun install(context: Context) {
        appInfo = readAppInfo(context)
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(appContext, throwable) }
            // Always chain to the platform handler so the process dies as it normally would.
            // The pending file is fsynced first so a hard kill still leaves a next-launch dialog.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Returns the pending crash report JSON, or null if none is waiting. */
    fun pendingReport(context: Context): String? {
        val f = reportFile(context)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /** Drops the next-launch dialog copy. Archived reports stay on disk. */
    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    /**
     * POSTs the given report JSON to the crash endpoint. Returns true on success.
     * Must be called off the main thread (it performs blocking network I/O).
     */
    fun submit(reportJson: String): Boolean =
        runCatching { HttpClient.post(ENDPOINT, reportJson) != null }.getOrDefault(false)

    private fun writeReport(context: Context, throwable: Throwable) {
        persistCrashReport(File(context.filesDir, DIR), crashReportJson(throwable))
    }

    private fun crashReportJson(throwable: Throwable): String {
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
            .toString().take(MAX_TRACE_CHARS)
        return JSONObject().apply {
            put("type", throwable.javaClass.name)
            put("trace", trace)
            put("appVersion", appInfo.versionName)
            put("versionCode", appInfo.versionCode)
            put("androidApi", Build.VERSION.SDK_INT)
            put("androidRelease", Build.VERSION.RELEASE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("ts", System.currentTimeMillis())
            put("breadcrumbs", JSONArray(breadcrumbs.toList()))
        }.toString()
    }

    internal fun persistCrashReport(dir: File, json: String) {
        dir.mkdirs()
        writeSynced(dir, FILE, json)
        val archive = File(dir, ARCHIVE_DIR)
        archive.mkdirs()
        val stamp = System.currentTimeMillis()
        var name = "crash-$stamp.json"
        var sequence = 0
        while (File(archive, name).exists()) {
            sequence++
            name = "crash-$stamp-$sequence.json"
        }
        writeSynced(archive, name, json)
        trimArchive(archive)
    }

    private fun trimArchive(archive: File) {
        val reports = archive.listFiles { file ->
            file.isFile && file.name.startsWith("crash-") && file.name.endsWith(".json")
        } ?: return
        if (reports.size <= MAX_ARCHIVED_REPORTS) return
        reports.sortedBy { it.name }.dropLast(MAX_ARCHIVED_REPORTS).forEach { it.delete() }
    }

    private fun writeSynced(dir: File, fileName: String, json: String) {
        val target = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            FileOutputStream(target, true).use { it.fd.sync() }
            return
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            FileOutputStream(target, true).use { it.fd.sync() }
        }
    }

    private fun reportFile(context: Context): File = File(File(context.filesDir, DIR), FILE)

    @Suppress("DEPRECATION")
    private fun readAppInfo(context: Context): AppInfo = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else pi.versionCode.toLong()
        AppInfo(pi.versionName ?: "?", code)
    }.getOrDefault(AppInfo("?", 0))
}
