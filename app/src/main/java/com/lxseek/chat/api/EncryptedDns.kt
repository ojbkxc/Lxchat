package com.lxseek.chat.api

import com.lxseek.chat.util.DebugLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Modes for the encrypted-DNS protection. Values are persisted in SettingsManager. */
object DnsProtection {
    const val MODE_OFF = "off"
    const val MODE_SELECTIVE = "selective"
    const val MODE_ALL = "all"
}

/**
 * OkHttp [okhttp3.Dns] that resolves selected hostnames through an encrypted
 * DoH (DNS-over-HTTPS) upstream, fail-open to the system resolver on any error.
 *
 * Modes:
 *  - [DnsProtection.MODE_OFF]: always delegate to [okhttp3.Dns.SYSTEM] (identical to today).
 *  - [DnsProtection.MODE_SELECTIVE]: DoH only for hosts matching [whitelist]; everything else goes
 *    to the system resolver.
 *  - [DnsProtection.MODE_ALL]: DoH for every public hostname.
 *
 * Safety guarantees (this is what keeps the app no-slower-or-broken than today):
 *  - **fail-open**: any timeout/exception during DoH resolution falls back to the system resolver.
 *  - **circuit breaker**: after [CIRCUIT_BREAK_THRESHOLD] consecutive DoH failures the resolver is
 *    temporarily bypassed for [CIRCUIT_BREAK_COOLDOWN_MS], so a flaky upstream never stalls traffic.
 *  - **upstream failover**: the primary DoH server is tried first, then the fallback, before giving up.
 *  - **recursion guard**: the DoH upstream hostnames themselves are always resolved via the system
 *    resolver, never through the client this resolver is installed on.
 *
 * Configuration is pushed in from [com.lxseek.chat.di.AppContainer] by collecting the settings
 * StateFlows; fields are @Volatile so a live OkHttp lookup picks up user edits without a rebuild.
 */
class EncryptedDns : okhttp3.Dns {

    @Volatile var mode: String = DnsProtection.MODE_OFF
    @Volatile var primaryUrl: String = DEFAULT_PRIMARY
    @Volatile var fallbackUrl: String = DEFAULT_FALLBACK
    @Volatile var whitelist: Set<String> = DEFAULT_WHITELIST

    // Dedicated resolver for DoH: uses the system DNS for the upstream host, with tight timeouts
    // so a slow DoH server cannot stall our own connection setup for long.
    private val dohClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .dns(okhttp3.Dns.SYSTEM)
        .build()

    private val dohMediaType = "application/dns-message".toMediaType()

    /** Consecutive DoH failures; opens the circuit when it reaches [CIRCUIT_BREAK_THRESHOLD]. */
    private val circuitFailures = AtomicInteger(0)

    @Volatile private var circuitOpenUntilMillis = 0L

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trimEnd('.')
        // Recursion guard: never resolve our own upstream hosts through ourselves.
        if (host.isBlank() || isUpstreamHost(host)) return okhttp3.Dns.SYSTEM.lookup(hostname)

        val useDoh = when (mode) {
            DnsProtection.MODE_ALL -> true
            DnsProtection.MODE_SELECTIVE -> matchesWhitelist(host, whitelist)
            else -> false
        }
        if (!useDoh) return okhttp3.Dns.SYSTEM.lookup(hostname)
        // L1：熔断打开期间的降级此前完全无日志，排障时无法区分「未启用 DoH」
        // 与「熔断降级」。recordFailure/isCircuitOpen 被 JVM 单测覆盖，日志加在
        // 这里（lookup 不进单测），避免未 mock 的 android.util.Log 炸测试。
        if (isCircuitOpen()) {
            DebugLog.d(TAG, "DoH circuit open, falling back to system DNS for $host")
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }

