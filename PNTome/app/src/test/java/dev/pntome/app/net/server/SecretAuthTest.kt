package dev.gsound.a.net.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the secret-comparison rule used by the HTTP auth interceptor (Req 5.3).
 *
 * The end-to-end `401`/`200` behavior over the Ktor test host is task 8.4; these pin the exact
 * authorization decision [isSecretAuthorized] makes, including the fail-closed cases, so the rule
 * is correct independently of the server harness.
 */
class SecretAuthTest {

    @Test
    fun `matching secret is authorized`() {
        assertTrue(isSecretAuthorized(presented = "s3cr3t", expected = "s3cr3t"))
    }

    @Test
    fun `mismatching secret is rejected`() {
        assertFalse(isSecretAuthorized(presented = "wrong", expected = "s3cr3t"))
    }

    @Test
    fun `null expected secret rejects any presented value (fail closed)`() {
        assertFalse(isSecretAuthorized(presented = "anything", expected = null))
        assertFalse(isSecretAuthorized(presented = null, expected = null))
    }

    @Test
    fun `missing presented header is rejected`() {
        assertFalse(isSecretAuthorized(presented = null, expected = "s3cr3t"))
    }

    @Test
    fun `empty presented secret does not match a non-empty expected`() {
        assertFalse(isSecretAuthorized(presented = "", expected = "s3cr3t"))
    }

    @Test
    fun `empty matches empty (length-equal, byte-equal)`() {
        assertTrue(isSecretAuthorized(presented = "", expected = ""))
    }

    @Test
    fun `secrets differing only in length are rejected`() {
        assertFalse(isSecretAuthorized(presented = "s3cr3t", expected = "s3cr3t0"))
    }
}
