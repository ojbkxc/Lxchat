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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxseek.chat.R
import com.lxseek.chat.membership.ActivationManager
import com.lxseek.chat.membership.ActivationResult
import com.lxseek.chat.membership.LocalMembershipProvider
import com.lxseek.chat.membership.MembershipStatus
import com.lxseek.chat.membership.MembershipTier
import com.lxseek.chat.membership.PendingOrderStore
import com.lxseek.chat.membership.RedemptionResult
import com.lxseek.chat.membership.RemoteCloudApi
import com.lxseek.chat.membership.YipayConfig
import com.lxseek.chat.membership.YipayPaymentManager
import com.lxseek.chat.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Membership settings page: status card + redemption code input + yipay upgrade entry.
 *
 * Three sections rendered in a [LazyColumn]:
 *  - **Status card** 鈥?current tier (color-coded), expiry, source, or upgrade prompt for Free.
 *  - **Redemption code** 鈥?text field + redeem button + result feedback.
 *  - **Yipay upgrade** 鈥?Premium/Pro upgrade buttons (Free only); payment is server-gated so a
 *    toast is shown for now.
 *
 * The page reads [ChatViewModel.membership] (a [com.lxseek.chat.viewmodel.MembershipViewModelApi])
 * and never touches other ViewModel state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMembershipPage(viewModel: ChatViewModel, onBack: () -> Unit) {
    val status by viewModel.membership.status.collectAsState()
    var codeInput by remember { mutableStateOf("") }
    var redeemResult by remember { mutableStateOf<RedemptionResult?>(null) }
    var isRedeeming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val paymentRedirecting = stringResource(R.string.membership_payment_redirecting)

    // 璁惧韬唤璇?+ 婵€娲荤爜浣撶郴锛氱敤 RemoteCloudApi 璋冭繙绋嬫縺娲绘湇鍔★紙activate.lxseek.com锛夈€?    // LocalCloudApi 淇濈暀浣滀负绂荤嚎鍏滃簳锛堟棤缃戠粶鏃舵湰鍦伴獙璇佺鍚嶏級锛屼絾涓昏矾寰勮蛋杩滅▼銆?    val activationManager = remember {
        ActivationManager(RemoteCloudApi(context), context)
    }
    var activationCodeInput by remember { mutableStateOf("") }
    var activationResult by remember { mutableStateOf<ActivationResult?>(null) }
    var isActivating by remember { mutableStateOf(false) }

    // 鍏嶈垂璇曠敤鐘舵€?    var isTrialing by remember { mutableStateOf(false) }
    var trialMessage by remember { mutableStateOf<String?>(null) }
    // 鏈湴鏍囪锛氭槸鍚﹀凡鐢ㄨ繃鍏嶈垂璇曠敤銆傚彧璇讳竴娆★紙璇曠敤鎴愬姛鍚?status.tier 鍙樺寲浼氳璇曠敤鍖烘秷澶憋級銆?    val trialUsed = remember { activationManager.isTrialUsed() }

    // 缁垂鐘舵€?    var isRenewing by remember { mutableStateOf(false) }
    var renewMessage by remember { mutableStateOf<String?>(null) }

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
                                // 婵€娲绘垚鍔熷悗鍒锋柊浼氬憳鐘舵€侊紝璁?StatusCard 鍚屾銆?                                viewModel.membership.refresh()
                            }
                            isActivating = false
                        }
                    },
                )
            }

            // 鍏嶈垂璇曠敤 3 澶╋細浠呭湪鏈縺娲讳笖鏈敤杩囪瘯鐢ㄦ椂鏄剧ず銆?            if ((status.tier == MembershipTier.Free || !status.isActive) && !trialUsed) {
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

            // 缁垂锛氬凡婵€娲讳絾蹇埌鏈燂紙3 澶╁唴锛夋椂鏄剧ず銆?            if (status.isActive && isExpiringSoon(status)) {
                item {
                    RenewMembershipSection(
                        isRenewing = isRenewing,
                        message = renewMessage,
                        onRenew = { tier ->
                            val amount = when (tier) {
                                MembershipTier.Premium -> "0.30"
                                MembershipTier.Pro -> "0.50"
                                else -> return@RenewMembershipSection
                            }
                            val config = YipayConfig.DEFAULT
                            val manager = YipayPaymentManager()
                            val outTradeNo = "lxchat_renew_${System.currentTimeMillis()}"
                            val returnUrl = "lxchat://yipay-callback"
                            val paymentUrl = manager.buildPaymentUrl(
                                config = config,
                                outTradeNo = outTradeNo,
                                amount = amount,
                                returnUrl = returnUrl,
                            )
                            // 淇濆瓨 PendingOrder锛堝甫 deviceId锛夛紝onResume 鍏滃簳鏌ヨ鐢ㄣ€?                            PendingOrderStore(context).save(
                                PendingOrderStore.PendingOrder(
                                    outTradeNo = outTradeNo,
                                    tier = tier,
                                    amount = amount,
                                    timestamp = System.currentTimeMillis(),
                                    deviceId = activationManager.getDeviceId(),
                                )
                            )
                            Toast.makeText(context, paymentRedirecting, Toast.LENGTH_SHORT).show()
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl))
                            context.startActivity(intent)
                        },
                    )
                }
            }

            item {
                RedemptionCodeSection(
                    code = codeInput,
                    onCodeChange = {
                        codeInput = it
                        redeemResult = null
                    },
                    result = redeemResult,
                    isRedeeming = isRedeeming,
                    onRedeem = {
                        scope.launch {
                            isRedeeming = true
                            val result = viewModel.membership.redeemCode(codeInput)
                            redeemResult = result
                            if (result is RedemptionResult.Valid) codeInput = ""
                            isRedeeming = false
                        }
                    },
                )
            }

            if (status.tier == MembershipTier.Free || !status.isActive) {
                item {
                    YipayUpgradeSection(
                        onUpgrade = { tier ->
                            val amount = when (tier) {
                                MembershipTier.Premium -> "0.30"
                                MembershipTier.Pro -> "0.50"
                                else -> return@YipayUpgradeSection
                            }
                            val config = YipayConfig.DEFAULT
                            val manager = YipayPaymentManager()
                            val outTradeNo = "lxchat_${System.currentTimeMillis()}"
                            val returnUrl = "lxchat://yipay-callback"
                            val paymentUrl = manager.buildPaymentUrl(
                                config = config,
                                outTradeNo = outTradeNo,
                                amount = amount,
                                returnUrl = returnUrl,
                            )
                            // Persist the pending order so onResume can ask the activation
                            // server to confirm payment if the DeepLink callback is lost.
                            // deviceId is included so activateByOrder can bind it to the credential.
                            PendingOrderStore(context).save(
                                PendingOrderStore.PendingOrder(
                                    outTradeNo = outTradeNo,
                                    tier = tier,
                                    amount = amount,
                                    timestamp = System.currentTimeMillis(),
                                    deviceId = activationManager.getDeviceId(),
                                )
                            )
                            Toast.makeText(context, paymentRedirecting, Toast.LENGTH_SHORT).show()
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl))
                            context.startActivity(intent)
                        },
                    )
                }
            }

            if (status.isActive) {
                item {
                    OutlinedButton(
                        onClick = { scope.launch { viewModel.membership.revokeMembership() } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(stringResource(R.string.membership_revoke))
                    }
                }
            }
        }
    }
}

