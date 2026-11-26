package com.medtroniclabs.microcoaching.network.events

import java.util.Locale

/**
 * Raised where Dart's `Uri` throws a `FormatException` or an `ArgumentError`:
 * a malformed authority, port or IP literal, or a component that does not
 * percent-decode to strict UTF-8.
 */
internal class DartUriFormatException(message: String) : IllegalArgumentException(message)

/**
 * The part of Dart 3.12.2 `Uri` (`sdk/lib/core/uri.dart`) that [UrlSanitizer]
 * reads, reproduced so the SDK and the app sanitise a URL identically.
 *
 * Only a URI with a scheme and an authority (`scheme:` then two of `/` or `\`)
 * is modelled; anything else returns null from [tryParse], which the sanitiser
 * treats exactly like Dart's empty host. Within that form the split follows
 * Dart's scanner: the authority ends at `/ \ ? #` outside `[...]`, the host
 * starts after the last `@`, and the port after the first `:` that follows it.
 * Components are then normalised as `_Uri.notSimple` does: [host] by
 * `_makeHost`, [port] by `int.tryParse` and `_makePort`, [path] by `_makePath`
 * (backslash to slash, escape normalisation, `_removeDotSegments`) and [query]
 * by `_makeQuery`.
 *
 * Callers pass ASCII; [UrlSanitizer] percent-encodes everything else first.
 */
