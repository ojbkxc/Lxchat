package com.lxseek.chat.channel

import android.os.Build
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手写轻量 SMTP 客户端（纯 SSLSocket，零第三方依赖，APK 零增量）。
 *
 * 只覆盖纯文本发送的最小协议子集：EHLO → AUTH LOGIN → MAIL FROM → RCPT TO →
 * DATA → QUIT。支持 465 SSL（默认）、587 STARTTLS、无加密三种模式，覆盖国内
 * 邮箱（QQ/163/126/139…）与 Gmail/Outlook 的使用场景，无需申请第三方发信 API key。
 */
class SmtpSender(
    private val host: String,
    private val port: Int,
    private val security: Security = Security.SSL,
    private val username: String,
    private val password: String,
) {

    enum class Security { SSL, STARTTLS, NONE }

    /** 发送一封纯文本邮件，失败抛 [SmtpException]。内部已切到 IO 线程。 */
    suspend fun send(to: String, subject: String, text: String) = withContext(Dispatchers.IO) {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plain.soTimeout = IO_TIMEOUT_MS
            var sock: Socket = plain
            var reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            var writer = sock.getOutputStream()

            expect(reader, REPLY_GREETING)
            helo(reader, writer)

            if (security == Security.STARTTLS) {
                writeLine(writer, "STARTTLS")
                expect(reader, REPLY_GREETING)
                val ssl = SSLSocketFactory.getDefault().createSocket(sock, host, port, true) as SSLSocket
                ssl.soTimeout = IO_TIMEOUT_MS
                ssl.startHandshake()
                sock = ssl
                reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                writer = sock.getOutputStream()
                helo(reader, writer)
            }

            writeLine(writer, "AUTH LOGIN")
            expect(reader, REPLY_AUTH_CHALLENGE)
            writeLine(writer, b64(username))
            expect(reader, REPLY_AUTH_CHALLENGE)
            writeLine(writer, b64(password))
            expect(reader, REPLY_AUTH_OK)

            writeLine(writer, "MAIL FROM:<$username>")
            expect(reader, REPLY_OK)
            writeLine(writer, "RCPT TO:<$to>")
            expect(reader, REPLY_OK)

            writeLine(writer, "DATA")
            expect(reader, REPLY_DATA)
            val payload = buildString {
                append("From: $username").append(CRLF)
                append("To: $to").append(CRLF)
                append("Subject: ").append(encodeHeader(subject)).append(CRLF)
                append("MIME-Version: 1.0").append(CRLF)
                append("Content-Type: text/plain; charset=UTF-8").append(CRLF)
                append("Content-Transfer-Encoding: 8bit").append(CRLF)
                append(CRLF)
                append(text.replace("\n", CRLF))
            }
            // dot-stuffing：行首 "." 加倍，结束用单独一行 "."。
            val stuffed = payload.split(CRLF)
                .joinToString(CRLF) { if (it.startsWith(".")) ".$it" else it }
            writeLine(writer, stuffed)
            writeLine(writer, ".")
            expect(reader, REPLY_OK)

            writeLine(writer, "QUIT")
        } finally {
            runCatching { plain.close() }
        }
    }

    private fun helo(reader: BufferedReader, writer: OutputStream) {
        writeLine(writer, "EHLO ${Build.MODEL.replace(" ", "")}")
        expect(reader, REPLY_OK)
    }

    private fun expect(reader: BufferedReader, expected: Int): String {
        val reply = readReply(reader)
        val code = reply.take(3).toIntOrNull()
        if (code != expected) {
            throw SmtpException("SMTP $host:$port 期望 $expected，实际: $reply")
        }
        return reply
    }

    /** 读一条完整响应（处理 `250-xxx` 多行续行）。 */
    private fun readReply(reader: BufferedReader): String {
        val first = reader.readLine() ?: throw SmtpException("SMTP $host:$port 连接被关闭")
        if (first.length > 3 && first[3] == '-') {
            var line = reader.readLine()
            while (line != null && line.length > 3 && line[3] == '-') {
                line = reader.readLine()
            }
        }
        return first
    }

    private fun writeLine(writer: OutputStream, line: String) {
        writer.write((line + CRLF).toByteArray(Charsets.UTF_8))
        writer.flush()
    }

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** 非 ASCII 的 subject 用 RFC 2047 编码，避免中文标题乱码。 */
    private fun encodeHeader(s: String): String =
        if (s.all { it.code < 128 }) s else "=?UTF-8?B?${b64(s)}?="

    class SmtpException(message: String) : java.io.IOException(message)

    private companion object {
        const val CRLF = "\r\n"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val IO_TIMEOUT_MS = 15_000
        const val REPLY_GREETING = 220
        const val REPLY_OK = 250
        const val REPLY_AUTH_CHALLENGE = 334
        const val REPLY_AUTH_OK = 235
        const val REPLY_DATA = 354
    }
}

/** 常用邮箱服务商预设：填发件邮箱后自动带出 SMTP 服务器 / 端口 / 加密方式。 */
object SmtpProviderPresets {

    data class Preset(val host: String, val port: Int, val security: SmtpSender.Security)

    private val PRESETS = mapOf(
        "qq.com" to Preset("smtp.qq.com", 465, SmtpSender.Security.SSL),
        "foxmail.com" to Preset("smtp.foxmail.com", 465, SmtpSender.Security.SSL),
        "163.com" to Preset("smtp.163.com", 465, SmtpSender.Security.SSL),
        "126.com" to Preset("smtp.126.com", 465, SmtpSender.Security.SSL),
        "yeah.net" to Preset("smtp.yeah.net", 465, SmtpSender.Security.SSL),
        "139.com" to Preset("smtp.139.com", 465, SmtpSender.Security.SSL),
        "sohu.com" to Preset("smtp.sohu.com", 465, SmtpSender.Security.SSL),
        "sina.com" to Preset("smtp.sina.com", 465, SmtpSender.Security.SSL),
        "aliyun.com" to Preset("smtp.aliyun.com", 465, SmtpSender.Security.SSL),
        "gmail.com" to Preset("smtp.gmail.com", 465, SmtpSender.Security.SSL),
        "outlook.com" to Preset("smtp-mail.outlook.com", 587, SmtpSender.Security.STARTTLS),
        "hotmail.com" to Preset("smtp-mail.outlook.com", 587, SmtpSender.Security.STARTTLS),
        "live.com" to Preset("smtp-mail.outlook.com", 587, SmtpSender.Security.STARTTLS),
        "icloud.com" to Preset("smtp.mail.me.com", 587, SmtpSender.Security.STARTTLS),
        "aol.com" to Preset("smtp.aol.com", 587, SmtpSender.Security.STARTTLS),
        "mail.com" to Preset("smtp.mail.com", 465, SmtpSender.Security.SSL),
    )

    /** 根据发件邮箱域名返回预设；未知域名返回 null（用户手动填写）。 */
    fun suggestFor(email: String): Preset? {
        val domain = email.substringAfterLast('@', "").trim().lowercase()
        return PRESETS[domain]
    }
}
