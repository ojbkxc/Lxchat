package com.lxseek.chat.baby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.lxseek.chat.LxChatApplication
import kotlinx.coroutines.flow.first

/**
 * 婴儿监护开关协调器（对齐 PetOverlayController 模式）：
 * 持久化开关 + 驱动 [BabyMonitorService]，供设置页与启动恢复共用同一套语义。
 *
 * 开启前置条件（不满足时保持 disabled 并让 UI 引导）：
 *  - RECORD_AUDIO 已授权；
 *  - YAMNet 模型已下载（[BabyModelManager.isDownloaded]）。
 */
object BabyMonitorController {

    private fun application(context: Context): LxChatApplication =
        context.applicationContext as LxChatApplication

    private fun store(context: Context): BabyMonitorStore =
        application(context).container.babyMonitorStore

    fun hasRecordPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context.applicationContext, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    suspend fun isEnabled(context: Context): Boolean =
        store(context).config.first().enabled

    /**
     * 持久化 [enabled] 并启动/停止服务。
     *
     * @return true 表示已应用；false 表示前置条件缺失（权限/模型），开关保持关闭，
     *         调用方应引导授权 / 去下载模型。
     */
    suspend fun setEnabled(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        if (enabled) {
            if (!hasRecordPermission(app)) {
                store(app).setEnabled(false)
                return false
            }
            val manager = BabyModelManager.getInstance(app)
            if (!manager.isDownloaded()) {
                store(app).setEnabled(false)
                return false
            }
            store(app).setEnabled(true)
            BabyMonitorService.createChannel(app)
            BabyMonitorService.start(app)
        } else {
            store(app).setEnabled(false)
            BabyMonitorService.stop(app)
        }
        return true
    }
}
