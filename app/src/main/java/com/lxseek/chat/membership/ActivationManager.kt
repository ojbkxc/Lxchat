package com.lxseek.chat.membership

import android.content.Context

/**
 * 婵€娲荤鐞嗗櫒锛氬皝瑁?[CloudApi] + 鏈湴楠岃瘉 + 璁惧韬唤璇佽幏鍙栥€? *
 * UI 灞傦紙濡?[com.lxseek.chat.ui.settings.SettingsMembershipPage]锛夊彧涓庢湰绫讳氦浜掞紝
 * 涓嶇洿鎺ユ帴瑙?[CloudApi] / [SignedCredential] / [DeviceIdCard]銆? *
 * - [activate]锛氭縺娲荤爜婵€娲伙紙鑱旂綉锛孭OST /api/activate_by_code锛夈€? * - [trial]锛氶娆″厤璐逛笁澶╄瘯鐢紙鑱旂綉锛孭OST /api/trial锛夈€? * - [activateByOrder]锛氳鍗曟縺娲伙紙鑱旂綉锛孭OST /api/activate_by_order锛屾湇鍔″櫒鏌ヨ鍗曠‘璁ゅ凡鏀粯锛夈€? * - [renew]锛氱画璐癸紙鑱旂綉锛孭OST /api/renew锛夈€? * - [verifyLocal]锛氱函绂荤嚎楠岃瘉锛岃鏈湴鍑瘉 鈫?楠岃瘉绛惧悕 + 璁惧 ID 鍖归厤 + 鏈繃鏈熴€? * - [verifyRemote]锛氳仈缃戦獙璇侊紙POST /api/verify锛夛紝鏈嶅姟鍣ㄧ‘璁ゅ嚟璇佹湁鏁堛€? * - [deactivate]锛氳В缁戞湰璁惧锛堟湰鍦版竻闄ゅ嚟璇侊級銆? * - [getDeviceIdDisplay]锛氳幏鍙栨牸寮忓寲璁惧韬唤璇侊紝渚涜缃〉鏄剧ず銆? *
 * 榛樿鐢?[RemoteCloudApi]锛堣皟 activate.lxseek.com锛夈€傞渶瑕佺绾垮厹搴曟椂鍙敞鍏?[LocalCloudApi]銆? */
