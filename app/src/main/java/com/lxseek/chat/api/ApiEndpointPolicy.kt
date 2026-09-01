package com.lxseek.chat.api

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * BYOK 端点安全策略 —— DNS 解析后的 IP 级屏障（借鉴 openclaw net-policy 的问题定义）。
 *
 * 既有 [HttpClient.guardCleartextCredentials] 是主机名级（字符串）判定：用户显式填写
 * "http://192.168.1.10" 时能正确放行自托管端点。但公网域名可被 DNS 解析到
 * 私网/链路本地/云元数据地址（DNS rebinding、域名钓鱼指向内网），主机名字符串
 * 看不出来 —— 本对象补上解析后的 IP 级校验，形成两级防线。
 *
 * 策略（pin public IPs，业界 SSRF 防护惯例，fail-closed）：
 *  - 主机名本身是本地端点（[HttpClient.isLocalHost] 为 true，如 IP 字面量、
 *    *.local、Tailscale）：原样放行，保留自托管（Ollama / nas / tailnet）合法路径；
 *  - 主机名是公网域名：仅保留解析出的公网地址；若全部地址均为私有/保留段，
 *    视为解析失败（抛 [UnknownHostException]），凭据永远不落到内网端点。
 *
 * 已知取舍：公网 DDNS 域名解析回家庭内网（如 mybox.duckdns.org → 192.168.x）
 * 的自托管用户会被屏障拦截。此类用户应改填内网 IP / *.local / Tailscale 名
 * （[HttpClient.isLocalHost] 放行）。错误信息会明确说明原因。
 */
object ApiEndpointPolicy {

    /** 解析后 IP 是否属于私有/保留地址段（IPv4 与 IPv6）。
     *  语义与 [HttpClient.isLocalHost] 的字符串级判定保持一致（含 Tailscale 段），
     *  另外补上字符串级无需覆盖的 0.0.0.0/8、受限广播与 IPv6 保留段。 */
    fun isPrivateAddress(addr: InetAddress): Boolean {
        val b = addr.address ?: return false
        return when (b.size) {
            4 -> isPrivateIpv4(b)
            16 -> isPrivateIpv6(b)
            else -> false
        }
    }

    /**
     * SSRF 屏障：在 DNS 解析完成后过滤地址列表。
     * 本地主机名原样放行；公网主机名只保留公网地址，全为私网时抛
     * [UnknownHostException]（对上层表现为"域名解析失败"，错误信息说明原因）。
     */
    fun screenResolvedAddresses(hostname: String, addresses: List<InetAddress>): List<InetAddress> {
        if (HttpClient.isLocalHost(hostname)) return addresses
        val publicOnly = addresses.filterNot { isPrivateAddress(it) }
        if (publicOnly.isEmpty()) {
            throw UnknownHostException(
                "$hostname resolved only to private/reserved addresses " +
                    "(${addresses.joinToString { it.hostAddress ?: "?" }}); refusing to " +
                    "connect a public endpoint to a private network target. If this is a " +
                    "self-hosted endpoint, use its LAN IP, a *.local name, or a Tailscale name."
            )
        }
        return publicOnly
    }

    // ── IPv4 ────────────────────────────────────────────────

    private fun isPrivateIpv4(b: ByteArray): Boolean {
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        return a0 == 0 ||                                   // 0.0.0.0/8 "本网络"保留（Android 上指本机）
            a0 == 10 ||                                     // 10.0.0.0/8 RFC1918
            a0 == 127 ||                                   // 127.0.0.0/8 loopback
            (a0 == 169 && a1 == 254) ||                     // 169.254.0.0/16 link-local（含 169.254.169.254 云元数据）
            (a0 == 172 && a1 in 16..31) ||                  // 172.16.0.0/12 RFC1918
            (a0 == 192 && a1 == 168) ||                     // 192.168.0.0/16 RFC1918
            (a0 == 100 && a1 in 64..127) ||                 // 100.64.0.0/10 CGNAT（Tailscale）
            a0 == 255                                       // 255.255.255.255 受限广播
    }

    // ── IPv6 ─────────────────────────────────────────────────

    private fun isPrivateIpv6(b: ByteArray): Boolean {
        fun u(i: Int) = b[i].toInt() and 0xFF
        // 前 15 字节全零 → ::（未指定地址）或 ::1（loopback）
        if ((0 until 15).all { u(it) == 0 }) {
            return u(15) == 0 || u(15) == 1
        }
        // ::ffff:0:0/96 IPv4-mapped —— 按内嵌 IPv4 判定
        // （Java 解析字面量时通常已折叠为 Inet4Address，此处防御 DNS 返回的映射形式）
        if ((0 until 10).all { u(it) == 0 } && u(10) == 0xFF && u(11) == 0xFF) {
            return isPrivateIpv4(byteArrayOf(b[12], b[13], b[14], b[15]))
        }
        if (u(0) == 0xFE && (u(1) and 0xC0) == 0x80) return true   // fe80::/10 link-local
        if ((u(0) and 0xFE) == 0xFC) return true                     // fc00::/7 ULA（含 Tailscale fd7a:115c:a1e0::/48）
        if (u(0) == 0xFF) return true                               // ff00::/8 组播保留
        return false
    }
}