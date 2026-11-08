package com.listentogether

import android.app.Application
import android.content.pm.ApplicationInfo
import com.listentogether.core.network.NetworkConfig

class ListenTogetherApp : Application() {

    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val baseUrl = if (isDebuggable) {
            // Debug builds can use a local development backend if configured via property
            System.getProperty("com.listentogether.backend.url") ?: NetworkConfig.PRODUCTION_BASE_URL
        } else {
            // Release builds strictly enforce the deployed production backend with TLS
            NetworkConfig.PRODUCTION_BASE_URL
        }
        appGraph = AppGraph(this, networkConfig = NetworkConfig(baseUrl))
    }

    override fun onTerminate() {
        if (::appGraph.isInitialized) {
            appGraph.close()
        }
        super.onTerminate()
    }
}
