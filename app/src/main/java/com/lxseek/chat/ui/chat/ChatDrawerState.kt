package com.lxseek.chat.ui.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.lxseek.chat.ui.motion.LxChatMotionPolicy

/**
 * Drawer state management — extracted from [ChatApp] to keep the main
 * scaffold composition focused on layout while the navigation-drawer
 * lifecycle (open/close gating, focus clearing, availability effects)
 * lives here.
 *
 * The drawer refuses to open when [drawerEnabled] is false (e.g. in
 * selection mode or when the composer is expanded). Opening also clears
 * the soft-keyboard focus so the drawer does not fight the IME for
 * gesture area.
 *
 * [DrawerAvailabilityEffect] is wired in so that reduced-motion users
 * get an instant snap instead of the default drawer slide animation.
 */

/**
 * Creates and remembers a [DrawerState] whose open/close transitions
 * respect [drawerEnabled] and clear focus on open.
 *
 * Extracted from [ChatApp] so the scaffold body no longer carries the
 * drawer-state boilerplate and the `confirmStateChange` policy reads
 * next to the availability effect that depends on it.
 */
@Composable
internal fun rememberChatDrawerState(
    drawerEnabled: Boolean,
    motionPolicy: LxChatMotionPolicy,
    focusManager: FocusManager,
): DrawerState {
    val latestDrawerEnabled by rememberUpdatedState(drawerEnabled)
    val drawerState = rememberDrawerState(
        initialValue = DrawerValue.Closed,
        confirmStateChange = { newValue ->
            val allowed = newValue == DrawerValue.Closed || latestDrawerEnabled
            if (allowed && newValue != DrawerValue.Closed) {
                focusManager.clearFocus()
            }
            allowed
        },
    )
    DrawerAvailabilityEffect(drawerEnabled, motionPolicy, drawerState)
    return drawerState
}

/**
 * Computes the drawer width (80% of the window) in both [Dp] and raw
 * pixels. The pixel value is needed by the scrim/drag gesture layer
 * while the [Dp] value drives the drawer content layout.
 */
internal fun computeDrawerDimensions(
    density: Density,
    windowWidthPx: Int,
): DrawerDimensions {
    val drawerWidth = with(density) { windowWidthPx.toDp() } * 0.8f
    val drawerWidthPx = with(density) { drawerWidth.toPx() }
    return DrawerDimensions(width = drawerWidth, widthPx = drawerWidthPx)
}

/** Packed drawer geometry so callers receive a single stable value. */
internal data class DrawerDimensions(
    val width: Dp,
    val widthPx: Float,
)