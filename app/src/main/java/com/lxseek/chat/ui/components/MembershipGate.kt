package com.lxseek.chat.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.ui.theme.LxDesign

/**
 * 颗粒最小化的会员门控组件库 —— 4 个粒度档位。
 *
 * 旧版只有一个 [MembershipGatedContent]：不管门控对象是一个开关还是一整页，
 * 都套同样的"alpha 0.4 + scrim + 居中锁卡"重型遮罩。这造成两个问题：
 *  1. 视觉噪声与层级浪费——给一个 48dp 的开关也铺 240dp 宽的锁卡；
 *  2. 门控语义无法按"能力"对齐，FeatureTierMapper 里的每个 PAID 条目
 *     在 UI 层没有可复用的最小门控单元。
 *
 * 四档粒度（从粗到细）：
 *  - [GatedPage]      页面级：整页替换为升级引导（用于整个页面都是付费能力的场景）；
 *  - [GatedSection]   区段级：内容保留可读，顶部一条内联提示条，点击跳转升级；
 *  - [GatedEntry]     入口级：设置行图标置灰 + 小锁徽标，点击跳转升级页；
 *  - [LockedControl]  控件级：仅一个 16dp 内联小锁，行内容照常渲染。
 *
 * 全部走 LxDesign 令牌（零 elevation、细强调条），重组路径上无 lambda 分配。
 */

/**
 * 档位 1 —— 控件级：内容照常渲染，仅在 [trailing] 位置放一个 16dp 小锁。
 *
 * 用于"整个设置行可见、只有行内某个控件是付费能力"的场景。锁本身可点击，
 * 点击即跳转升级页，不给整行铺遮罩。
 */
@Composable
fun LockedControl(
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.membership_gated_locked),
) {
    Icon(
        imageVector = Icons.Outlined.Lock,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = modifier
            .size(16.dp)
            .clickable(onClick = onUpgradeClick)
            .padding(2.dp),
    )
}

/**
 * 档位 2 —— 入口级：付费入口行的通用渲染。
 *
 * 图标/文字置灰（0.38f alpha，Material 禁用标准），行尾替换为小锁；
 * 点击整行不进入目标页，而是直接跳会员页。行内容仍由调用方提供，
 * 保证与免费行共用同一布局结构（零额外重组）。
 */
@Composable
fun GatedEntry(
    locked: Boolean,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    title: String,
    description: String? = null,
    preview: String? = null,
    trailingContent: @Composable () -> Unit = {},
) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val entryModifier = if (locked) {
        modifier.clickable { onUpgradeClick() }
    } else {
        modifier
    }
    Row(
        modifier = entryModifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (locked) disabledColor else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (locked) disabledColor else MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (locked) disabledColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (preview != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = if (locked) disabledColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(120.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (locked) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = stringResource(R.string.membership_gated_locked),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp),
            )
        } else {
            trailingContent()
        }
    }
}

/**
 * 档位 3 —— 区段级：内容完整保留（不置灰、不遮罩），顶部一条内联提示条。
 *
 * 适合"区段里的配置可以看但不能改"的场景：用户能理解这个功能是什么，
 * 只是被提示需要升级。提示条 = 2dp 强调条 + 一行文字 + 右侧小锁，点击跳转。
 */
@Composable
fun GatedSection(
    locked: Boolean,
    featureName: String,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (locked) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = LxDesign.shapeS,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUpgradeClick() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = featureName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(R.string.gate_unlock_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        content()
    }
}

/**
 * 档位 4 —— 页面级：整个页面都是付费能力时，直接渲染升级引导页。
 *
 * 比 scrim+锁卡方案更省：不组合底层页面（省掉整棵子树的测量与绘制），
 * 只渲染一个品牌图标、标题、说明与按钮。
 */
@Composable
fun GatedPage(
    featureName: String,
    onUpgradeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = stringResource(R.string.membership_gated_locked),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = featureName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.membership_gated_upgrade_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUpgradeClick) {
                Text(stringResource(R.string.membership_gated_upgrade_button))
            }
        }
    }
}
