package com.amarhelper.console.data.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun `bare host gets https scheme`() {
        val result = UrlValidator.validate("api.example.com")
        assertTrue(result is UrlValidation.Valid)
        assertEquals("https://api.example.com", (result as UrlValidation.Valid).normalized)
    }

    @Test
    fun `trailing slash is stripped so retrofit base urls stay consistent`() {
        val result = UrlValidator.validate("https://api.example.com/") as UrlValidation.Valid
        assertEquals("https://api.example.com", result.normalized)
    }

    @Test
    fun `port and path survive normalization`() {
        val result = UrlValidator.validate("https://box.tail1234.ts.net:4096/api") as UrlValidation.Valid
        assertEquals("https://box.tail1234.ts.net:4096/api", result.normalized)
    }

    @Test
    fun `cleartext to a public host is rejected`() {
        val result = UrlValidator.validate("http://example.com")
        assertTrue(result is UrlValidation.Invalid)
    }

    @Test
    fun `cleartext to a tailscale magicdns host is allowed`() {
        val result = UrlValidator.validate("http://vps.tail1234.ts.net:3000")
        assertTrue(result is UrlValidation.Valid)
        assertEquals(null, (result as UrlValidation.Valid).warning)
    }

    @Test
    fun `cleartext to localhost is allowed`() {
        assertTrue(UrlValidator.validate("http://localhost:8000") is UrlValidation.Valid)
    }

    @Test
    fun `cleartext to a raw tailnet ip is allowed without a caveat`() {
        // The address Tailscale shows first must work: it is unreachable from the
        // internet and the traffic is already WireGuard-encrypted.
        val result = UrlValidator.validate("http://100.87.52.65:4096")
        assertTrue(result is UrlValidation.Valid)
        assertEquals(null, (result as UrlValidation.Valid).warning)
    }

    @Test
    fun `addresses just outside the tailnet range are still treated as public`() {
        assertTrue(UrlValidator.validate("http://100.63.0.1:4096") is UrlValidation.Invalid)
        assertTrue(UrlValidator.validate("http://100.128.0.1:4096") is UrlValidation.Invalid)
    }

    @Test
    fun `a bare tailnet host defaults to http rather than a failing https handshake`() {
        val magicDns = UrlValidator.validate("vmi3507647.tail7bf6b1.ts.net:4096")
        assertEquals("http://vmi3507647.tail7bf6b1.ts.net:4096", (magicDns as UrlValidation.Valid).normalized)

        val tailnetIp = UrlValidator.validate("100.87.52.65:3000")
        assertEquals("http://100.87.52.65:3000", (tailnetIp as UrlValidation.Valid).normalized)

        val lan = UrlValidator.validate("192.168.1.50:4096")
        assertEquals("http://192.168.1.50:4096", (lan as UrlValidation.Valid).normalized)
    }

    @Test
    fun `private lan address is treated as private`() {
        val result = UrlValidator.validate("http://192.168.1.50:4096")
        assertTrue(result is UrlValidation.Valid)
    }

    @Test
    fun `empty input is rejected`() {
        assertTrue(UrlValidator.validate("   ") is UrlValidation.Invalid)
    }

    @Test
    fun `non http scheme is rejected`() {
        assertTrue(UrlValidator.validate("ftp://example.com") is UrlValidation.Invalid)
    }

    @Test
    fun `garbage input is rejected rather than crashing`() {
        assertTrue(UrlValidator.validate("http://") is UrlValidation.Invalid)
    }
}
