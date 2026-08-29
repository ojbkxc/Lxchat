package com.lxseek.chat.membership

import android.content.Context
import android.os.Build
import android.provider.Settings

import java.security.MessageDigest

/**
 * 设备身份证生成器。
 *
 * 防破解思路：
 * - 组合多个硬件/系统特征做 SHA-256，单一字段被篡改不会改变最终哈希。
 * - ANDROID_ID 在 wiping data / factory reset 后会变，但同一设备同一用户保持稳定。
 * - Build.FINGERPRINT / MODEL / MANUFACTURER / BOARD / HARDWARE / SUPPORTED_ABIS 共同决定
 *   "这台设备"的指纹，刷机/换机后哈希必然变化。
 * - 后续可将本逻辑移到 NDK native 层进一步增加破解难度（见 [getDeviceId] 注释）。
 *
 * 该对象无状态、线程安全，可在任意线程调用。
 */
object DeviceIdCard {

    /** 完整设备身份证长度（SHA-256 取前 32 个 hex 字符）。 */
    private const val DEVICE_ID_HEX_LEN = 32

    /** 显示用截取长度（取前 16 个 hex 字符）。 */
    private const val DISPLAY_LEN = 16

    /** 显示分组大小（每 4 位一组，用 `-` 连接）。 */
    private const val DISPLAY_GROUP_SIZE = 4

    /**
     * 生成设备唯一标识（设备身份证）。
     *
     * 组合：ANDROID_ID + Build.FINGERPRINT + Build.MODEL + Build.MANUFACTURER +
     * Build.BOARD + Build.HARDWARE + SUPPORTED_ABIS，做 SHA-256 后取前 32 位 hex。
     *
     * 任意一个特征变化都会导致 deviceId 变化，从而让绑定该 deviceId 的激活码
     * 在新设备上验证失败（一码一机）。
     *
     * 后续可移到 NDK native 层进一步防破解：把拼接顺序/盐值/哈希算法藏在 .so 里，
     * 反编译只能看到 JNI 入口，无法直接拿到原始特征组合。
     */
    fun getDeviceId(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            // 极少数设备/ROM 读取 ANDROID_ID 会抛异常，回退到空串以保证不崩。
            ""
        }
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val board = Build.BOARD.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val abis = Build.SUPPORTED_ABIS.joinToString(",")

        // 用显式分隔符拼接，避免字段边界歧义。
        val raw = StringBuilder()
            .append(androidId).append('|')
            .append(fingerprint).append('|')
            .append(model).append('|')
            .append(manufacturer).append('|')
            .append(board).append('|')
            .append(hardware).append('|')
            .append(abis)
            .toString()

        return sha256Hex(raw).take(DEVICE_ID_HEX_LEN)
    }

    /**
     * 格式化显示：XXXX-XXXX-XXXX-XXXX（取前 16 位，4 位一组用 `-` 连接）。
     *
     * 用户可以在设置页看到自己设备的身份证号，便于客服/激活码发放方核对。
     * 仅取前 16 位是为了显示紧凑；完整 32 位仍是内部绑定用的 deviceId。
     */
    fun getDeviceIdDisplay(context: Context): String {
        val full = getDeviceId(context)
        val display = full.take(DISPLAY_LEN)
        return display.chunked(DISPLAY_GROUP_SIZE).joinToString("-")
    }

    /** SHA-256 → 小写 hex 字符串。 */
    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_TABLE[v ushr 4])
            sb.append(HEX_TABLE[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX_TABLE = "0123456789abcdef".toCharArray()
}