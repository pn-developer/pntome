package dev.pntome.app.domain.sync

import java.util.Locale

/**
 * Natural ("human") ordering of file names, used to order tracks/chapters within a folder
 * (spec gsound-audiobooks AB-2.2, AB-2.5).
 *
 * The name (extension stripped) is split into alternating digit and non-digit runs:
 * - digit runs compare by numeric value — arbitrarily long, so leading zeros and huge numbers
 *   are safe (`2` < `10`, `01` == `1` by value, then the shorter run first as a tie-break);
 * - text runs compare case-insensitively ([Locale.ROOT]);
 * - when one name is a prefix of the other (by runs), the shorter sorts first.
 *
 * A final tie-break on the full original name (case-sensitive) makes the order total and
 * deterministic, so two distinct names never compare equal.
 *
 * Examples, ascending: `01 Intro` < `02 Chapter` < `10 Chapter`; `1-02` < `1-10` < `2-01`;
 * `Chapter 2` < `chapter 10`; names without digits sort alphabetically.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val ta = tokenize(stripExtension(a))
        val tb = tokenize(stripExtension(b))
        val n = minOf(ta.size, tb.size)
        for (i in 0 until n) {
            val c = compareToken(ta[i], tb[i])
            if (c != 0) return c
        }
        if (ta.size != tb.size) return ta.size.compareTo(tb.size)
        return a.compareTo(b)
    }

    /** Drop a trailing `.ext` (not a leading dot), mirroring the title fallback. */
    private fun stripExtension(name: String): String {
        val trimmed = name.trim()
        val dot = trimmed.lastIndexOf('.')
        return if (dot > 0) trimmed.substring(0, dot) else trimmed
    }

    /** Split into maximal runs of digits / non-digits. */
    internal fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val digit = s[i].isAsciiDigit()
            var j = i + 1
            while (j < s.length && s[j].isAsciiDigit() == digit) j++
            out += s.substring(i, j)
            i = j
        }
        return out
    }

    private fun compareToken(x: String, y: String): Int {
        val xd = x[0].isAsciiDigit()
        val yd = y[0].isAsciiDigit()
        return when {
            xd && yd -> compareNumeric(x, y)
            // Digits before text at the same position ("1 Intro" < "Intro").
            xd -> -1
            yd -> 1
            else -> x.lowercase(Locale.ROOT).compareTo(y.lowercase(Locale.ROOT))
        }
    }

    /** Compare digit strings by value without parsing (no overflow), then shorter run first. */
    private fun compareNumeric(x: String, y: String): Int {
        val xs = x.trimStart('0')
        val ys = y.trimStart('0')
        if (xs.length != ys.length) return xs.length.compareTo(ys.length)
        val c = xs.compareTo(ys)
        if (c != 0) return c
        return x.length.compareTo(y.length)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