class ActivationManager(
    private val cloudApi: CloudApi,
    private val context: Context,
) {

    /** 婵€娲荤爜婵€娲伙紙鑱旂綉锛歅OST /api/activate_by_code锛夈€?*/
    suspend fun activate(code: String): ActivationResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.activate(code, deviceId)
    }

    /**
     * 棣栨鍏嶈垂涓夊ぉ璇曠敤锛堣仈缃戯細POST /api/trial锛夈€?     *
     * 浠?[RemoteCloudApi] 鏀寔锛涜嫢娉ㄥ叆鐨勬槸 [LocalCloudApi] 鍒欒繑鍥?[ActivationResult.NetworkError]銆?     * 鎴愬姛鍚庢湰鍦版爣璁板凡鐢ㄨ繃璇曠敤锛圼isTrialUsed]锛夛紝UI 鎹闅愯棌璇曠敤鎸夐挳銆?     */
    suspend fun trial(): ActivationResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        val result = if (cloudApi is RemoteCloudApi) {
            cloudApi.trial(deviceId)
        } else {
            ActivationResult.NetworkError
        }
        if (result is ActivationResult.Success) {
            markTrialUsed()
        }
        return result
    }

    /**
     * 璁㈠崟婵€娲伙紙鑱旂綉锛歅OST /api/activate_by_order锛夈€?     *
     * DeepLink 鍥炶皟鍚庤皟鐢細鏈嶅姟鍣ㄦ煡璁㈠崟纭宸叉敮浠?鈫?杩斿洖绛惧悕鍑瘉銆?     * 浠?[RemoteCloudApi] 鏀寔锛涜嫢娉ㄥ叆鐨勬槸 [LocalCloudApi] 鍒欒繑鍥?[ActivationResult.NetworkError]銆?     */
    suspend fun activateByOrder(outTradeNo: String): ActivationResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return if (cloudApi is RemoteCloudApi) {
            cloudApi.activateByOrder(deviceId, outTradeNo)
        } else {
            ActivationResult.NetworkError
        }
    }

    /**
     * 缁垂锛堣仈缃戯細POST /api/renew锛夈€?     *
     * 宸叉縺娲讳絾蹇埌鏈熸椂璋冪敤锛氭湇鍔″櫒鏌ヨ鍗曠‘璁ゅ凡鏀粯 鈫?杩斿洖鏂扮殑绛惧悕鍑瘉銆?     * 浠?[RemoteCloudApi] 鏀寔锛涜嫢娉ㄥ叆鐨勬槸 [LocalCloudApi] 鍒欒繑鍥?[ActivationResult.NetworkError]銆?     */
    suspend fun renew(outTradeNo: String): ActivationResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return if (cloudApi is RemoteCloudApi) {
            cloudApi.renew(deviceId, outTradeNo)
        } else {
            ActivationResult.NetworkError
        }
    }

    /**
     * 绂荤嚎楠岃瘉锛氳鍙栨湰鍦板嚟璇?鈫?楠岃瘉绛惧悕 + 璁惧 ID 鍖归厤 + 鏈繃鏈熴€?     *
     * 涓嶈仈缃戯紝App 鍚姩鏃跺揩閫熷垽瀹氫細鍛樼姸鎬佺敤銆傚鎵樼粰 [LocalCloudApi.verify]锛?     * 瀹冨彧璇绘湰鍦?SharedPreferences 涓殑鍑瘉骞剁敤 HMAC-SHA256 楠岃瘉绛惧悕銆?     *
     * 娉ㄦ剰锛氳繖瑕佹眰鏈嶅姟鍣ㄧ鍙戝嚟璇佹椂鐢ㄧ殑 HMAC 瀵嗛挜涓?[LocalCloudApi] 鐨勫瘑閽ヤ竴鑷淬€?     * 褰撳墠鏄繃娓℃柟妗堬紱鍚庣画鏈嶅姟鍣ㄦ敼鐢?RSA 闈炲绉扮鍚嶅悗锛孾SignedCredential] 闇€瑕?     * 鍔?RSA 楠岃瘉鏀寔锛屽眾鏃剁绾块獙璇佹敼鐢ㄥ叕閽ャ€?     */
    suspend fun verifyLocal(): VerifyResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        // Always use LocalCloudApi for offline verification, even if cloudApi is RemoteCloudApi.
        return LocalCloudApi(context).verify(deviceId)
    }

    /**
     * 鑱旂綉楠岃瘉锛圥OST /api/verify锛夛細鏈嶅姟鍣ㄧ‘璁ゅ嚟璇佹湁鏁堛€?     *
     * 浠?[RemoteCloudApi] 鏀寔锛涜嫢娉ㄥ叆鐨勬槸 [LocalCloudApi] 鍒欑瓑浠蜂簬 [verifyLocal]銆?     */
    suspend fun verifyRemote(): VerifyResult {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.verify(deviceId)
    }

    /** 瑙ｇ粦鏈澶囷紙鏈湴娓呴櫎鍑瘉锛夈€?*/
    suspend fun deactivate(): Boolean {
        val deviceId = DeviceIdCard.getDeviceId(context)
        return cloudApi.deactivate(deviceId)
    }

    /** 鑾峰彇褰撳墠璁惧韬唤璇侊紙瀹屾暣 32 浣?hex锛夛紝鐢ㄤ簬婵€娲荤爜缁戝畾銆?*/
    fun getDeviceId(): String = DeviceIdCard.getDeviceId(context)

    /** 鑾峰彇鏍煎紡鍖栬澶囪韩浠借瘉锛圶XXX-XXXX-XXXX-XXXX锛夛紝鐢ㄤ簬璁剧疆椤垫樉绀恒€?*/
    fun getDeviceIdDisplay(): String = DeviceIdCard.getDeviceIdDisplay(context)

    /** 鏄惁宸茬敤杩囧厤璐硅瘯鐢ㄣ€傛湰鍦?SharedPreferences 鏍囪锛孶I 鎹闅愯棌璇曠敤鎸夐挳銆?*/
    fun isTrialUsed(): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRIAL_USED, false)

    /** 鏍囪宸茬敤杩囪瘯鐢紙trial 鎴愬姛鍚庤皟鐢級銆?*/
    private fun markTrialUsed() {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TRIAL_USED, true).apply()
    }

    companion object {
        private const val PREF_NAME = "lxchat_activation"
        private const val KEY_TRIAL_USED = "trial_used"
    }
}
