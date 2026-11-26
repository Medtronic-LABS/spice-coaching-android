package com.medtroniclabs.microcoaching.network.events

import java.util.Locale

/**
 * Reduces a request URL to a form that is safe to store and show in the
 * network log: scheme, lowercased host, a non-default port, the path with
 * identifier-like segments replaced by [ID_TOKEN], and only allowlisted query
 * keys, each with its value replaced by [REDACTED]. Userinfo, fragments and
 * every other query pair are dropped.
 *
 * A line-for-line port of the mobile app's Dart `UrlSanitizer`, which is the
 * reference: both must give the same output for every case in the shared
 * `net_log/url_sanitizer_vectors.json`. Dart's `Uri` parsing is reproduced by
 * [DartUriLite].
 *
 * Pure and total: never throws, the result is at most [MAX_LENGTH] characters,
 * and `sanitize(sanitize(x)) == sanitize(x)` for the vector inputs (the app
 * re-sanitises rows it merges). Hosts are trusted and kept apart from
 * lowercasing and the port rule. Known limitations, as in the reference:
 * digits are counted per path segment, opaque tokens without `@`, `;`, a UUID
 * or digits are not masked, and only Unicode decimal digits count as digits.
 */
internal object UrlSanitizer {

    const val MAX_LENGTH = 2048
    const val MAX_INPUT_LENGTH = 8192
    const val UNPARSABLE = "<unparsable>"
    const val ID_TOKEN = "{id}"
    const val REDACTED = "<redacted>"
    const val CAP_MARKER = "\u2026"

    private const val MAX_DECODE_PASSES = 8
    private const val MIN_TOTAL_DIGITS = 7

    private val SCHEMES = setOf("http", "https", "ws", "wss")
    // The output rule: a port equal to the scheme's default is not shown. It
    // differs from DartUriLite's parse-time `_defaultPort` (http/https only,
    // other schemes 0); both stages are part of the reference behaviour.
    private val DEFAULT_PORTS = mapOf("http" to 80L, "https" to 443L, "ws" to 80L, "wss" to 443L)
    private val QUERY_ALLOWLIST = setOf(
        "_count",
        "_lang",
        "encounter",
        "key",
        "language",
        "model",
        "since",
        "limit",
        "offset",
        "cursor",
        "from_date",
        "to_date",
    )
    /**
     * Every Unicode decimal digit (`\p{Nd}`, general category Nd) known to the
     * Dart 3.12.2 reference, which uses Unicode 16.0, as inclusive
     * `first, last` code-point pairs.
     *
     * A code point counts as a digit if the platform calls it one or it falls in
     * this table. JDK 21 (Unicode 15.0) lacks 80 of these digits: Garay,
     * Myanmar Pao and Eastern Pwo Karen, Sunuwar, Gurung Khema, Kirat Rai,
     * outlined digits and Ol Onal. Android's ICU lacks more on older API levels
     * (minSdk 23). Without the table, a run of those digits would be masked by
     * the app but not here. A platform with newer Unicode data only adds digits
     * the reference does not know, a harmless superset.
     *
     * To regenerate after a Dart SDK upgrade, outside the repo: for every code
     * point, ask the Dart reference whether `https://h.test/x/` followed by six
     * copies of it sanitises to `/{id}`. Then drop `@`, `;`, `\uFF20` and `\uFE6B`,
     * which are masked for another reason, and fold the rest into ranges.
     */
    internal val DART_DECIMAL_DIGIT_RANGES = intArrayOf(
        0x0030, 0x0039, 0x0660, 0x0669, 0x06F0, 0x06F9, 0x07C0, 0x07C9,
        0x0966, 0x096F, 0x09E6, 0x09EF, 0x0A66, 0x0A6F, 0x0AE6, 0x0AEF,
        0x0B66, 0x0B6F, 0x0BE6, 0x0BEF, 0x0C66, 0x0C6F, 0x0CE6, 0x0CEF,
        0x0D66, 0x0D6F, 0x0DE6, 0x0DEF, 0x0E50, 0x0E59, 0x0ED0, 0x0ED9,
        0x0F20, 0x0F29, 0x1040, 0x1049, 0x1090, 0x1099, 0x17E0, 0x17E9,
        0x1810, 0x1819, 0x1946, 0x194F, 0x19D0, 0x19D9, 0x1A80, 0x1A89,
        0x1A90, 0x1A99, 0x1B50, 0x1B59, 0x1BB0, 0x1BB9, 0x1C40, 0x1C49,
        0x1C50, 0x1C59, 0xA620, 0xA629, 0xA8D0, 0xA8D9, 0xA900, 0xA909,
        0xA9D0, 0xA9D9, 0xA9F0, 0xA9F9, 0xAA50, 0xAA59, 0xABF0, 0xABF9,
        0xFF10, 0xFF19, 0x104A0, 0x104A9, 0x10D30, 0x10D39, 0x10D40, 0x10D49,
        0x11066, 0x1106F, 0x110F0, 0x110F9, 0x11136, 0x1113F, 0x111D0, 0x111D9,
        0x112F0, 0x112F9, 0x11450, 0x11459, 0x114D0, 0x114D9, 0x11650, 0x11659,
        0x116C0, 0x116C9, 0x116D0, 0x116E3, 0x11730, 0x11739, 0x118E0, 0x118E9,
        0x11950, 0x11959, 0x11BF0, 0x11BF9, 0x11C50, 0x11C59, 0x11D50, 0x11D59,
        0x11DA0, 0x11DA9, 0x11F50, 0x11F59, 0x16130, 0x16139, 0x16A60, 0x16A69,
        0x16AC0, 0x16AC9, 0x16B50, 0x16B59, 0x16D70, 0x16D79, 0x1CCF0, 0x1CCF9,
        0x1D7CE, 0x1D7FF, 0x1E140, 0x1E149, 0x1E2F0, 0x1E2F9, 0x1E4F0, 0x1E4F9,
        0x1E5F1, 0x1E5FA, 0x1E950, 0x1E959, 0x1FBF0, 0x1FBF9,
    )