// 鈹€鈹€ Section A: Status card 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

@Composable
private fun MembershipStatusCard(status: MembershipStatus) {
    val tierColor = tierAccentColor(status.tier)
    val tierLabel = when (status.tier) {
        MembershipTier.Free -> stringResource(R.string.membership_status_free)
        MembershipTier.Premium -> stringResource(R.string.membership_status_premium)
        MembershipTier.Pro -> stringResource(R.string.membership_status_pro)
        MembershipTier.Enterprise -> "Enterprise"
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

private fun tierAccentColor(tier: MembershipTier): Color = when (tier) {
    MembershipTier.Free -> Color(0xFF9E9E9E) // gray
    MembershipTier.Premium -> Color(0xFFFFB300) // gold/amber
    MembershipTier.Pro -> Color(0xFF7E57C2) // deep purple
    MembershipTier.Enterprise -> Color(0xFF1565C0) // deep blue
}

// 鈹€鈹€ Section B: Redemption code input 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

@Composable
private fun RedemptionCodeSection(
    code: String,
    onCodeChange: (String) -> Unit,
    result: RedemptionResult?,
    isRedeeming: Boolean,
    onRedeem: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.membership_redeem_section_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.membership_redeem_code_hint)) },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRedeem,
            enabled = code.isNotBlank() && !isRedeeming,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.membership_redeem_button))
        }

        result?.let { RedemptionResultFeedback(it) }
    }
}

