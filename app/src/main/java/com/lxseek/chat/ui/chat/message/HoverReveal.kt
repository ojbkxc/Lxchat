package com.lxseek.chat.ui.chat.message

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

internal class HoverRevealState internal constructor(
    val revealed: Boolean,
    val modifier: Modifier,
)

/**
 * ChatGPT 风格悬停揭示：桌面端鼠标悬停消息时才浮现操作行，触摸端恒为可见。
 *
 * [hoverEnabled] 是列表级"是否检测到鼠标"的标志（见 [rememberMouseDetector]）。
 * 触摸设备上为 false，操作行恒显示；桌面端首次出现鼠标后，操作行仅在悬停该消息时显示。
 * [enabled] 表示该消息此刻是否本应显示操作行（流式生成期间为 false）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun rememberHoverRevealState(
    hoverEnabled: Boolean,
    enabled: Boolean,
): HoverRevealState {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val revealed = if (hoverEnabled) isHovered else true
    return HoverRevealState(
        revealed = enabled && revealed,
        modifier = if (hoverEnabled) {
            Modifier.hoverable(interactionSource)
        } else {
            Modifier
        },
    )
}

internal class MouseDetectorState internal constructor(
    val hasMouse: Boolean,
    val modifier: Modifier,
)

/**
 * 列表级鼠标探测：任何鼠标指针事件（含移动）经过该 modifier 命中区域即视为桌面端。
 * 触摸事件类型为 Touch，不会触发，因此手机上操作行保持恒显示。
 */
@Composable
internal fun rememberMouseDetector(): MouseDetectorState {
    var hasMouse by remember { mutableStateOf(false) }
    val modifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { it.type == PointerType.Mouse }) {
                    hasMouse = true
                }
            }
        }
    }
    return MouseDetectorState(hasMouse, modifier)
}