    private val AT_SIGNS = listOf("@", "\uFF20", "\uFE6B")

    // Only bounded repeats of single characters: java.util.regex matches these
    // iteratively, so stack use does not grow with the input. The reference's
    // digit regexes and its two cap regexes, which repeat a group, are hand-written
    // scans below ([hasSensitiveDigits], [stripTrailingEscapes]).
    private val UUID = Regex("[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")
    private val DASHLESS_UUID = Regex("[0-9a-fA-F]{32}")
    private val ESCAPE = Regex("%[0-9a-fA-F]{2}")

    private const val MIN_DIGIT_RUN = 6
    private const val MAX_ESCAPE_HEX_DIGITS = 2

    /** `{id`, `{i`, `{`: what a cut can leave of [ID_TOKEN], longest first. */
    private val ID_TOKEN_PREFIXES = (ID_TOKEN.length - 1 downTo 1).map { ID_TOKEN.substring(0, it) }

    /**
     * The sanitised form of [raw], or [UNPARSABLE] when it is longer than
     * [MAX_INPUT_LENGTH] UTF-16 units, is not an http(s)/ws(s) URL with a host,
     * or fails to parse.
     *
     * [RuntimeException] is caught here on purpose: this is the totality
     * boundary (the Dart reference catches `Object`), so an unforeseen parser
     * failure on hostile input yields the marker instead of failing the
     * network call being recorded.
     */
    fun sanitize(raw: String): String {
        if (raw.length > MAX_INPUT_LENGTH) return UNPARSABLE
        return try {
            // A trailing cap marker from an earlier pass is not data: strip it,
            // sanitise the rest and put it back, so the cap output is a fixed point.
            val hadMarker = raw.endsWith(CAP_MARKER)
            val body = sanitizeUrl(if (hadMarker) raw.substring(0, raw.length - CAP_MARKER.length) else raw)
            if (body == UNPARSABLE) UNPARSABLE else cap(body, hadMarker)
        } catch (e: RuntimeException) {
            UNPARSABLE
        }
    }

    private fun sanitizeUrl(raw: String): String {
        val uri = DartUriLite.tryParse(percentEncodeNonAscii(raw))
        if (uri == null || uri.scheme.lowercase(Locale.ROOT) !in SCHEMES || uri.host.isEmpty()) {
            return UNPARSABLE
        }
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host.lowercase(Locale.ROOT)
        // An IPvFuture host keeps its brackets (see DartUriLite), so one with `:`
        // is wrapped twice here, exactly as the reference does.
        val authority = if (host.contains(':')) "[$host]" else host
        val port = if (uri.port != null && uri.port != DEFAULT_PORTS[scheme]) ":${uri.port}" else ""
        val path = uri.path.split('/').joinToString("/") { sanitizeSegment(it) }
        val query = sanitizeQuery(uri.query)
        return "$scheme://$authority$port$path${if (query.isEmpty()) "" else "?$query"}"
    }

    /**
     * Dart's `Uri.tryParse` rejects raw non-ASCII; encode it so such paths still
     * reach the per-segment rule (Bengali digits count towards the 6+ run).
     */
    private fun percentEncodeNonAscii(value: String): String {
        val out = StringBuilder(value.length)
        DartUriLite.appendPercentEncoded(out, value) { it.code < 0x80 }
        return out.toString()
    }

