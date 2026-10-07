package com.sysadmindoc.alarmclock.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.sysadmindoc.alarmclock.ui.settings.UtilityShortcutCard
import com.sysadmindoc.alarmclock.ui.theme.AlarmClockXtremeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YouTubeFixLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun truthfulProgressAndSeparateLogOptions() {
        compose.setContent {
            AlarmClockXtremeTheme {
                Column(Modifier.width(360.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DownloadingHint()
                    UtilityShortcutCard(Icons.Default.BugReport, "Share all logs (.txt)",
                        "One text file with redacted crashes, readiness, diagnostics and YouTube failures. Nothing is uploaded automatically.") {}
                    UtilityShortcutCard(Icons.Default.BugReport, "Share crash logs", "Existing crash-only export") {}
                }
            }
        }
        compose.onNodeWithText("Share all logs (.txt)").assertIsDisplayed()
        compose.onNodeWithText("Share crash logs").assertIsDisplayed()
        compose.onNodeWithText("79%").assertDoesNotExist()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val dir=File("build/reports/stopwatch-layout").apply { mkdirs() }
        File(dir,"youtube-fix-options.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
