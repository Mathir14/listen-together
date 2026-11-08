package com.listentogether

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppPermissionsAndSecurityTest {

    @Test
    fun `network security config explicitly prohibits cleartext traffic`() {
        val configFile = File("src/main/res/xml/network_security_config.xml")
        assertTrue(configFile.exists(), "network_security_config.xml must exist")

        val content = configFile.readText()
        assertTrue(
            content.contains("""cleartextTrafficPermitted="false""""),
            "Cleartext traffic must be disabled (cleartextTrafficPermitted=\"false\")",
        )
        assertFalse(
            content.contains("""cleartextTrafficPermitted="true""""),
            "Cleartext traffic must never be permitted",
        )
    }

    @Test
    fun `android manifest declares required foreground service permissions and types`() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue(manifestFile.exists(), "AndroidManifest.xml must exist")

        val manifestText = manifestFile.readText()
        assertTrue(manifestText.contains("android.permission.INTERNET"))
        assertTrue(manifestText.contains("android.permission.RECORD_AUDIO"))
        assertTrue(manifestText.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifestText.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifestText.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
        assertTrue(manifestText.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(manifestText.contains("""android:networkSecurityConfig="@xml/network_security_config""""))
    }
}
