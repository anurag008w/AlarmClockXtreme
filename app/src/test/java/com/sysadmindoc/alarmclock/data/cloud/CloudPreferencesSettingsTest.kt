package com.sysadmindoc.alarmclock.data.cloud

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class CloudPreferencesSettingsTest {
    @Test fun remoteSettingsDoNotOverwriteDeviceSecretsOrCapturedLocation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = PreferencesManager(context)
        preferences.update { it.copy(hueApiKey="local-only", webhookSigningSecret="local-secret", lastKnownLatitude=12.3) }
        preferences.applyCloudSettings(mapOf("bedtimeHour" to 22.0, "hueApiKey" to "remote", "webhookSigningSecret" to "remote", "lastKnownLatitude" to 50.0))
        val current = preferences.getCurrentSettings()
        assertEquals(22,current.bedtimeHour)
        assertEquals("local-only",current.hueApiKey)
        assertEquals("local-secret",current.webhookSigningSecret)
        assertEquals(12.3,current.lastKnownLatitude,0.001)
        val export = preferences.cloudSettings()
        assertFalse(export.containsKey("hueApiKey"))
        assertFalse(export.containsKey("webhookSigningSecret"))
        assertFalse(export.containsKey("lastKnownLatitude"))
    }
    @Test fun worldClockZonesMigrateAndAcceptEmptyRemoteList() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("world_clock_prefs", Context.MODE_PRIVATE).edit()
            .putString("zones", "Asia/Kolkata|Europe/London").commit()
        val preferences = PreferencesManager(context)
        assertEquals("Asia/Kolkata|Europe/London", preferences.cloudSettings()["worldClockZones"])
        preferences.applyCloudSettings(mapOf("worldClockZones" to ""))
        assertEquals("", preferences.cloudSettings()["worldClockZones"])
        preferences.applyCloudSettings(mapOf("worldClockZones" to "Not/A_Zone"))
        assertEquals("", preferences.cloudSettings()["worldClockZones"])
    }
 @Test fun differentAccountCannotInheritPhoneAlarmsAfterLogout() {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.getSharedPreferences("cloud_sync",Context.MODE_PRIVATE).edit().clear().commit()
  val prefs=CloudPreferences(context);prefs.saveSession("token-one","one@example.invalid");prefs.clearSession()
  var blocked=false
  try {prefs.saveSession("token-two","two@example.invalid")} catch(_:IllegalArgumentException){blocked=true}
  assertTrue(blocked);assertFalse(prefs.isLoggedIn())
  prefs.saveSession("token-new","one@example.invalid");assertTrue(prefs.isLoggedIn())
 }
}
