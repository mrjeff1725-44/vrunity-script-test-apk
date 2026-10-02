package com.vrunity.vrapk

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

object VrLaunchFailure {
    private fun prefs(context: Context) = context.getSharedPreferences("vr_launch", Context.MODE_PRIVATE)

    fun begin(context: Context): String? {
        val saved = prefs(context)
        val since = saved.getLong("since", 0L)
        val phase = saved.getString("phase", "VR startup") ?: "VR startup"
        var failure = saved.getString("failure", null)
        if (failure == null && since > 0L && Build.VERSION.SDK_INT >= 30) {
            val manager = context.getSystemService(ActivityManager::class.java)
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8).firstOrNull {
                it.processName == context.packageName && it.timestamp >= since &&
                    it.reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                        ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
            }
            if (exit != null) {
                val cause = when (exit.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                    ApplicationExitInfo.REASON_ANR -> "App stopped responding"
                    else -> "Startup crash"
                }
                failure = cause + " during " + phase + "\n" + (exit.description ?: "")
            }
        }
        saved.edit().remove("failure").putLong("since", System.currentTimeMillis())
            .putString("phase", "Loading Android VR bridge").commit()
        return failure
    }

    fun phase(context: Context, value: String) { prefs(context).edit().putString("phase", value.take(256)).commit() }
    fun success(context: Context) { prefs(context).edit().remove("since").remove("phase").remove("failure").commit() }
    fun failure(context: Context, reason: String) {
        prefs(context).edit().remove("since").putString("failure", reason.take(4000)).commit()
    }
}
