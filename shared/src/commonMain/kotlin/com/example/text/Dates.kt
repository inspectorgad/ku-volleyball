@file:OptIn(ExperimentalTime::class)

package com.example.text

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// Dates and times without java.text.SimpleDateFormat, which iOS does not have.
//
// The app uses a handful of SimpleDateFormat patterns, so this is that subset:
// the letters y M d E H h m s a, quoted text, and '' for a quote mark. It keeps
// SimpleDateFormat's behaviour where the app could see a difference - US
// English names, strict parsing (2026-02-30 is not a date), and anything after
// the pattern ignored. Time zones come from kotlinx-datetime. DatesTest runs
// every pattern the app uses against SimpleDateFormat over decades of dates.

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** The schedule's times are Kansas time. */
private val CENTRAL = TimeZone.of("America/Chicago")

/** Milliseconds since 1970, now. */
fun currentTimeMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Today's date where the phone is, "yyyy-MM-dd". */
fun todayIso(): String = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

/**
 * [value] read with the pattern [from] and written with [to] - "2026-09-25",
 * "yyyy-MM-dd", "EEE MMM d" gives "Fri Sep 25". No time zone is involved: the
 * fields read are the fields written. Null when [value] does not fit [from].
 */
fun reformat(value: String, from: String, to: String): String? = parse(value, from)?.let { format(it, to) }

/** A "yyyy-MM-dd" date and "HH:mm" time in Kansas, as UTC milliseconds; null if either is not valid. */
fun centralToUtcMs(date: String, time: String): Long? =
    parse("$date $time", "yyyy-MM-dd HH:mm")?.toInstant(CENTRAL)?.toEpochMilliseconds()

/** A "yyyy-MM-dd'T'HH:mm:ss'Z'" UTC timestamp, written with [pattern] in the phone's own time zone. */
fun utcToLocal(iso: String, pattern: String): String? =
    parse(iso, "yyyy-MM-dd'T'HH:mm:ss'Z'")
        ?.toInstant(TimeZone.UTC)
        ?.toLocalDateTime(TimeZone.currentSystemDefault())
        ?.let { format(it, pattern) }

private sealed interface Token
private class Field(val letter: Char, val count: Int) : Token
private class Literal(val text: String) : Token

private fun tokenize(pattern: String): List<Token> {
    val tokens = mutableListOf<Token>()
    val literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) tokens += Literal(literal.toString())
        literal.clear()
    }
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            c == '\'' && i + 1 < pattern.length && pattern[i + 1] == '\'' -> {
                literal.append('\'')
                i += 2
            }
            c == '\'' -> {
                // Quoted text runs to the next lone quote; '' inside it is a quote mark.
                i++
                while (i < pattern.length) {
                    if (pattern[i] != '\'') {
                        literal.append(pattern[i++])
                    } else if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
                        literal.append('\'')
                        i += 2
                    } else {
                        i++
                        break
                    }
                }
            }
            c in 'a'..'z' || c in 'A'..'Z' -> {
                require(c in "yMdEHhmsa") { "Pattern letter '$c' is not supported" }
                flush()
                var count = 1
                while (i + count < pattern.length && pattern[i + count] == c) count++
                tokens += Field(c, count)
                i += count
            }
            else -> literal.append(pattern[i++])
        }
    }
    flush()
    return tokens
}

private fun format(t: LocalDateTime, pattern: String): String = buildString {
    for (token in tokenize(pattern)) {
        when (token) {
            is Literal -> append(token.text)
            is Field -> append(formatField(t, token.letter, token.count))
        }
    }
}

private fun formatField(t: LocalDateTime, letter: Char, count: Int): String {
    fun pad(n: Int) = n.toString().padStart(count, '0')
    return when (letter) {
        // As in SimpleDateFormat: "yy" is the last two digits, any other count the whole year.
        'y' -> if (count == 2) (t.year % 100).toString().padStart(2, '0') else pad(t.year)
        'M' -> when {
            count >= 4 -> MONTHS[t.month.number - 1]
            count == 3 -> MONTHS[t.month.number - 1].take(3)
            else -> pad(t.month.number)
        }
        'd' -> pad(t.day)
        'E' -> WEEKDAYS[t.dayOfWeek.isoDayNumber - 1].let { if (count >= 4) it else it.take(3) }
        'H' -> pad(t.hour)
        'h' -> pad(if (t.hour % 12 == 0) 12 else t.hour % 12)
        'm' -> pad(t.minute)
        's' -> pad(t.second)
        'a' -> if (t.hour < 12) "AM" else "PM"
        else -> error("Pattern letter '$letter' is not supported")
    }
}

/** Reads the numeric fields of [pattern] from [value]; null when they do not fit or are out of range. */
private fun parse(value: String, pattern: String): LocalDateTime? {
    var year = 1970
    var month = 1
    var day = 1
    var hour = 0
    var minute = 0
    var second = 0
    var pos = 0
    for (token in tokenize(pattern)) {
        when (token) {
            is Literal -> {
                if (!value.startsWith(token.text, pos)) return null
                pos += token.text.length
            }
            is Field -> {
                if (token.letter !in "yMdHms" || (token.letter == 'M' && token.count > 2)) {
                    error("Reading pattern letter '${token.letter}' is not supported")
                }
                // SimpleDateFormat skips spaces and tabs before a number.
                while (pos < value.length && (value[pos] == ' ' || value[pos] == '\t')) pos++
                val start = pos
                while (pos < value.length && value[pos] in '0'..'9') pos++
                val n = value.substring(start, pos).toIntOrNull() ?: return null
                when (token.letter) {
                    'y' -> year = n
                    'M' -> month = n
                    'd' -> day = n
                    'H' -> hour = n
                    'm' -> minute = n
                    's' -> second = n
                }
            }
        }
    }
    // LocalDateTime rejects a month 13, a February 30 or an hour 24, as a
    // non-lenient SimpleDateFormat does.
    return try {
        LocalDateTime(year, month, day, hour, minute, second)
    } catch (e: IllegalArgumentException) {
        null
    }
}