        return try {
            val addresses = resolveViaDoh(host)
            circuitFailures.set(0) // a full successful resolution resets the breaker
            if (addresses.isEmpty()) {
                DebugLog.w(TAG, "DoH empty answer for $host, falling back to system DNS")
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } else addresses
        } catch (t: Throwable) {
            recordFailure()
            // L1：DoH 失败降级补日志（保留 fail-open 行为不变）。
            DebugLog.w(TAG, "DoH resolution failed for $host (${t.javaClass.simpleName}), falling back to system DNS")
            // Fail-open: whatever happened with DoH, the system resolver is the safe fallback.
            try {
                okhttp3.Dns.SYSTEM.lookup(hostname)
            } catch (_: Throwable) {
                throw t
            }
        }
    }

    private fun resolveViaDoh(host: String): List<InetAddress> {
        // Ask for A first; only fall back to AAAA when there is no A record.
        val a = queryWithFailover(host, TYPE_A)
        if (a.isNotEmpty()) return a
        return queryWithFailover(host, TYPE_AAAA)
    }

    private fun queryWithFailover(host: String, qtype: Int): List<InetAddress> {
        val query = DnsWire.buildQuery(host, qtype)
        val response: ByteArray = try {
            postDoh(primaryUrl, query)
        } catch (t: Throwable) {
            // L1：主 DoH 上游失败切备用时补日志，便于定位上游抖动。
            DebugLog.w(TAG, "Primary DoH upstream failed, trying fallback: ${t.javaClass.simpleName}")
            postDoh(fallbackUrl, query)
        }
        return DnsWire.parseAddresses(response)
    }

    private fun postDoh(url: String, query: ByteArray): ByteArray {
        val call = dohClient.newCall(
            Request.Builder()
                .url(url)
                .header("Accept", "application/dns-message")
                .post(query.toRequestBody(dohMediaType))
                .build(),
        )
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("DoH HTTP ${response.code}: $url")
            return response.body?.bytes() ?: throw IOException("DoH empty response: $url")
        }
    }

    internal fun isUpstreamHost(host: String): Boolean {
        val h = host.lowercase()
        return listOf(primaryUrl, fallbackUrl).any { url ->
            runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() == h
        }
    }

    internal fun isCircuitOpen(): Boolean {
        val now = System.currentTimeMillis()
        return if (now < circuitOpenUntilMillis) {
            true
        } else {
            if (circuitOpenUntilMillis != 0L) circuitOpenUntilMillis = 0L
            false
        }
    }

    private fun recordFailure() {
        if (circuitFailures.incrementAndGet() >= CIRCUIT_BREAK_THRESHOLD) {
            circuitOpenUntilMillis = System.currentTimeMillis() + CIRCUIT_BREAK_COOLDOWN_MS
            circuitFailures.set(0)
        }
    }

    internal fun simulateFailures(count: Int) = repeat(count) { recordFailure() }

    companion object {
        private const val TAG = "EncryptedDns"

        const val DEFAULT_PRIMARY = "https://dns.alidns.com/dns-query"
        const val DEFAULT_FALLBACK = "https://cloudflare-dns.com/dns-query"
        /** Built-in protection set: Cloudflare's worker/pages spaces + OpenAI. Users can customize. */
        val DEFAULT_WHITELIST = setOf(
            "*.workers.dev", "*.pages.dev", "*.cloudflare.com",
            "api.openai.com", "*.openai.com",
            "activate.lxseek.com", "pay.lxseek.com",
        )

        const val DEFAULT_TIMEOUT_MS = 1500L
        const val CALL_TIMEOUT_MS = 2500L
        const val CIRCUIT_BREAK_THRESHOLD = 3
        const val CIRCUIT_BREAK_COOLDOWN_MS = 60_000L

        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
    }
}

/**
 * 单条主机匹配规则（R3 抽取）：精确 host、前导点后缀（`.openai.com`）或
 * 通配符 `*.suffix`。大小写不敏感。供 DNS 白名单与 HttpClient 的代理 bypass
 * 共用一处实现，避免多处复制导致行为漂移。
 */
internal fun matchesHostRule(host: String, rule: String): Boolean {
    val h = host.lowercase()
    val r = rule.trim().lowercase()
    if (r.isEmpty()) return false
    return when {
        // *.suffix → 匹配 suffix 本身或任意子域（suffix 前必须有点边界）。
        r.startsWith("*.") -> h == r.drop(2) || h.endsWith(r.drop(1))
        // .suffix → 同上：suffix 本身或任意子域（后缀自带点边界，防 evil suffix 伪造）。
        r.startsWith(".") -> h == r.drop(1) || h.endsWith(r)
        else -> h == r
    }
}

