package com.sysadmindoc.alarmclock.ui.stopwatch

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
    @get:Rule val compose = createComposeRule()
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
        compose.onNodeWithText(String.format(java.util.Locale.ROOT, "%02d", count)).performScrollTo().assertIsDisplayed()
        val dir = File("build/reports/stopwatch-layout").apply { mkdirs() }
        File(dir, "${count}-laps.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun oneLapIsVisibleOnSmallScreen() = verifyLaps(1)
    @Test fun twoLapsAreVisibleOnSmallScreen() = verifyLaps(2)
}
