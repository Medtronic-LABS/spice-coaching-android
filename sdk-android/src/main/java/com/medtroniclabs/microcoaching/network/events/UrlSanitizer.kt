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
    const val CAP_MARKER = "…"

    private const val MAX_DECODE_PASSES = 8
    private const val MIN_TOTAL_DIGITS = 7

    private val SCHEMES = setOf("http", "https", "ws", "wss")
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
    private val AT_SIGNS = listOf("@", "＠", "﹫")

    private val UUID = Regex("[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")
    private val DASHLESS_UUID = Regex("[0-9a-fA-F]{32}")
    private val DIGIT = Regex("\\p{Nd}")
    private val DIGIT_RUN = Regex("\\p{Nd}{6,}")
    private val ESCAPE = Regex("%[0-9a-fA-F]{2}")

    // `\z`, not `$`: Java's `$` also matches before a final line terminator.
    private val TRAILING_ESCAPES = Regex("(%[0-9a-fA-F]{0,2})+\\z")
    private val TRAILING_ID_PREFIX = Regex("\\{(i|id)?\\z")

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
            DIGIT_RUN.containsMatchIn(segment) ||
            DIGIT.findAll(segment).count() >= MIN_TOTAL_DIGITS

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
        var out = TRAILING_ID_PREFIX.replaceFirst(TRAILING_ESCAPES.replaceFirst(cut, ""), "")
        val q = out.indexOf('?')
        if (q >= 0 && !out.endsWith("=$REDACTED")) {
            val amp = out.lastIndexOf('&')
            out = out.substring(0, if (amp > q) amp else q)
        }
        return out
    }
}
