package com.andri.ailiveavatar

import android.app.Application
import android.util.Log
import java.io.File

class AvatarApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(filesDir, "crash-log.txt").writeText(
                    Log.getStackTraceString(throwable)
                )
            } catch (_: Exception) {
            }

            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