@Composable
private fun RedemptionResultFeedback(result: RedemptionResult) {
    Spacer(modifier = Modifier.height(12.dp))
    when (result) {
        is RedemptionResult.Valid -> {
            Text(
                text = stringResource(R.string.membership_redeem_success),
                color = Color(0xFF2E7D32), // green
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is RedemptionResult.Invalid -> {
            Text(
                text = stringResource(R.string.membership_redeem_invalid, result.reason),
                color = Color(0xFFC62828), // red
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        RedemptionResult.Expired -> {
            Text(
                text = stringResource(R.string.membership_redeem_expired),
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        RedemptionResult.AlreadyUsed -> {
            Text(
                text = stringResource(R.string.membership_redeem_already_used),
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// 鈹€鈹€ Section C: Yipay upgrade entry 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

@Composable
private fun YipayUpgradeSection(onUpgrade: (MembershipTier) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.membership_upgrade_prompt),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onUpgrade(MembershipTier.Premium) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
        ) {
            Text(stringResource(R.string.membership_upgrade_premium), color = Color.Black)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onUpgrade(MembershipTier.Pro) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E57C2)),
        ) {
            Text(stringResource(R.string.membership_upgrade_pro), color = Color.White)
        }
    }
}

// 鈹€鈹€ Section D: Device ID card (read-only display) 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

/**
 * 璁惧韬唤璇佹樉绀哄尯锛堝彧璇伙級銆? *
 * 鐢ㄦ埛鍙湪璁剧疆椤垫煡鐪嬫湰璁惧鐨勮韩浠借瘉鍙凤紝渚夸簬瀹㈡湇/婵€娲荤爜鍙戞斁鏂规牳瀵广€? * 韬唤璇佸彿鐢?[DeviceIdCard.getDeviceIdDisplay] 鐢熸垚锛岀粍鍚堝涓‖浠剁壒寰佸仛 SHA-256锛? * 涓嶅彲琚畝鍗曠鏀癸紱鍚庣画绉?NDK 杩涗竴姝ラ槻鐮磋В銆? */
@Composable
private fun DeviceIdCardSection(deviceIdDisplay: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
    }
}

// 鈹€鈹€ Section E: Activation code input 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

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

// 鈹€鈹€ Section F: Free trial (3 days) 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

/**
 * 鍏嶈垂璇曠敤 3 澶╁尯銆? *
 * 浠呭湪鏈縺娲讳笖鏈敤杩囪瘯鐢ㄦ椂鏄剧ず銆傜偣鍑诲悗璋?[ActivationManager.trial]锛? * 鏈嶅姟鍣ㄧ鍙?3 澶?Premium 鍑瘉銆? */
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

// 鈹€鈹€ Section G: Renew membership 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

/**
 * 缁垂鍖猴細宸叉縺娲讳絾蹇埌鏈熸椂鏄剧ず銆? *
 * 鐐瑰嚮鍚庡彂璧锋槗鏀粯鏀粯 鈫?DeepLink 鍥炶皟 鈫?鏈嶅姟鍣ㄧ‘璁?鈫?缁垂銆? */
@Composable
private fun RenewMembershipSection(
    isRenewing: Boolean,
    message: String?,
    onRenew: (MembershipTier) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.membership_renew_button),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onRenew(MembershipTier.Premium) },
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
