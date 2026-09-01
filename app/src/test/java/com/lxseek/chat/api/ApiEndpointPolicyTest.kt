package com.lxseek.chat.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * [ApiEndpointPolicy] 是 DNS 解析后的 IP 级 SSRF 屏障：主机名级判定
 * （见 [HttpClientLocalHostTest]）只看字符串，防不住公网域名被解析到
 * 私网/元数据地址（DNS rebinding）。此处回归会导致两种静默故障之一：
 * 拦死自托管端点，或放行凭据发往内网目标。
 */
class ApiEndpointPolicyTest {

    // ── isPrivateAddress：IPv4 ────────────────────────────────

    @Test
    fun ipv4_privateRanges_arePrivate() {
        listOf(
            "127.0.0.1", "10.0.0.5", "172.16.0.1", "172.31.255.254", "192.168.1.10",
            "169.254.1.1", "169.254.169.254",   // 云元数据端点
            "100.64.0.1", "100.101.102.103", "100.127.255.254", // CGNAT（Tailscale）
            "0.0.0.0", "255.255.255.255",       // 保留/受限广播
        ).forEach { assertTrue(it, ApiEndpointPolicy.isPrivateAddress(InetAddress.getByName(it))) }
    }

    @Test
    fun ipv4_publicAddresses_areNotPrivate() {
        listOf(
            "8.8.8.8", "1.1.1.1",
            "100.63.255.255",                   // CGNAT 下边界之外
            "100.128.0.1",                      // CGNAT 上边界之外
            "172.15.0.1", "172.32.0.1",        // 172.16/12 两侧之外
            "11.0.0.1", "191.255.0.1",
        ).forEach { assertFalse(it, ApiEndpointPolicy.isPrivateAddress(InetAddress.getByName(it))) }
    }

    // ── isPrivateAddress：IPv6 ────────────────────────────────

    @Test
    fun ipv6_privateRanges_arePrivate() {
        listOf(
            "::",                               // 未指定地址
            "::1",                              // loopback
            "fe80::1",                          // link-local
            "fc00::1", "fd12:3456::1",          // fc00::/7 ULA
            "fd7a:115c:a1e0::1234",             // Tailscale ULA
            "ff02::1",                          // 组播
            "::ffff:192.168.1.1",               // IPv4-mapped（按内嵌 IPv4 判定）
            "::ffff:127.0.0.1",
        ).forEach { assertTrue(it, ApiEndpointPolicy.isPrivateAddress(InetAddress.getByName(it))) }
    }

    @Test
    fun ipv6_publicAddresses_areNotPrivate() {
        listOf(
            "2001:4860:4860::8888",              // Google DNS
            "2606:4700::1111",                  // Cloudflare DNS
            "2a00:1450:4001::81",               // 公网全局单播
        ).forEach { assertFalse(it, ApiEndpointPolicy.isPrivateAddress(InetAddress.getByName(it))) }
    }

    // ── 语义一致性：IP 级与主机名级判定对同一字面量不矛盾 ──────

    @Test
    fun ipLevel_agreesWithHostnameLevel_forIpLiterals() {
        // 字符串级判"本地"的 IP 字面量，IP 级必须也判"私有"，
        // 否则 DNS 屏障与明文凭据守卫会对同一端点给出相反结论。
        listOf(
            "127.0.0.1", "10.0.0.5", "192.168.1.10", "172.16.0.1", "172.31.255.254",
            "169.254.1.1", "100.64.0.1", "100.127.255.254",
        ).forEach {
            assertTrue(it, HttpClient.isLocalHost(it))
            assertTrue(it, ApiEndpointPolicy.isPrivateAddress(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "100.63.255.255", "100.128.0.1", "172.15.0.1", "172.32.0.1")
            .forEach {
                assertFalse(it, HttpClient.isLocalHost(it))
                assertFalse(it, ApiEndpointPolicy.isPrivateAddress(InetAddress.getByName(it)))
            }
    }

    // ── screenResolvedAddresses：屏障行为 ─────────────────────

    @Test
    fun localHostnames_passThroughUnfiltered() {
        // 自托管路径不受影响：本地主机名（含解析出公网地址的罕见情形）原样放行。
        val mixed = listOf(
            InetAddress.getByName("192.168.1.5"),
            InetAddress.getByName("8.8.8.8"),
        )
        listOf("192.168.1.5", "nas.local", "ollama", "box.ts.net", "fd7a:115c:a1e0::9")
            .forEach { host ->
                assertEquals(host, mixed, ApiEndpointPolicy.screenResolvedAddresses(host, mixed))
            }
    }

    @Test
    fun publicHostname_keepsOnlyPublicAddresses() {
        // rebinding：公网域名解析出混合地址 → 只保留公网，连接继续但不落内网。
        val addresses = listOf(
            InetAddress.getByName("10.0.0.8"),        // 私网，应被滤除
            InetAddress.getByName("93.184.216.34"),  // 公网，保留
            InetAddress.getByName("192.168.0.9"),    // 私网，应被滤除
        )
        val screened = ApiEndpointPolicy.screenResolvedAddresses("rebind.example.com", addresses)
        assertEquals(listOf(InetAddress.getByName("93.184.216.34")), screened)
    }

    @Test
    fun publicHostname_resolvingOnlyPrivate_throws() {
        // 域名钓鱼/元数据：公网域名全部解析到私网 → 等同解析失败（fail-closed）。
        val addresses = listOf(
            InetAddress.getByName("169.254.169.254"),
            InetAddress.getByName("10.1.2.3"),
        )
        try {
            ApiEndpointPolicy.screenResolvedAddresses("metadata-fish.example.com", addresses)
            throw AssertionError("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            // 错误信息须包含主机名与原因，给用户/诊断页可读的失败语义。
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("metadata-fish.example.com"))
        }
    }
}