/**
 * True if [host] matches any entry in [entries]. Supported entry shapes:
 * - `*.suffix` — subdomains of suffix and the bare suffix
 * - `.suffix` — subdomains of suffix and the bare suffix (leading-dot form)
 * - exact host (case-insensitive)
 */
internal fun matchesWhitelist(host: String, entries: Collection<String>): Boolean {
    val h = host.lowercase()
    return entries.any { matchesHostRule(h, it) }
}


/** Minimal, dependency-free DNS wire-format encoder/decoder for A/AAAA records over DoH. */
internal object DnsWire {

    /**
     * Builds a standard DNS query packet (RD=1, QCLASS=IN) for [host] asking for [qtype].
     * Kept tiny and self-contained so the feature needs no third-party DNS library.
     */
    fun buildQuery(host: String, qtype: Int, id: Int = java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 0x10000)): ByteArray {
        val out = java.io.ByteArrayOutputStream(host.length + 32)
        // Header: ID, flags RD, QDCOUNT=1, AN/NS/AR=0.
        out.write((id ushr 8) and 0xFF); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        // QNAME: one length-prefixed label per label list, terminated by root.
        host.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            // L2 修复：单个 DNS label 长度必须为 1..63 字节（RFC 1035，长度前缀
            // 是单字节）。超长/空 label 会编码出畸形查询或被中间设备丢弃，此前
            // 未校验。此处抛出后由 lookup 的 catch 兜底降级系统解析（fail-open）。
            require(bytes.size in 1..63) {
                "Invalid DNS label in \"$host\": \"$label\" (${bytes.size} bytes, must be 1..63)"
            }
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)
        // QTYPE + QCLASS (IN).
        out.write((qtype ushr 8) and 0xFF); out.write(qtype and 0xFF)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    /**
     * Parses a DNS response and returns all A and AAAA addresses found in the answer section.
     * Skips the question section and follows name-compression pointers for record owner names.
     * Malformed packets or non-zero RCODE yield an empty list rather than throwing.
     */
    fun parseAddresses(msg: ByteArray): List<InetAddress> {
        if (msg.size < 12) return emptyList()
        val flags = ((msg[2].toInt() and 0xFF) shl 8) or (msg[3].toInt() and 0xFF)
        if (flags and 0x8000 == 0) return emptyList() // not a response
        if (flags and 0x000F != 0) return emptyList() // NXDOMAIN / SERVFAIL etc.
        val qd = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
        val an = ((msg[6].toInt() and 0xFF) shl 8) or (msg[7].toInt() and 0xFF)

        var i = 12
        repeat(qd) {
            i = skipNameAt(msg, i)
            i += 4 // QTYPE + QCLASS
        }

        val out = ArrayList<InetAddress>()
        repeat(an) {
            i = skipNameAt(msg, i)
            if (i + 10 > msg.size) return out // truncated
            val type = ((msg[i].toInt() and 0xFF) shl 8) or (msg[i + 1].toInt() and 0xFF)
            val rdlen = ((msg[i + 8].toInt() and 0xFF) shl 8) or (msg[i + 9].toInt() and 0xFF)
            i += 10
            if (i + rdlen > msg.size) return out // truncated
            when (type) {
                1 -> if (rdlen == 4) out.add(InetAddress.getByAddress(msg.copyOfRange(i, i + 4)))
                28 -> if (rdlen == 16) out.add(InetAddress.getByAddress(msg.copyOfRange(i, i + 16)))
            }
            i += rdlen
        }
        return out
    }

    /** Advances [start] past one (possibly compression-pointed) name; returns the next index. */
    private fun skipNameAt(b: ByteArray, start: Int): Int {
        var i = start
        while (i < b.size) {
            val len = b[i].toInt() and 0xFF
            if (len == 0) return i + 1
            if ((len and 0xC0) == 0xC0) return i + 2 // compression pointer
            i += 1 + len
        }
        return b.size
    }
}