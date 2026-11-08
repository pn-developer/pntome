package dev.pntome.app.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [Redact] (Req 8.4, design §5.7).
 *
 * The property that matters for the no-secrets-in-logs guarantee is that the placeholder **never
 * contains the raw secret** and has a **stable, non-reversible** shape. These pin the three
 * cases (null / empty / present) plus the "never leaks" invariant across a range of inputs.
 */
class RedactTest {

    @Test
    fun `null renders as the null marker`() {
        assertEquals("<null>", Redact.redact(null))
    }

    @Test
    fun `empty renders as the empty marker`() {
        assertEquals("<empty>", Redact.redact(""))
    }

    @Test
    fun `short value renders as masked length and omits the raw value`() {
        val secret = "abc"
        val out = Redact.redact(secret)

        assertEquals("***(len=3)", out)
        assertFalse("placeholder must not contain the raw secret", out.contains(secret))
    }

    @Test
    fun `long value renders as masked length and omits the raw value`() {
        // Realistic shape: 32 random bytes Base64Url ≈ 43 chars.
        val secret = "dGhpcy1pcy1hLXZlcnktbG9uZy1zZWNyZXQtdmFsdWU"
        val out = Redact.redact(secret)

        assertEquals("***(len=${secret.length})", out)
        assertFalse(out.contains(secret))
    }

    @Test
    fun `output never contains any non-trivial substring of the secret`() {
        // Guard against accidental partial leakage: no 3+ char window of the secret appears.
        val secret = "S3cr3tValue-With_Mixed.Chars/and spaces"
        val out = Redact.redact(secret)

        for (start in 0..secret.length - 3) {
            val window = secret.substring(start, start + 3)
            assertFalse(
                "placeholder leaked substring '$window' of the secret: '$out'",
                out.contains(window),
            )
        }
    }

    @Test
    fun `redaction is stable for the same input`() {
        val secret = "repeatable-secret"
        assertEquals(Redact.redact(secret), Redact.redact(secret))
    }

    @Test
    fun `distinct present values of equal length share the same placeholder (non-reversible)`() {
        // Same length ⇒ same output: the placeholder encodes only length, not content.
        assertEquals(Redact.redact("aaaaa"), Redact.redact("bcdef"))
    }

    @Test
    fun `dRedacted convenience never emits the raw secret`() {
        val secret = "top-secret-123"
        val captured = StringBuilder()
        val capturing = object : Logger {
            override fun d(tag: String, message: String) { captured.append(message) }
            override fun i(tag: String, message: String) = Unit
            override fun w(tag: String, message: String, throwable: Throwable?) = Unit
            override fun e(tag: String, message: String, throwable: Throwable?) = Unit
        }

        capturing.dRedacted("Test", "sharedSecret", secret)

        val line = captured.toString()
        assertTrue("label should be present", line.startsWith("sharedSecret="))
        assertTrue("length placeholder expected", line.contains("len=${secret.length}"))
        assertFalse("raw secret must not be logged", line.contains(secret))
    }
}
