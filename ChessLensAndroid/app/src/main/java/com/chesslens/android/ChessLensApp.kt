package com.chesslens.android

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

/** Starts crash reporting before anything else runs. */
class ChessLensApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
    }
}

/**
 * If the app crashes, the error is saved and shown on the next launch so it can be copied and
 * sent along; nothing leaves the phone by itself.
 */
object CrashReport {
    private const val PREFS = "chesslens_crash"
    private const val KEY = "report"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val text = "ChessLens ${versionName(app)} on Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})\n" +
                    "Thread: ${thread.name}\n\n" + trace.take(6000)
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, text).commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved report from the last crash, if any (cleared once read). */
    fun take(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY, null) ?: return null
        prefs.edit().remove(KEY).apply()
        return text
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}
