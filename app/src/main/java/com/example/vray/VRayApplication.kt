package com.example.vray

import android.app.Application
import android.content.Context
import android.util.Log

class VRayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable)
                getSharedPreferences("vray_crash", Context.MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", trace)
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {
                // If even saving the crash fails, fall through to the default handler below.
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
