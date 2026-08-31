package com.lxseek.chat.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.membership.ActivationManager
import com.lxseek.chat.membership.ActivationResult
import com.lxseek.chat.membership.LocalMembershipProvider
import com.lxseek.chat.membership.MembershipStatus
import com.lxseek.chat.membership.MembershipTier
import com.lxseek.chat.membership.PlanCatalog
import com.lxseek.chat.membership.PendingOrderStore
import com.lxseek.chat.membership.RemoteCloudApi
import com.lxseek.chat.membership.YipayConfig
import com.lxseek.chat.membership.YipayPaymentManager
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Membership settings page: status card + activation code input + plan selection.
 *
 * 二元制会员体系：账户只有免费/付费两档；套餐（月付/季付/半年/年付/永久）是
 * 付费账户的不同时长买法（统一定义在 [PlanCatalog]，重构 R2），不是等级。
 *
 * Sections rendered in a [LazyColumn]:
 *  - **Status card** — current account type (color-coded), expiry, source.
 *  - **Device ID card** — device identity + online restore button.
 *  - **Activation code** — text field + activate button + result feedback.
 *  - **Restore / Free trial** — contextual sections for free users.
 *  - **Plan selection** — paid plans (hidden for lifetime members).
 *
 * The page reads [ChatViewModel.membership] (a [com.lxseek.chat.viewmodel.MembershipViewModelApi])
 * and never touches other ViewModel state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMembershipPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onNavigateToAbout: () -> Unit = {},
) {
    val status by viewModel.membership.status.collectAsState()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val paymentRedirecting = stringResource(R.string.membership_payment_redirecting)

    // Device ID + activation code: use RemoteCloudApi to call remote activation server.
    // LocalCloudApi is only for offline fallback (local verification when no network).
    val activationManager = remember {
        ActivationManager(RemoteCloudApi(context), context)
    }
    var activationCodeInput by rememberSaveable { mutableStateOf("") }
    var activationResult by remember { mutableStateOf<ActivationResult?>(null) }
    var isActivating by remember { mutableStateOf(false) }

    // Free trial state.
    var isTrialing by remember { mutableStateOf(false) }
    var trialMessage by remember { mutableStateOf<String?>(null) }
    // Local flag: whether trial has been used. Read once.
    val trialUsed = remember { activationManager.isTrialUsed() }

    // Renewal state.
    var isRenewing by remember { mutableStateOf(false) }
    var renewMessage by remember { mutableStateOf<String?>(null) }

    // Restore state: free user with no local credential — may be a reinstall.
    var isRestoring by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }

    // 支付下单失败提示文案（复用现有字符串资源，H2 回退禁用时使用）。
    val paymentFailedText = stringResource(R.string.membership_payment_failed)

    /**
     * 统一下单入口（R2 + H2）：
     * 1) 先尝试云端下单（/api/create_payment），服务端生成订单 + 支付 URL（服务器权威定价）。
     * 2) 失败时若已配置易支付商户密钥，回退到 App 端自行构造支付 URL（旧逻辑）。
     *    未配置商户密钥（H2）则**禁用本地回退**（空/假密钥签名无意义且可被利用），
     *    仅提示失败——支付确认只能依赖服务器对账路径。
     * 二元制：所有套餐激活后都是同一档付费账户（Premium），无档位参数。
     */
    suspend fun purchasePlan(plan: PlanCatalog.Plan) {
        val orderResult = activationManager.createPaymentOrder(
            amount = plan.amount,
            planId = plan.id,
        )
        if (orderResult != null) {
            // 云端下单成功：保存 pending order，打开支付 URL。
            PendingOrderStore(context).save(
                PendingOrderStore.PendingOrder(
                    outTradeNo = orderResult.outTradeNo,
                    tier = MembershipTier.Premium,
                    amount = plan.amount,
                    timestamp = System.currentTimeMillis(),
                    deviceId = activationManager.getDeviceId(),
                )
            )
            Toast.makeText(context, paymentRedirecting, Toast.LENGTH_SHORT).show()
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(orderResult.paymentUrl)))
            return
        }
        // 2) 回退：App 端自己构造 Yipay 支付 URL（旧逻辑，仅限已配置商户密钥）。
        val config = YipayConfig.DEFAULT
        if (!config.isMerchantKeyConfigured) {
            // H2：未配置商户密钥 → 禁用本地构造（不留假钥路径），提示失败。
            com.lxseek.chat.membership.MembershipSecrets.warnIfYipayKeyNotConfigured()
            Toast.makeText(context, paymentFailedText, Toast.LENGTH_SHORT).show()
            return
        }
        val manager = YipayPaymentManager()
        val outTradeNo = "lxchat_${System.currentTimeMillis()}"
        val returnUrl = "lxchat://yipay-callback"
        val paymentUrl = manager.buildPaymentUrl(
            config = config,
            outTradeNo = outTradeNo,
            amount = plan.amount,
            returnUrl = returnUrl,
        )
        PendingOrderStore(context).save(
            PendingOrderStore.PendingOrder(
                outTradeNo = outTradeNo,
                tier = MembershipTier.Premium,
                amount = plan.amount,
                timestamp = System.currentTimeMillis(),
                deviceId = activationManager.getDeviceId(),
            )
        )
        Toast.makeText(context, paymentRedirecting, Toast.LENGTH_SHORT).show()
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl)))
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_membership)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { MembershipStatusCard(status) }

            item {
                DeviceIdCardSection(
                    deviceIdDisplay = activationManager.getDeviceIdDisplay(),
                    // 已是付费版时不显示联网激活按钮
                    showRestoreButton = !status.isActive,
                    isRestoring = isRestoring,
                    restoreMessage = restoreMessage,
                    onRestore = {
                        scope.launch {
                            isRestoring = true
                            restoreMessage = null
                            val credential = activationManager.restoreActivation(activationManager.getDeviceId())
                            restoreMessage = if (credential != null) {
                                viewModel.membership.applyCredential(credential)
                                context.getString(R.string.membership_restore_success)
                            } else {
                                context.getString(R.string.membership_restore_failed)
                            }
                            isRestoring = false
                        }
                    },
                )
            }

            item {
                ActivationCodeSection(
                    code = activationCodeInput,
                    onCodeChange = {
                        activationCodeInput = it
                        activationResult = null
                    },
                    result = activationResult,
                    isActivating = isActivating,
                    onActivate = {
                        scope.launch {
                            isActivating = true
                            val result = activationManager.activate(activationCodeInput)
                            activationResult = result
                            if (result is ActivationResult.Success) {
                                activationCodeInput = ""
                                // After activation, refresh membership status so StatusCard syncs.
                                viewModel.membership.refresh()
                            }
                            isActivating = false
                        }
                    },
                )
            }

            // Restore: free user with no local credential — may be a reinstall.
            if (status.tier == MembershipTier.Free && !activationManager.hasActiveCredential()) {
                item {
                    RestoreMembershipSection(
                        isRestoring = isRestoring,
                        message = restoreMessage,
                        onRestore = {
                            scope.launch {
                                isRestoring = true
                                restoreMessage = null
                                val credential = activationManager.restoreActivation(activationManager.getDeviceId())
                                restoreMessage = if (credential != null) {
                                    viewModel.membership.applyCredential(credential)
                                    context.getString(R.string.membership_restore_success)
                                } else {
                                    context.getString(R.string.membership_restore_failed)
                                }
                                isRestoring = false
                            }
                        },
                    )
                }
            }

            // Free trial: only show when inactive and not used before.
            if ((status.tier == MembershipTier.Free || !status.isActive) && !trialUsed) {
                item {
                    FreeTrialSection(
                        isTrialing = isTrialing,
                        message = trialMessage,
                        onTrial = {
                            scope.launch {
                                isTrialing = true
                                trialMessage = null
                                val result = activationManager.trial()
                                trialMessage = when (result) {
                                    is ActivationResult.Success -> {
                                        viewModel.membership.refresh()
                                        context.getString(R.string.membership_trial_success)
                                    }
                                    ActivationResult.NetworkError ->
                                        context.getString(R.string.membership_activate_network_error)
                                    else -> context.getString(R.string.membership_trial_failed)
                                }
                                isTrialing = false
                            }
                        },
                    )
                }
            }

            // Renewal: active and expiring soon (within 3 days).
            if (status.isActive && isExpiringSoon(status)) {
                item {
                    RenewMembershipSection(
                        isRenewing = isRenewing,
                        message = renewMessage,
                        onRenew = {
                            // 二元制 + R2：续费 = 购买月度套餐（价格统一来自 PlanCatalog），
                            // 激活由服务器在剩余时长上累加（M5）。
                            scope.launch {
                                isRenewing = true
                                try {
                                    purchasePlan(PlanCatalog.monthly)
                                } finally {
                                    isRenewing = false
                                }
                            }
                        },
                    )
                }
            }


            // 套餐选择：非永久激活用户可见（免费/未激活可购买，付费可续费叠加时间）。
            // 永久激活用户隐藏整块套餐 UI（MembershipStatus.isLifetime 统一判定）。
            if (!status.isLifetime) {
                item {
                    PlanSelectionSection(

                        onPaid = { plan ->
                            scope.launch { purchasePlan(plan) }
                        },
                    )
                }
            }


            // About entry at the bottom.
            item {
                OutlinedButton(
                    onClick = onNavigateToAbout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.about_title))
                }
            }
        }
    }
}

