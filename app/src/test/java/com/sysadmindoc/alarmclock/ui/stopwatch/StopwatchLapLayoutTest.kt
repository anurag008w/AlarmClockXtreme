package com.sysadmindoc.alarmclock.ui.stopwatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.ui.theme.AlarmClockXtremeTheme
import org.junit.After
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
class StopwatchLapLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After fun clear() {
        context.getSharedPreferences("stopwatch_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun verifyLaps(count: Int) {
        clear()
        val vm = StopwatchViewModel(context)
        vm.start()
        repeat(count) { vm.lap() }
        vm.pause()
        compose.setContent {
            AlarmClockXtremeTheme {
                Box(Modifier.width(360.dp).height(640.dp)) {
                    StopwatchScreen(viewModel = vm)
                }
            }
        }
        compose.onNodeWithTag("stopwatch-lap-history").performScrollTo()
        val dir = File("build/reports/stopwatch-layout").apply { mkdirs() }
        // Robolectric has no physical Surface for PixelCopy's forceRedraw.
        // Draw the actual attached view tree into a native bitmap instead.
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        File(dir, "${count}-laps.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        compose.onNodeWithText(String.format(java.util.Locale.ROOT, "%02d", count)).assertIsDisplayed()
    }

    @Test fun oneLapIsVisibleOnSmallScreen() = verifyLaps(1)
    @Test fun twoLapsAreVisibleOnSmallScreen() = verifyLaps(2)
}
