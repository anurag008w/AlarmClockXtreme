package com.sysadmindoc.alarmclock.ui.theme

import androidx.compose.ui.unit.dp

/** Spacing scale shared by every screen. Use these instead of ad hoc dp values. */
object AppSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 40.dp
    val huge = 48.dp
}

/** Corner radius scale. */
object AppRadius {
    val xs = 10.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
}

/** Minimum touch target for any tappable control. */
object AppSize {
    val minTouch = 48.dp
    val divider = 1.dp
    val iconSm = 18.dp
    val iconMd = 24.dp
}

/** Elevation is kept minimal: surfaces separate by tone and hairline, not shadow. */
object AppElevation {
    val none = 0.dp
    val raised = 1.dp
    val overlay = 3.dp
}

/** Standard motion duration range for state and expand/collapse transitions. */
object AppMotion {
    const val FastMs = 150
    const val StandardMs = 200
}
