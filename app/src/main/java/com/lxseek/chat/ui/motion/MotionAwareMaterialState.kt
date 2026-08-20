package com.lxseek.chat.ui.motion

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue

/** Direct drawer drags remain gesture-bound; button/back-triggered travel snaps when reduced. */
suspend fun DrawerState.openWithMotionPolicy(policy: LxChatMotionPolicy) {
    if (policy.allowSpatialTransitions) {
        open()
    } else {
        snapTo(DrawerValue.Open)
    }
}

suspend fun DrawerState.closeWithMotionPolicy(policy: LxChatMotionPolicy) {
    if (policy.allowSpatialTransitions) {
        close()
    } else {
        snapTo(DrawerValue.Closed)
    }
}
