package com.droid.whaly

import android.app.Application
import android.content.Context
import com.droid.whaly.plugin.PluginManager
import com.droid.whaly.plugin.PluginDownloadPolicy

class WhalyApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        PluginDownloadPolicy.initialize(this)
        // Vault the real adapter name before any screen can touch the radio.
        com.droid.whaly.ghost.GhostIdentity.bind(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val log = buildString {
                append("Thread: ")
                append(thread.name)
                append('\n')
                append("Time: ")
                append(java.time.Instant.now().toString())
                append('\n')
                append(android.util.Log.getStackTraceString(throwable))
            }
            runCatching { java.io.File(filesDir, "crash_stack.txt").writeText(log) }
            if (PluginManager.autoSafeModeOnCrash.value && PluginManager.hasRunningPlugins()) {
                PluginManager.activateSafeModeFromCrash(this, log)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
        try {
            PluginManager.initialize(this)
        } catch (t: Throwable) {
            android.util.Log.e("WhalyApplication", "Plugin init failed", t)
        }
    }
}

