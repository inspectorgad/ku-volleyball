package com.example.text

import kotlin.math.floor

// Fixed-point number formatting without java.util.Formatter, which iOS does
// not have.
//
// fixed(v, 3) is String.format(Locale.US, "%.3f", v), digit for digit. That
// matters more than it looks: Java rounds half-up on the shortest decimal
// form of the number, not on its exact binary value, so a hitting percentage
// of 1/16 shows as .063 where C's printf (and so a naive iOS port) would show
// .062. NumbersTest compares the two over a few hundred thousand values.

/** [value] with exactly [decimals] digits after the point, as Java's %.Nf writes it. */
fun fixed(value: Double, decimals: Int): String {
    require(decimals >= 0) { "decimals must not be negative" }
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    // Java keeps the sign of negative zero and of negatives that round to 0.
    val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)

    val (digits, pointAt) = decimalDigits(kotlin.math.abs(value))
    // Keep the digits up to the requested place, then round half-up on the next.
    val keep = pointAt + decimals
    val kept = StringBuilder()
    var roundUp = false
    if (keep < 0) {
        // Every kept digit is a leading zero; nothing to round from.
    } else {
        for (i in 0 until keep) kept.append(if (i < digits.length) digits[i] else '0')
        roundUp = keep < digits.length && digits[keep] >= '5'
    }
    var whole = kept.toString().ifEmpty { "0" }
    if (roundUp) whole = incrementDecimal(whole)

    // whole now holds the number times 10^decimals; put the point back in.
    val padded = whole.padStart(decimals + 1, '0')
    val intPart = padded.substring(0, padded.length - decimals).trimStart('0').ifEmpty { "0" }
    val fracPart = padded.substring(padded.length - decimals)
    val body = if (decimals == 0) intPart else "$intPart.$fracPart"
    return if (negative) "-$body" else body
}

/**
 * [value] to the nearest whole number, halves rounded up, as Java's
 * Math.round(Double) gives it: 2.5 is 3, -2.5 is -2, 0.49999999999999994 is 0,
 * and NaN is 0. Kotlin's roundToLong() throws on NaN, and on iOS it computes
 * floor(value + 0.5), which rounds that last example up. value - floor(value)
 * is exact here (or, just below a half, rounds the right way), so this is
 * Java's answer on every platform.
 */
fun roundHalfUp(value: Double): Long {
    if (value.isNaN()) return 0
    val down = floor(value)
    return (if (value - down >= 0.5) down + 1 else down).toLong()
}

/** Lower-case two-digit hex for a byte, as "%02x".format(byte) writes it. */
fun hex2(b: Byte): String = (b.toInt() and 0xFF).toString(16).padStart(2, '0')

/**
 * The significant digits of a non-negative [value] and where the decimal
 * point falls among them, from its shortest decimal form: 0.0625 is
 * ("625", -1), 12.5 is ("125", 2), 1.0E-5 is ("1", -4).
 */
private fun decimalDigits(value: Double): Pair<String, Int> {
    val text = value.toString()
    val ePos = text.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (ePos >= 0) text.substring(0, ePos) else text
    val exponent = if (ePos >= 0) text.substring(ePos + 1).toInt() else 0
    val dot = mantissa.indexOf('.')
    val intDigits = if (dot >= 0) mantissa.substring(0, dot) else mantissa
    val fracDigits = if (dot >= 0) mantissa.substring(dot + 1) else ""
    val all = intDigits + fracDigits
    var pointAt = intDigits.length + exponent
    // Drop leading zeros, moving the point with them.
    val firstNonZero = all.indexOfFirst { it != '0' }
    if (firstNonZero < 0) return "0" to 1
    pointAt -= firstNonZero
    return all.substring(firstNonZero).trimEnd('0').ifEmpty { "0" } to pointAt
}

/** Adds one to a string of decimal digits: "0999" -> "1000", "99" -> "100". */
private fun incrementDecimal(digits: String): String {
    val chars = digits.toCharArray()
    var i = chars.size - 1
    while (i >= 0) {
        if (chars[i] == '9') {
            chars[i] = '0'
            i--
        } else {
            chars[i] = chars[i] + 1
            return chars.concatToString()
        }
    }
    return "1" + chars.concatToString()
}
