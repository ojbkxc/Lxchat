package com.lxseek.chat.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.ui.components.clearFocusOnTap

/** Fixed height of the flat settings top bar (below the status bar). */
internal val SettingsBarHeight = 64.dp

/**
 * Flat ChatGPT-style settings top bar: a plain back arrow on the left, the title centered, optional
 * [actions] on the right. No circular button, no gradient, no elevation. An opaque bar (including
 * its status-bar strip) hides list content scrolling underneath it.
 */
@Composable
private fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    statusBarTop: Dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(statusBarTop + SettingsBarHeight)
            .background(MaterialTheme.colorScheme.background)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(start = 4.dp, top = statusBarTop)
                .size(SettingsBarHeight),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp, top = statusBarTop),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * ChatGPT-style settings page scaffold for **scrolling-Column** sub-pages: a flat top bar with a
 * centered title plus the given [content] scrolling beneath it. The composable name historically
 * said "Collapsing" (an iOS-style large title) — that decoration was removed in the minimalism
 * pass; the name is kept so all existing call sites keep compiling unchanged.
 */
@Composable
fun CollapsingSettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(scrollState)
                .clearFocusOnTap()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(statusBarTop + SettingsBarHeight))
            content()
            Spacer(modifier = Modifier.height(32.dp))
        }
        SettingsTopBar(
            title = title,
            onBack = onBack,
            statusBarTop = statusBarTop,
            actions = actions,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            floatingActionButton()
        }
    }
}

/**
 * ChatGPT-style settings page scaffold for **LazyColumn** sub-pages (dynamic lists). Same flat top
 * bar as [CollapsingSettingsScaffold]; provide list content via [content]. The "Collapsing" name is
 * kept for call-site compatibility (see [CollapsingSettingsScaffold]).
 */
@Composable
fun CollapsingSettingsLazyScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentHorizontalPadding: Dp = 16.dp,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .clearFocusOnTap()
                .padding(horizontal = contentHorizontalPadding),
            contentPadding = PaddingValues(top = statusBarTop + SettingsBarHeight)
        ) {
            content()
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
        SettingsTopBar(
            title = title,
            onBack = onBack,
            statusBarTop = statusBarTop,
            actions = actions,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            floatingActionButton()
        }
    }
}