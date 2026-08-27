package com.lxseek.chat.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 附加回复渠道（Telegram/Bark/Email）的发送记录，便于排查「回复到底发出去没有」。
 * 每次 [com.lxseek.chat.channel.ReplyChannel.send] 结束后写一条；保留最近 N 条，
 * 过老的记录由 [ChannelSendLogDao.prune] 定期清理。
 */
@Entity(tableName = "channel_send_log")
data class ChannelSendLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 渠道 id：telegram / bark / email。 */
    val channelId: String,
    /** 收件人（telegram=chat_id、email=邮箱、bark=标题）。 */
    val recipient: String,
    /** 是否发送成功。 */
    val ok: Boolean,
    /** 失败原因（成功时为 null）。 */
    val error: String?,
    /** 发送时间（epoch millis）。 */
    val sentAt: Long,
)

@Dao
interface ChannelSendLogDao {

    @Insert
    suspend fun insert(log: ChannelSendLogEntity)

    /** 最近 [limit] 条发送记录，最新在前。 */
    @Query("SELECT * FROM channel_send_log ORDER BY sentAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<ChannelSendLogEntity>

    /** 删除 [before] 之前的记录，防止表无限膨胀。 */
    @Query("DELETE FROM channel_send_log WHERE sentAt < :before")
    suspend fun prune(before: Long)
}