// Section A: Status card

@Composable
private fun MembershipStatusCard(status: MembershipStatus) {
    val tierColor = tierAccentColor(status.tier)
    // 二元制：免费/付费两档（旧档位 Pro/Enterprise 由 parse 归一化，不会到达此处）。
    val tierLabel = if (status.tier == MembershipTier.Free) {
        stringResource(R.string.membership_status_free)
    } else {
        stringResource(R.string.membership_status_premium)
    }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (status.tier == MembershipTier.Free) Icons.Default.Star else Icons.Default.Verified,
                    contentDescription = null,
                    tint = tierColor,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = tierLabel,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = tierColor,
                )
            }

            if (status.isActive) {
                // Lifetime (permanent) members: show "永久有效", hide expiry date and source.
                if (status.isLifetime) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "永久有效",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    status.expiryTimestamp?.let { expiry ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.membership_expiry, dateFormatter.format(Date(expiry))),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (status.source.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val sourceLabel = when (status.source) {
                            LocalMembershipProvider.SOURCE_REDEMPTION_CODE ->
                                stringResource(R.string.membership_source_redemption)
                            LocalMembershipProvider.SOURCE_YIPAY ->
                                stringResource(R.string.membership_source_yipay)
                            else -> "Source: ${status.source}"
                        }
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.membership_upgrade_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 二元制：免费=灰色，付费=金色（旧档位统一归入付费色）。 */
private fun tierAccentColor(tier: MembershipTier): Color =
    if (tier == MembershipTier.Free) Color(0xFF9E9E9E) else Color(0xFFFFB300)

// Section C2: Plan selection（二元制：付费账户的不同时长买法）

/**
 * 套餐选择卡片 + 立即支付按钮（套餐统一定义在 [PlanCatalog]，R2）。
 *
 * 用户选中一个套餐后点"立即支付"，调 [ActivationManager.createPaymentOrder] 传对应 planId。
 * 云端下单成功直接打开返回的支付 URL；失败回退到 App 端自己构造 Yipay URL
 * （[onPaid] 内处理，未配置商户密钥时禁用回退，见 H2）。
 */
@Composable
private fun PlanSelectionSection(

    onPaid: (PlanCatalog.Plan) -> Unit,
) {
    // 默认选中月度套餐
    var selectedPlanId by rememberSaveable { mutableStateOf(PlanCatalog.DEFAULT_PLAN_ID) }
    val selectedPlan = PlanCatalog.plans.firstOrNull { it.id == selectedPlanId } ?: PlanCatalog.monthly

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.membership_plan_select),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        // 限时特惠促销标签（红色醒目）
        Text(
            text = stringResource(R.string.membership_plan_promotion),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        PlanCatalog.plans.forEach { plan ->
            PlanOptionCard(
                plan = plan,
                selected = plan.id == selectedPlanId,
                onClick = { selectedPlanId = plan.id },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(
            onClick = { onPaid(selectedPlan) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
        ) {
            Text(
                text = stringResource(R.string.membership_plan_pay_now) + " · " + selectedPlan.price,
                color = Color.Black,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 单个套餐选项卡片（RadioButton 风格）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanOptionCard(
    plan: PlanCatalog.Plan,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) Color(0xFFFFB300) else MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = borderColor,
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                    selectedColor = Color(0xFFFFB300),
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            // 套餐名称
            Text(
                text = plan.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 原价划掉 + 促销价 + 月均价
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 原价划掉
                    Text(
                        text = plan.originalPrice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        textDecoration = TextDecoration.LineThrough,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // 促销价（醒目）
                    Text(
                        text = plan.price,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (selected) Color(0xFFFFB300) else MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = plan.perMonth,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Section D: Device ID card (read-only display)

/**
 * Device ID display section (read-only).
 *
 * 当 [showRestoreButton] 为 true（即用户当前不是付费版）时，在设备身份证右边显示
 * "联网激活"按钮：调 [ActivationManager.restoreActivation] 用设备 ID 查服务端恢复凭证。
 * 按钮点击后显示 loading（[isRestoring]），成功显示"激活成功"，失败显示"未找到激活记录"。
 */
@Composable
private fun DeviceIdCardSection(
    deviceIdDisplay: String,
    showRestoreButton: Boolean = false,
    isRestoring: Boolean = false,
    restoreMessage: String? = null,
    onRestore: () -> Unit = {},
) {
    val successText = stringResource(R.string.membership_restore_success)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.membership_device_id),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = deviceIdDisplay,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.membership_device_id_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showRestoreButton) {
                    OutlinedButton(
                        onClick = onRestore,
                        enabled = !isRestoring,
                    ) {
                        Text(
                            text = if (isRestoring) {
                                stringResource(R.string.membership_restoring)
                            } else {
                                stringResource(R.string.membership_restore_button)
                            },
                        )
                    }
                }
            }
            // 联网激活结果反馈
            if (showRestoreButton && restoreMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val color = if (restoreMessage == successText) {
                    Color(0xFF2E7D32) // green
                } else {
                    Color(0xFFC62828) // red
                }
                Text(
                    text = restoreMessage,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// Section E: Activation code input

@Composable
private fun ActivationCodeSection(
    code: String,
    onCodeChange: (String) -> Unit,
    result: ActivationResult?,
    isActivating: Boolean,
    onActivate: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.membership_activate_code),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.membership_activate_code_hint)) },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onActivate,
            enabled = code.isNotBlank() && !isActivating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.membership_activate))
        }

        result?.let { ActivationResultFeedback(it) }
    }
}

@Composable
private fun ActivationResultFeedback(result: ActivationResult) {
    Spacer(modifier = Modifier.height(12.dp))
    when (result) {
        is ActivationResult.Success -> {
            Text(
                text = stringResource(R.string.membership_activate_success),
                color = Color(0xFF2E7D32), // green
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ActivationResult.InvalidCode -> {
            Text(
                text = stringResource(R.string.membership_activate_invalid),
                color = Color(0xFFC62828), // red
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ActivationResult.AlreadyUsed -> {
            Text(
                text = stringResource(R.string.membership_activate_already_used),
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ActivationResult.Expired -> {
            Text(
                text = stringResource(R.string.membership_activate_expired),
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ActivationResult.NetworkError -> {
            Text(
                text = stringResource(R.string.membership_activate_network_error),
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// Section F: Free trial (3 days)

/** Restore membership section: re-activate via device ID after reinstall. */
@Composable
private fun RestoreMembershipSection(
    isRestoring: Boolean,
    message: String?,
    onRestore: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.membership_restore_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRestore,
            enabled = !isRestoring,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isRestoring) {
                    stringResource(R.string.membership_restoring)
                } else {
                    stringResource(R.string.membership_restore_button)
                },
            )
        }
        message?.let {
            Spacer(modifier = Modifier.height(8.dp))
            val color = if (it == stringResource(R.string.membership_restore_success)) {
                Color(0xFF2E7D32)
            } else {
                Color(0xFFC62828)
            }
            Text(text = it, color = color, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Free trial section (3 days). */
@Composable
private fun FreeTrialSection(
    isTrialing: Boolean,
    message: String?,
    onTrial: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.membership_trial_button),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onTrial,
            enabled = !isTrialing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
        ) {
            Text(
                text = if (isTrialing) {
                    stringResource(R.string.membership_trial_in_progress)
                } else {
                    stringResource(R.string.membership_trial_button)
                },
                color = Color.White,
            )
        }
        message?.let {
            Spacer(modifier = Modifier.height(8.dp))
            val color = if (it == stringResource(R.string.membership_trial_success)) {
                Color(0xFF2E7D32)
            } else {
                Color(0xFFC62828)
            }
            Text(
                text = it,
                color = color,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// Section G: Renew membership

/**
 * Renewal section: active but expiring soon.
 * 二元制：续费无档位参数（购买月度套餐，服务器在剩余时长上累加，M5）。
 */
@Composable
private fun RenewMembershipSection(
    isRenewing: Boolean,
    message: String?,
    onRenew: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.membership_renew_button),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRenew,
            enabled = !isRenewing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
        ) {
            Text(
                text = if (isRenewing) {
                    stringResource(R.string.membership_renewing)
                } else {
                    stringResource(R.string.membership_renew_button)
                },
                color = Color.Black,
            )
        }
        message?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** True if the membership expires within [RENEW_THRESHOLD_DAYS] days. */
private fun isExpiringSoon(status: MembershipStatus): Boolean {
    val expiry = status.expiryTimestamp ?: return false
    val remaining = expiry - System.currentTimeMillis()
    return remaining in 0..(RENEW_THRESHOLD_DAYS * MILLIS_PER_DAY)
}

private const val RENEW_THRESHOLD_DAYS = 3L
private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
