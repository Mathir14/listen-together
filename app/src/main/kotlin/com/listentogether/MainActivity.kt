package com.listentogether

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.listentogether.core.designsystem.ListenTogetherTheme
import com.listentogether.service.SessionService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val appGraph: AppGraph
        get() = (application as ListenTogetherApp).appGraph

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (!recordAudioGranted) {
            // Degrades safely without crash
            appGraph.sessionRuntime.onMicrophoneUnavailable()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request runtime permissions if not yet granted
        requestRuntimePermissions()

        // Handle deep link if launched with one
        handleDeepLink(intent)

        // Observe foreground service requirements
        lifecycleScope.launch {
            appGraph.sessionRuntime.serviceRequired.collectLatest { required ->
                if (required) {
                    SessionService.start(this@MainActivity)
                } else {
                    SessionService.stop(this@MainActivity)
                }
            }
        }

        setContent {
            ListenTogetherTheme {
                MainNavigationHost(appGraph = appGraph)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun requestRuntimePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest += Manifest.permission.RECORD_AUDIO
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val request = DeepLinkHandler.parse(intent) ?: return
        lifecycleScope.launch {
            appGraph.joinWithDeepLink(request)
        }
    }
}