internal class DartUriLite private constructor(
    /** Lowercased scheme. */
    val scheme: String,
    /** Normalised host; an IPv6 literal without its brackets, as Dart's `host` getter. */
    val host: String,
    /** Explicit port, or null where Dart's `hasPort` is false. */
    val port: Long?,
    /** Normalised path, empty when the URI has none. */
    val path: String,
    /** Normalised query without the `?`, empty when absent. */
    val query: String,
) {

    companion object {

        private const val HEX_DIGITS = "0123456789ABCDEF"
        private const val SUB_DELIMS = "!$&'()*+,;="
        private const val GEN_DELIMS = ":/?#[]@"
        private const val UNRESERVED_2396_MARKS = "-_.!~*'()"
        private const val ESCAPED_PERCENT = "%25"
        private const val INVALID_ESCAPE = "%"
        private const val IP_FUTURE_MARKER = 'v'
        private const val MAX_IPV6_PARTS = 8
        private const val MAX_IPV6_PART_DIGITS = 4
        private const val IPV4_OCTETS = 4
        private const val MAX_IPV4_OCTET = 255
        private const val REPLACEMENT_CHARACTER = 0xFFFD
        private const val BYTE_ORDER_MARK = 0xFEFF

        /** Dart's `_defaultPort`: an explicit port equal to it is dropped. */
        private val DART_DEFAULT_PORTS = mapOf("http" to 80L, "https" to 443L)

        /** Dart `Uri.tryParse`, narrowed to URIs with an authority (see the class KDoc). */
        fun tryParse(uri: String): DartUriLite? = try {
            parse(uri)
        } catch (e: DartUriFormatException) {
            null
        }

        /** Dart `Uri.encodeComponent`: keeps `A-Za-z0-9-_.!~*'()`, escapes the rest as UTF-8. */
        fun encodeComponent(component: String): String {
            val out = StringBuilder(component.length)
            appendPercentEncoded(out, component) { it.isAsciiLetterOrDigit() || it in UNRESERVED_2396_MARKS }
            return out.toString()
        }

        /**
         * Escapes every code point that [keep] rejects as uppercase `%XX` UTF-8
         * bytes. A lone surrogate is encoded as U+FFFD, as Dart's `utf8.encode` does.
         */
        fun appendPercentEncoded(out: StringBuilder, text: String, keep: (Char) -> Boolean) {
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                val width = Character.charCount(codePoint)
                if (width == 1 && keep(text[i])) {
                    out.append(text[i])
                } else {
                    val encodable = if (codePoint in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code) {
                        REPLACEMENT_CHARACTER
                    } else {
                        codePoint
                    }
                    appendEscapedCodePoint(out, encodable)
                }
                i += width
            }
        }

        /** Dart `Uri.decodeComponent`: strict UTF-8, a single leading BOM dropped. */
        fun decodeComponent(encoded: String): String = uriDecode(encoded, plusToSpace = false)

        /** Dart `Uri.decodeQueryComponent`: as [decodeComponent], with `+` read as a space. */
        fun decodeQueryComponent(encoded: String): String = uriDecode(encoded, plusToSpace = true)

        private fun parse(uri: String): DartUriLite? {
            val schemeEnd = uri.indexOf(':')
            if (schemeEnd < 0 || uri.substring(0, schemeEnd).any { it in "/?#\\" }) return null
            val scheme = makeScheme(uri.substring(0, schemeEnd))
            val authorityStart = schemeEnd + 3
            if (authorityStart > uri.length || !isSlash(uri[schemeEnd + 1]) || !isSlash(uri[schemeEnd + 2])) {
                return null
            }
            val split = scanAuthority(uri, authorityStart)
            val fragmentStart = uri.indexOf('#', split.end).let { if (it < 0) uri.length else it }
            val queryStart = uri.indexOf('?', split.end).let { if (it < 0 || it > fragmentStart) fragmentStart else it }

            if (split.hostStart > authorityStart) checkUserInfo(uri, authorityStart, split.hostStart - 1)
            val hostEnd = if (split.portStart < 0) split.end else split.portStart
            val host = hostGetter(makeHost(uri, split.hostStart, hostEnd))
            val port = if (split.portStart >= 0 && split.portStart + 1 < split.end) {
                val number = parseDartInt(uri.substring(split.portStart + 1, split.end))
                    ?: throw DartUriFormatException("Invalid port")
                number.takeIf { it != (DART_DEFAULT_PORTS[scheme] ?: 0L) }
            } else {
                null
            }
            val path = removeDotSegments(
                normalize(uri, split.end, queryStart, ::isPathCharOrSlash, replaceBackslash = true),
            )
            val query = if (queryStart < fragmentStart) {
                normalize(uri, queryStart + 1, fragmentStart, ::isQueryChar, replaceBackslash = false)
            } else {
                ""
            }
            return DartUriLite(scheme, host, port, path, query)
        }

        /** Where the authority's host and port begin, and where the authority ends. */
        private class AuthoritySplit(val hostStart: Int, val portStart: Int, val end: Int)

        private enum class AuthorityState { START, HOST, PORT, BRACKET }

        /** Dart's scanner states 4-8: `@` restarts the host, `[...]` hides delimiters. */
        private fun scanAuthority(uri: String, start: Int): AuthoritySplit {
            var state = AuthorityState.START
            var hostStart = start
            var portStart = -1
            var i = start
            while (i < uri.length) {
                val c = uri[i]
                if (state == AuthorityState.BRACKET) {
                    if (c == ']') state = AuthorityState.HOST
                } else {
                    when (c) {
                        '/', '\\', '?', '#' -> return AuthoritySplit(hostStart, portStart, i)
                        '@' -> {
                            hostStart = i + 1
                            portStart = -1
                            state = AuthorityState.START
                        }
                        ':' -> if (state != AuthorityState.PORT) {
                            portStart = i
                            state = AuthorityState.PORT
                        }
                        '[' -> if (state == AuthorityState.START) state = AuthorityState.BRACKET
                        else -> if (state == AuthorityState.START) state = AuthorityState.HOST
                    }
                }
                i++
            }
            return AuthoritySplit(hostStart, portStart, uri.length)
        }

        /** `_makeScheme`: an alphabetic first character, then `[A-Za-z0-9+.-]`. */
        private fun makeScheme(scheme: String): String {
            if (scheme.isEmpty() || !scheme[0].isAsciiLetter()) throw DartUriFormatException("Invalid scheme")
            if (scheme.any { !(it.isAsciiLetterOrDigit() || it in "+-.") }) {
                throw DartUriFormatException("Illegal scheme character")
            }
            return scheme.lowercase(Locale.ROOT)
        }

        /** `_makeUserInfo`: the value is discarded, but a general delimiter other than `:` fails. */
        private fun checkUserInfo(uri: String, start: Int, end: Int) {
            for (i in start until end) {
                val c = uri[i]
                if (c != ':' && c in GEN_DELIMS) throw DartUriFormatException("Invalid character")
            }
        }

        /** `_makeHost` with `strictIPv6 = false`; the scanner never leaves a `:` in a bare host. */
        private fun makeHost(uri: String, start: Int, end: Int): String {
            if (start == end) return ""
            if (uri[start] != '[') return normalizeRegName(uri, start, end)
            if (uri[end - 1] != ']') throw DartUriFormatException("Missing end `]` to match `[` in host")
            var zoneId = ""
            var index = end - 1
            if (uri[start + 1] != IP_FUTURE_MARKER) {
                index = uri.indexOf('%', start + 1).let { if (it in (start + 1) until (end - 1)) it else end - 1 }
                if (index < end - 1) {
                    val zoneStart = if (uri.startsWith("25", index + 1)) index + 3 else index + 1
                    zoneId = normalizeZoneId(uri, zoneStart, end - 1)
                }
            }
            val isIpv6 = validateIpAddress(uri, start + 1, index)
            val hostChars = uri.substring(start + 1, index).let { if (isIpv6) it.lowercase(Locale.ROOT) else it }
            return "[$hostChars$zoneId]"
        }

        /** Dart's `host` getter drops the brackets of an IPv6 literal but not of an IPvFuture one. */
        private fun hostGetter(host: String): String =
            if (host.startsWith('[') && host.getOrNull(1) != IP_FUTURE_MARKER) {
                host.substring(1, host.length - 1)
            } else {
                host
            }

        /** `_normalizeRegName`: literal characters lowercased, escapes normalised. */
        private fun normalizeRegName(uri: String, start: Int, end: Int): String {
            val out = StringBuilder(end - start)
            var i = start
            while (i < end) {
                val c = uri[i]
                i += when {
                    c == '%' -> appendNormalizedEscape(out, uri, i, lowerCase = true)
                    isUnreserved(c) || c in SUB_DELIMS -> 1.also { out.append(c.lowercaseChar()) }
                    c in GEN_DELIMS -> throw DartUriFormatException("Invalid character")
                    else -> appendEscapedAt(out, uri, i)
                }
            }
            return out.toString()
        }

        /** `_normalizeZoneID` with the `%25` prefix; an invalid escape fails here. */
        private fun normalizeZoneId(uri: String, start: Int, end: Int): String {
            val out = StringBuilder(ESCAPED_PERCENT)
            var i = start
            while (i < end) {
                val c = uri[i]
                i += when {
                    c == '%' && normalizeEscape(uri, i, lowerCase = true) == INVALID_ESCAPE ->
                        throw DartUriFormatException("ZoneID should not contain % anymore")
                    c == '%' -> appendNormalizedEscape(out, uri, i, lowerCase = true)
                    isUnreserved(c) -> 1.also { out.append(c) }
                    else -> appendEscapedAt(out, uri, i)
                }
            }
            return out.toString()
        }

        /** Appends the normalised escape at [index]; returns the characters consumed. */
        private fun appendNormalizedEscape(out: StringBuilder, source: String, index: Int, lowerCase: Boolean): Int =
            when (val replacement = normalizeEscape(source, index, lowerCase)) {
                null -> 3.also { out.append(source, index, index + 3) }
                INVALID_ESCAPE -> 1.also { out.append(ESCAPED_PERCENT) }
                else -> 3.also { out.append(replacement) }
            }

        /** Escapes the code point at [index] as Dart's `_escapeChar`; returns the characters consumed. */
        private fun appendEscapedAt(out: StringBuilder, source: String, index: Int): Int {
            val codePoint = source.codePointAt(index)
            appendEscapedCodePoint(out, codePoint)
            return Character.charCount(codePoint)
        }

        /**
         * `_normalizeEscape`: the decoded character for an unreserved escape, an
         * uppercased escape for lowercase hex, null to keep the escape as is, or
         * `"%"` when the percent sign does not start a valid escape.
         */
        private fun normalizeEscape(source: String, index: Int, lowerCase: Boolean): String? {
            if (index + 2 >= source.length) return INVALID_ESCAPE
            val first = source[index + 1]
            val second = source[index + 2]
            val high = hexValue(first)
            val low = hexValue(second)
            if (high < 0 || low < 0) return INVALID_ESCAPE
            val value = (high shl 4) or low
            if (value < 0x80 && isUnreserved(value.toChar())) {
                val decoded = value.toChar()
                return (if (lowerCase) decoded.lowercaseChar() else decoded).toString()
            }
            if (first >= 'a' || second >= 'a') return source.substring(index, index + 3).uppercase(Locale.ROOT)
            return null
        }

        /** `_normalize` with `escapeDelimiters = true`, as used for the path and the query. */
        private fun normalize(
            uri: String,
            start: Int,
            end: Int,
            allowed: (Char) -> Boolean,
            replaceBackslash: Boolean,
        ): String {
            val out = StringBuilder(end - start)
            var i = start
            while (i < end) {
                val c = uri[i]
                i += when {
                    c.code < 0x7F && allowed(c) -> 1.also { out.append(c) }
                    c == '%' -> appendNormalizedEscape(out, uri, i, lowerCase = false)
                    c == '\\' && replaceBackslash -> 1.also { out.append('/') }
                    else -> appendEscapedAt(out, uri, i)
                }
            }
            return out.toString()
        }

        /** `_removeDotSegments`, applied to every path of a URI with a scheme. */
        private fun removeDotSegments(path: String): String {
            if (!path.startsWith('.') && !path.contains("/.")) return path
            val output = ArrayList<String>()
            var appendSlash = false
            for (segment in path.split('/')) {
                appendSlash = false
                when (segment) {
                    ".." -> {
                        if (output.isNotEmpty()) {
                            output.removeAt(output.lastIndex)
                            if (output.isEmpty()) output.add("")
                        }
                        appendSlash = true
                    }
                    "." -> appendSlash = true
                    else -> output.add(segment)
                }
            }
            if (appendSlash) output.add("")
            return output.joinToString("/")
        }

        /**
         * Dart VM `int.tryParse` without a radix: ASCII whitespace trimmed, one
         * optional sign, decimal digits or a `0x` hex literal, 64-bit range; a
         * positive hex literal may use all 64 bits and wraps, as in Dart.
         */
        private fun parseDartInt(source: String): Long? {
            val text = source.trim { it == ' ' || it in '\t'..'\r' }
            if (text.isEmpty()) return null
            var i = 0
            val negative = text[0] == '-'
            if (text[0] == '+' || negative) i++
            if (i == text.length) return null
            var radix = 10
            if (text[i] == '0') {
                i++
                if (i == text.length) return 0L
                if (text[i] == 'x' || text[i] == 'X') {
                    i++
                    if (i == text.length) return null
                    radix = 16
                }
            }
            var magnitude = 0uL
            val limit = ULong.MAX_VALUE / radix.toULong()
            for (index in i until text.length) {
                val digit = Character.digit(text[index], radix)
                if (digit < 0 || text[index].code > 0x7F) return null
                if (magnitude > limit) return null
                val next = magnitude * radix.toULong() + digit.toULong()
                if (next < magnitude * radix.toULong()) return null
                magnitude = next
            }
            val signedLimit = Long.MAX_VALUE.toULong()
            return when {
                negative && magnitude > signedLimit + 1uL -> null
                negative -> (0uL - magnitude).toLong()
                radix == 16 -> magnitude.toLong()
                magnitude > signedLimit -> null
                else -> magnitude.toLong()
            }
        }

        /** `_validateIPvAddress`: true for IPv6, false for a valid IPvFuture literal. */
        private fun validateIpAddress(host: String, start: Int, end: Int): Boolean {
            if (start == end) throw DartUriFormatException("Empty IP address")
            if (host[start] == IP_FUTURE_MARKER) {
                validateIpFuture(host, start, end)
                return false
            }
            parseIpv6(host, start, end)
            return true
        }

        /** `_validateIPvFutureAddress`: `v`, hex digits, `.`, then userinfo characters. */
        private fun validateIpFuture(host: String, start: Int, end: Int) {
            var cursor = start + 1
            val digitsStart = cursor
            while (true) {
                if (cursor >= end) throw DartUriFormatException("Missing '.' in IPvFuture address")
                val c = host[cursor++]
                if (hexValue(c) >= 0) continue
                if (c == '.' && cursor - 1 != digitsStart) break
                throw DartUriFormatException("Invalid IPvFuture version")
            }
            if (cursor == end) throw DartUriFormatException("Missing address in IPvFuture address")
            for (i in cursor until end) {
                val c = host[i]
                if (!(isUnreserved(c) || c in SUB_DELIMS || c == ':')) {
                    throw DartUriFormatException("Invalid IPvFuture address character")
                }
            }
        }

        /** `Uri.parseIPv6Address`, validating only. */
        private fun parseIpv6(host: String, start: Int, end: Int) {
            fun error(message: String): Nothing = throw DartUriFormatException("Illegal IPv6 address, $message")
            if (end - start < 2) error("address is too short")
            var partStart = start
            var wildcardAt = -1
            var partCount = 0
            var decValue = true
            var cursor = start
            if (host[cursor] == ':') {
                if (host[cursor + 1] != ':') error("invalid start colon")
                wildcardAt = 0
                partCount = 1
                cursor += 2
                partStart = cursor
            }
            while (true) {
                val c = if (cursor >= end) '\u0000' else host[cursor]
                val digit = hexValue(c)
                if (digit >= 0) {
                    if (c !in '0'..'9') decValue = false
                    if (cursor >= partStart + MAX_IPV6_PART_DIGITS) error("a part has more than 4 hex digits")
                    cursor++
                    continue
                }
                if (cursor > partStart) {
                    if (c == '.') {
                        if (decValue) {
                            if (partCount > MAX_IPV6_PARTS - 2) error("an address must contain at most 8 parts")
                            parseIpv4(host, partStart, end)
                            cursor = end
                            partCount += 2
                        }
                        break
                    }
                    partCount++
                    if (c == ':') {
                        if (partCount >= MAX_IPV6_PARTS) error("an address must contain at most 8 parts")
                        decValue = true
                        cursor++
                        partStart = cursor
                        continue
                    }
                    break
                }
                if (c == ':') {
                    if (wildcardAt >= 0) error("only one wildcard `::` is allowed")
                    wildcardAt = partCount
                    partCount++
                    partStart = ++cursor
                    continue
                }
                if (wildcardAt != partCount - 1) error("missing part")
                break
            }
            if (cursor < end) error("invalid character")
            if (partCount < MAX_IPV6_PARTS && wildcardAt < 0) {
                error("an address without a wildcard must contain exactly 8 parts")
            }
        }

        /** `_parseIPv4Address`: four decimal octets, no leading zeros, each at most 255. */
        private fun parseIpv4(host: String, start: Int, end: Int) {
            fun error(message: String): Nothing = throw DartUriFormatException("Illegal IPv4 address, $message")
            var cursor = start
            var octetStart = cursor
            var octetIndex = 0
            var octetValue = 0
            while (true) {
                val c = if (cursor >= end) '\u0000' else host[cursor]
                if (c in '0'..'9') {
                    if (octetValue == 0 && cursor != octetStart) error("parts must not have leading zeros")
                    octetValue = octetValue * 10 + (c - '0')
                    if (octetValue > MAX_IPV4_OCTET) error("each part must be in the range 0..255")
                    cursor++
                    continue
                }
                if (cursor == octetStart) {
                    if (cursor == end) break
                    error("invalid character")
                }
                octetIndex++
                if (c == '.') {
                    if (octetIndex < IPV4_OCTETS) {
                        octetValue = 0
                        octetStart = ++cursor
                        continue
                    }
                    break
                }
                if (cursor == end) {
                    if (octetIndex == IPV4_OCTETS) return
                    break
                }
                error("invalid character")
            }
            error("IPv4 address should contain exactly 4 parts")
        }

        /** `_uriDecode`: percent escapes and plain ASCII to bytes, then strict UTF-8. */
        private fun uriDecode(text: String, plusToSpace: Boolean): String {
            if (text.none { it.code > 0x7F || it == '%' || (plusToSpace && it == '+') }) return text
            val bytes = IntArray(text.length)
            var count = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c.code > 0x7F) throw DartUriFormatException("Illegal percent encoding in URI")
                when {
                    c == '%' -> {
                        if (i + 3 > text.length) throw DartUriFormatException("Truncated URI")
                        val high = hexValue(text[i + 1])
                        val low = hexValue(text[i + 2])
                        if (high < 0 || low < 0) throw DartUriFormatException("Invalid URL encoding")
                        bytes[count++] = (high shl 4) or low
                        i += 2
                    }
                    plusToSpace && c == '+' -> bytes[count++] = ' '.code
                    else -> bytes[count++] = c.code
                }
                i++
            }
            return decodeUtf8Strict(bytes, count)
        }

        /**
         * Dart `utf8.decode` with `allowMalformed: false`: overlong, surrogate,
         * out-of-range, stray continuation and truncated sequences fail, and one
         * BOM at the very start is dropped. Hand-written so the JVM and Android,
         * whose charset decoders differ on malformed input, behave the same.
         */
        private fun decodeUtf8Strict(bytes: IntArray, count: Int): String {
            fun malformed(): Nothing = throw DartUriFormatException("Invalid UTF-8")
            val out = StringBuilder(count)
            var i = 0
            while (i < count) {
                val lead = bytes[i]
                val start = i
                val (extra, minSecond, maxSecond) = when (lead) {
                    in 0x00..0x7F -> Triple(0, 0, 0)
                    in 0xC2..0xDF -> Triple(1, 0x80, 0xBF)
                    0xE0 -> Triple(2, 0xA0, 0xBF)
                    0xED -> Triple(2, 0x80, 0x9F)
                    in 0xE1..0xEF -> Triple(2, 0x80, 0xBF)
                    0xF0 -> Triple(3, 0x90, 0xBF)
                    in 0xF1..0xF3 -> Triple(3, 0x80, 0xBF)
                    0xF4 -> Triple(3, 0x80, 0x8F)
                    else -> malformed()
                }
                var codePoint = if (extra == 0) lead else lead and (0x3F shr extra)
                for (k in 1..extra) {
                    if (i + k >= count) malformed()
                    val next = bytes[i + k]
                    val (low, high) = if (k == 1) minSecond to maxSecond else 0x80 to 0xBF
                    if (next !in low..high) malformed()
                    codePoint = (codePoint shl 6) or (next and 0x3F)
                }
                i += extra + 1
                if (!(start == 0 && codePoint == BYTE_ORDER_MARK)) out.appendCodePoint(codePoint)
            }
            return out.toString()
        }

        /** Dart `_escapeChar`: the UTF-8 bytes of [codePoint] as uppercase `%XX`. */
        private fun appendEscapedCodePoint(out: StringBuilder, codePoint: Int) {
            val bytes = when {
                codePoint < 0x80 -> intArrayOf(codePoint)
                codePoint < 0x800 -> intArrayOf(0xC0 or (codePoint shr 6), 0x80 or (codePoint and 0x3F))
                codePoint < 0x10000 -> intArrayOf(
                    0xE0 or (codePoint shr 12),
                    0x80 or ((codePoint shr 6) and 0x3F),
                    0x80 or (codePoint and 0x3F),
                )
                else -> intArrayOf(
                    0xF0 or (codePoint shr 18),
                    0x80 or ((codePoint shr 12) and 0x3F),
                    0x80 or ((codePoint shr 6) and 0x3F),
                    0x80 or (codePoint and 0x3F),
                )
            }
            for (byte in bytes) out.append('%').append(HEX_DIGITS[byte shr 4]).append(HEX_DIGITS[byte and 0xF])
        }

        private fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }

        private fun isSlash(c: Char): Boolean = c == '/' || c == '\\'

        private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

        private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

        private fun isUnreserved(c: Char): Boolean = c.isAsciiLetterOrDigit() || c in "-._~"

        private fun isPathCharOrSlash(c: Char): Boolean = isUnreserved(c) || c in SUB_DELIMS || c in ":@/"

        private fun isQueryChar(c: Char): Boolean = isPathCharOrSlash(c) || c == '?'
    }
}
