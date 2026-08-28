package com.lxseek.chat.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class EncryptedDnsTest {

    // ── Whitelist matching ───────────────────────────────────────────

    @Test
    fun `wildcard star suffix matches subdomains and the bare domain`() {
        val rules = setOf("*.workers.dev")
        assertTrue(matchesWhitelist("api.workers.dev", rules))
        assertTrue(matchesWhitelist("a.b.workers.dev", rules))
        assertTrue(matchesWhitelist("workers.dev", rules))
        assertFalse(matchesWhitelist("workers.devevil.com", rules))
        assertFalse(matchesWhitelist("other.com", rules))
    }

    @Test
    fun `leading-dot suffix matches subdomains but not spoofed suffixes`() {
        val rules = setOf(".openai.com")
        assertTrue(matchesWhitelist("api.openai.com", rules))
        assertTrue(matchesWhitelist("chat.openai.com", rules))
        assertTrue(matchesWhitelist("openai.com", rules))
        assertFalse(matchesWhitelist("evilopenai.com", rules))
        assertFalse(matchesWhitelist("openai.com.evil.com", rules))
    }

    @Test
    fun `exact host is case-insensitive`() {
        val rules = setOf("API.OpenAI.com")
        assertTrue(matchesWhitelist("api.openai.com", rules))
        assertFalse(matchesWhitelist("api.openai.com.evil.com", rules))
    }

    // ── DNS wire encode / decode ─────────────────────────────────────

    @Test
    fun `buildQuery produces a well-formed header and question`() {
        val q = DnsWire.buildQuery("example.com", 1)
        val len = q.length
        assertTrue(len > 12)
        // QDCOUNT == 1
        assertEquals(0, q[4].toInt() and 0xFF)
        assertEquals(1, q[5].toInt() and 0xFF)
        val qtype = ((q[len - 4].toInt() and 0xFF) shl 8) or (q[len - 3].toInt() and 0xFF)
        assertEquals(1, qtype) // A
        val qclass = ((q[len - 2].toInt() and 0xFF) shl 8) or (q[len - 1].toInt() and 0xFF)
        assertEquals(1, qclass) // IN
    }

    @Test
    fun `parseAddresses returns an A record`() {
        val msg = buildResponse("example.com.", listOf(4 to byteArrayOf(93, 184, -40, 34)))
        val addrs = DnsWire.parseAddresses(msg)
        assertEquals(listOf(InetAddress.getByAddress(byteArrayOf(93, 184, -40, 34))), addrs)
    }

    @Test
    fun `parseAddresses returns an AAAA record`() {
        val ipv6 = byteArrayOf(0x26, 0x06.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1)
        val msg = buildResponse("example.com.", listOf(28 to ipv6))
        val addrs = DnsWire.parseAddresses(msg)
        assertEquals(listOf(InetAddress.getByAddress(ipv6)), addrs)
    }

    @Test
    fun `parseAddresses is empty on NXDOMAIN`() {
        val msg = buildResponse("missing.example.", listOf(1 to byteArrayOf(93, 184, -40, 34)), rcode = 3)
        assertTrue(DnsWire.parseAddresses(msg).isEmpty())
    }

    @Test
    fun `parseAddresses ignores non-address records inside answers`() {
        // TXT followed by A in the answer section: only the A is returned.
        val rdata = byteArrayOf(3).let { txt -> txt + byteArrayOf(1, 2, 3) } // TXT "\u0001\u0002\u0003"
        val msg = buildResponse("example.com.", listOf(16 to rdata, 1 to byteArrayOf(1, 2, 3, 4)))
        val addrs = DnsWire.parseAddresses(msg)
        assertEquals(listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))), addrs)
    }

    // ── Circuit breaker ──────────────────────────────────────────────

    @Test
    fun `breaker opens after threshold failures and stays closed below it`() {
        val dns = EncryptedDns()
        dns.simulateFailures(EncryptedDns.CIRCUIT_BREAK_THRESHOLD - 1)
        assertFalse(dns.isCircuitOpen())
        dns.simulateFailures(1)
        assertTrue(dns.isCircuitOpen())
    }

    @Test
    fun `upstream hostname is not routable through the resolver itself`() {
        val dns = EncryptedDns()
        dns.mode = DnsProtection.MODE_ALL
        // primary host must be recognized as an upstream (recursion guard), even in ALL mode
        assertTrue(dns.isUpstreamHost("dns.alidns.com"))
        assertTrue(dns.isUpstreamHost("cloudflare-dns.com"))
        assertFalse(dns.isUpstreamHost("api.openai.com"))
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun buildName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        name.trimEnd('.').split('.').forEach { label ->
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size)
            out.write(b, 0, b.size)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun buildResponse(
        questionName: String,
        answers: List<Pair<Int, ByteArray>>,
        rcode: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // header
        out.write(0x12); out.write(0x34)
        val flags = 0x8180 or rcode
        out.write((flags shr 8) and 0xFF); out.write(flags and 0xFF)
        out.write(0); out.write(1) // qd = 1
        out.write(0); out.write(answers.size) // an
        out.write(0); out.write(0)
        out.write(0); out.write(0)
        // question
        out.write(buildName(questionName))
        out.write(0); out.write(1) // A
        out.write(0); out.write(1) // IN
        // answers
        for ((type, rdata) in answers) {
            out.write(buildName(questionName))
            out.write(type shr 8); out.write(type and 0xFF)
            out.write(0); out.write(1) // class IN
            out.write(0); out.write(0); out.write(0); out.write(60) // ttl
            out.write(rdata.size shr 8); out.write(rdata.size and 0xFF) // rdlen
            out.write(rdata, 0, rdata.size)
        }
        return out.toByteArray()
    }
}