    private fun sanitizeSegment(segment: String): String {
        if (segment.isEmpty()) return segment
        val decoded = decodeStable(segment)
        if (decoded == null || isSensitive(decoded)) return ID_TOKEN
        if (decoded == ID_TOKEN) return ID_TOKEN
        return DartUriLite.encodeComponent(decoded)
    }

    private fun decodeStable(value: String): String? {
        var current = value
        try {
            var pass = 0
            while (pass < MAX_DECODE_PASSES && ESCAPE.containsMatchIn(current)) {
                current = DartUriLite.decodeComponent(current)
                pass++
            }
        } catch (e: DartUriFormatException) {
            return null
        }
        return if (ESCAPE.containsMatchIn(current)) null else current
    }

    private fun isSensitive(segment: String): Boolean =
        AT_SIGNS.any { segment.contains(it) } ||
            segment.contains(';') ||
            UUID.containsMatchIn(segment) ||
            DASHLESS_UUID.containsMatchIn(segment) ||
            hasSensitiveDigits(segment)

    /**
     * The reference's `\p{Nd}{6,}` and "7 or more `\p{Nd}`" rules, counted per
     * code point with [isDecimalDigit] so the digit set matches Dart's.
     */
    private fun hasSensitiveDigits(segment: String): Boolean {
        var run = 0
        var total = 0
        var i = 0
        while (i < segment.length) {
            val codePoint = segment.codePointAt(i)
            if (isDecimalDigit(codePoint)) {
                run++
                total++
                if (run >= MIN_DIGIT_RUN || total >= MIN_TOTAL_DIGITS) return true
            } else {
                run = 0
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    private fun isDecimalDigit(codePoint: Int): Boolean {
        if (Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER.toInt()) return true
        for (i in DART_DECIMAL_DIGIT_RANGES.indices step 2) {
            if (codePoint < DART_DECIMAL_DIGIT_RANGES[i]) return false
            if (codePoint <= DART_DECIMAL_DIGIT_RANGES[i + 1]) return true
        }
        return false
    }

    private fun sanitizeQuery(rawQuery: String): String {
        if (rawQuery.isEmpty()) return ""
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        try {
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val rawKey = if (eq < 0) pair else pair.substring(0, eq)
                val key = DartUriLite.decodeQueryComponent(rawKey)
                if (key in QUERY_ALLOWLIST && seen.add(key)) {
                    out.add("$key=$REDACTED")
                }
            }
        } catch (e: DartUriFormatException) {
            return ""
        }
        return out.joinToString("&")
    }

    private fun cap(body: String, hadMarker: Boolean): String {
        val combined = if (hadMarker) "$body$CAP_MARKER" else body
        if (combined.length <= MAX_LENGTH) return combined
        val cut = dropPartialToken(body.substring(0, MAX_LENGTH - 1))
        return "$cut$CAP_MARKER"
    }

    /**
     * Drops a token the cut split (escape run, `{id`, or a half query pair) so
     * a second pass over the capped text leaves it unchanged.
     */
    private fun dropPartialToken(cut: String): String {
        var out = stripTrailingIdPrefix(stripTrailingEscapes(cut))
        val q = out.indexOf('?')
        if (q >= 0 && !out.endsWith("=$REDACTED")) {
            val amp = out.lastIndexOf('&')
            out = out.substring(0, if (amp > q) amp else q)
        }
        return out
    }

    /**
     * The reference's `replaceFirst(RegExp(r'(%[0-9a-fA-F]{0,2})+$'), '')` as an
     * O(n) backward scan. Hex digits are never `%`, so a match splits uniquely at
     * each `%`. Walk back over pieces of `%` plus at most two hex digits and cut at
     * the leftmost piece of that unbroken chain. The regex is not used because
     * java.util.regex recurses once per group repetition and overflows small
     * thread stacks on a long escape run.
     */
    private fun stripTrailingEscapes(text: String): String {
        var keep = text.length
        while (keep > 0) {
            val percent = text.lastIndexOf('%', keep - 1)
            if (percent < 0 || keep - percent - 1 > MAX_ESCAPE_HEX_DIGITS) break
            if ((percent + 1 until keep).any { !DartUriLite.isHexDigit(text[it]) }) break
            keep = percent
        }
        return text.substring(0, keep)
    }

    /** The reference's `replaceFirst(RegExp(r'\{(i|id)?$'), '')`: drop a trailing `{`, `{i` or `{id`. */
    private fun stripTrailingIdPrefix(text: String): String =
        ID_TOKEN_PREFIXES.firstOrNull { text.endsWith(it) }?.let { text.dropLast(it.length) } ?: text
}
