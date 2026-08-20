package com.lxseek.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

import com.lxseek.chat.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.ui.components.TypewriterMode
import com.lxseek.chat.ui.components.TypewriterText
import com.lxseek.chat.ui.motion.LxChatMotionPolicy

@Composable
internal fun ChatAppScrollToBottomFab(
    showButton: Boolean,
    motionPolicy: LxChatMotionPolicy,
    bottomBarHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    onRequestScroll: () -> Unit,
) {
    val fabElevation by animateDpAsState(
        targetValue = if (showButton) 4.dp else 0.dp,
        animationSpec = if (motionPolicy.allowSpatialTransitions) {
            tween(400)
        } else {
            snap()
        }
    )

    AnimatedVisibility(
        visible = showButton,
        enter = if (motionPolicy.allowSpatialTransitions) {
            fadeIn(tween(400)) +
                scaleIn(initialScale = 0.6f, animationSpec = tween(400))
        } else {
            fadeIn(tween(400))
        },
        exit = if (motionPolicy.allowSpatialTransitions) {
            fadeOut(tween(400)) +
                scaleOut(targetScale = 0.6f, animationSpec = tween(400))
        } else {
            fadeOut(tween(400))
        },
        modifier = modifier.padding(bottom = bottomBarHeight + 8.dp)
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            FloatingActionButton(onClick = onRequestScroll, containerColor = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface, shape = CircleShape, elevation = FloatingActionButtonDefaults.elevation(fabElevation), modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.scroll_to_bottom), modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
internal fun ChatAppShareSelectionOverlay(
    shareSelectionActive: Boolean,
    motionPolicy: LxChatMotionPolicy,
    bottomBarHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    hasSelection: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShareImage: () -> Unit,
    onShareMarkdown: () -> Unit,
    onSaveToGallery: () -> Unit,
    onConfirm: () -> Unit,
) {
    AnimatedVisibility(
        visible = shareSelectionActive,
        enter = if (motionPolicy.allowSpatialTransitions) {
            fadeIn(tween(220)) + scaleIn(
                initialScale = 0.86f,
                animationSpec = tween(220),
            )
        } else {
            fadeIn(tween(220))
        },
        exit = if (motionPolicy.allowSpatialTransitions) {
            fadeOut(tween(180)) + scaleOut(
                targetScale = 0.86f,
                animationSpec = tween(180),
            )
        } else {
            fadeOut(tween(180))
        },
        modifier = modifier
            .padding(bottom = bottomBarHeight + 10.dp),
    ) {
        ShareSelectionFab(
            hasSelection = hasSelection,
            onDismiss = onDismiss,
            onCopy = onCopy,
            onShareImage = onShareImage,
            onShareMarkdown = onShareMarkdown,
            onSaveToGallery = onSaveToGallery,
            onConfirm = onConfirm,
        )
    }
}

@Composable
internal fun ChatAppSwitchingOverlay(
    isSwitching: Boolean,
    isTransitioningToNewChat: Boolean,
) {
    AnimatedVisibility(
        visible = isSwitching && !isTransitioningToNewChat,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 5.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun ChatAppWelcomeContent(
    bottomBarHeight: Dp,
    windowHeightDp: Float,
    topBarHeight: Dp,
    newChatEntryId: Long,
    animateWelcomeText: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = bottomBarHeight),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            val welcomeText = stringResource(R.string.welcome_to_lxchat)
            val availableWelcomeHeight =
                windowHeightDp +
                    topBarHeight.value / 2f -
                    bottomBarHeight.value
            val welcomeTopPadding =
                (availableWelcomeHeight / 2f).coerceAtLeast(0f).dp
            val welcomeModifier =
                Modifier.padding(top = welcomeTopPadding)
            TypewriterText(
                text = welcomeText,
                animationKey = newChatEntryId,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                typeSpeedMs = 100,
                animate = animateWelcomeText,
                mode = TypewriterMode.TEXT_GRADIENT,
                modifier = welcomeModifier,
            )
        }
    }
}
