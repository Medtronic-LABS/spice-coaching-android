package com.medtroniclabs.microcoaching.network.events

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source scan: the files that handle presigned URLs and storage responses must
 * not put a URL, a response body, an exception message or a throwable (whose
 * message is printed with the stack) into a log call. Presigned URLs carry
 * signatures and release builds keep these logs.
 *
 * Only the three files in [scanned] are checked, not the rest of the SDK; adding
 * a file to the list is how its logging comes under this rule.
 *
 * Allowed in a log call: fixed text, a status code, the exception class name
 * and `UrlSanitizer.sanitize(url)`.
 */
class NoUrlInLogsTest {

    private val root = "src/main/java/com/medtroniclabs/microcoaching"

    private val scanned = listOf(
        "$root/ai/download/ResumableHttpDownloader.kt",
        "$root/network/MediaUrlResolver.kt",
        "$root/data/asset/AssetCache.kt",
    )

    @Test
    fun logCallsDoNotLeakUrlsBodiesOrMessages() {
        val violations = scanned.flatMap { path ->
            val file = File(path)
            assertTrue("missing $path (cwd=${File(".").absolutePath})", file.exists())
            scan(file.name, file.readText())
        }
        assertTrue("log calls leak sensitive data:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    // Scanner self-tests: each synthetic leak must be flagged, each safe line must not.

    private fun log(call: String) = "fun f() {\n    $call\n}\n"

    private fun flagged(call: String) = scan("Synthetic.kt", log(call)).isNotEmpty()

    @Test fun flagsBareThrowableInTemplate() {
        listOf("e", "ex", "err", "exception", "cause", "it", "throwable", "t").forEach { n ->
            assertTrue("\$$n", flagged("Log.w(TAG, \"failed: \$$n\")"))
            assertTrue("\${$n}", flagged("Log.w(TAG, \"failed: \${$n}\")"))
        }
        assertEquals(false, flagged("Log.w(TAG, \"items \$items\")"))
    }

    @Test fun flagsToStringAndLocalizedMessage() {
        assertTrue(flagged("Log.w(TAG, \"x \${e.toString()}\")"))
        assertTrue(flagged("Log.w(TAG, \"x \${e.localizedMessage}\")"))
    }

    @Test fun flagsUrlishIdentifiersExceptWhenSanitized() {
        listOf("url", "signedUri", "downloadLink", "src", "hrefValue", "presignedURL").forEach { n ->
            assertTrue(n, flagged("Log.d(TAG, \"x \$$n\")"))
            assertTrue(n, flagged("Log.d(TAG, \"x \${$n.trim()}\")"))
        }
        assertEquals(false, flagged("Log.d(TAG, \"x \${UrlSanitizer.sanitize(url)}\")"))
        assertEquals(false, flagged("Log.d(TAG, \"x \${UrlSanitizer.sanitize(a.b(url))}\")"))
    }

    @Test fun flagsTrailingIdentifierThrowable() {
        assertTrue(flagged("Log.w(TAG, \"failed\", ex)"))
        assertTrue(flagged("Log.w(TAG, \"failed\", err,\n)"))
        assertEquals(false, flagged("Log.w(TAG, \"failed\")"))
    }

    @Test fun flagsMessageReadIntoLocal() {
        val src = "fun f() {\n    val m = e.message\n    Log.w(TAG, \"x \$m\")\n}\n"
        assertTrue(scan("Synthetic.kt", src).isNotEmpty())
        val ok = "fun f() {\n    val m = e.message\n    Log.w(TAG, \"x\")\n}\n"
        assertTrue(scan("Synthetic.kt", ok).isEmpty())
    }

    @Test fun matchesWtfAndIgnoresParenthesesInsideStrings() {
        assertTrue(flagged("Log.wtf(TAG, \"x \$url\")"))
        assertTrue(flagged("Log.w(TAG, \"oops ) here \${e.message}\")"))
        assertTrue(flagged("Log.w(TAG, \"( open\", e)"))
        assertEquals(false, flagged("Log.w(TAG, \"lone ) paren\")"))
    }

    @Test fun flagsBodyAndHeaders() {
        assertTrue(flagged("Log.w(TAG, \"b=\${response.body?.string()}\")"))
        assertTrue(flagged("Log.w(TAG, \"h \${resp.headers}\")"))
    }

    private val logStart = Regex("""\b(?:Log|Timber)\.(?:[divwe]|wtf)\(""")
    private val sanitizeStart = Regex("""UrlSanitizer\.sanitize\(""")
    private val messageLocal = Regex("""\bval\s+(\w+)\s*=[^\n]*\.(?:message|localizedMessage)\b""")
    private val throwableNames = "e|ex|err|error|exception|throwable|cause|it|t"
    private val urlish = Regex("(?i)url|uri|link|src|href")

    private val rules = listOf(
        "bare throwable in template" to
            Regex("""\$(?:$throwableNames)\b|\$\{\s*(?:$throwableNames)\s*\}"""),
        "exception or response message" to Regex("""\.message\b|localizedMessage|\.toString\(\)"""),
        "response or error body" to Regex("""\bbody=|errorBody|response\.body|\.string\(\)"""),
        "response headers" to Regex("""\bheaders\b"""),
        "throwable argument" to Regex(""""\s*,\s*[A-Za-z_]\w*\s*,?\s*\)\z"""),
    )

    private fun scan(name: String, text: String): List<String> {
        val messageLocals = messageLocal.findAll(text).map { it.groupValues[1] }.toSet()
        val violations = mutableListOf<String>()
        logStart.findAll(text).forEach { match ->
            val call = callText(text, match.range.last)
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            val checked = stripSanitized(call)
            val hits = rules.filter { it.second.containsMatchIn(checked) }.map { it.first }.toMutableList()
            if (interpolatedIdentifiers(checked).any { urlish.containsMatchIn(it) }) hits += "URL-like value"
            if (interpolatedIdentifiers(checked).any { it in messageLocals }) hits += "message held in a local"
            hits.forEach { violations += "$name:$line $it: ${call.replace(Regex("""\s+"""), " ")}" }
        }
        return violations
    }

    /** Removes every `UrlSanitizer.sanitize(...)` call, however deeply its parentheses nest. */
    private fun stripSanitized(call: String): String {
        val sb = StringBuilder()
        var from = 0
        while (true) {
            val m = sanitizeStart.find(call, from) ?: break
            sb.append(call, from, m.range.first)
            from = m.range.last + 1 + callText(call, m.range.last).length - 1
        }
        return sb.append(call, from, call.length).toString()
    }

    /** Identifiers used inside `$name` and `${...}` template expressions. */
    private fun interpolatedIdentifiers(call: String): List<String> {
        val ident = Regex("""[A-Za-z_]\w*""")
        val simple = Regex("""\$([A-Za-z_]\w*)""").findAll(call).map { it.groupValues[1] }
        val braced = Regex("""\$\{([^}]*)}""").findAll(call).flatMap { ident.findAll(it.groupValues[1]).map { m -> m.value } }
        return (simple + braced).toList()
    }

    /** Text from the `(` at [openIndex] to its closing `)`, ignoring parentheses inside string and char literals. */
    private fun callText(text: String, openIndex: Int): String {
        var depth = 0
        var i = openIndex
        while (i < text.length) {
            when {
                text[i] == '"' -> { i = skipString(text, i); continue }
                text[i] == '\'' && i + 2 < text.length && text[i + 2] == '\'' -> i += 2
                text[i] == '(' -> depth++
                text[i] == ')' -> if (--depth == 0) return text.substring(openIndex, i + 1)
            }
            i++
        }
        return text.substring(openIndex)
    }

    /** Index just past the string literal starting at [start], including `${...}` templates and raw strings. */
    private fun skipString(text: String, start: Int): Int {
        if (text.startsWith("\"\"\"", start)) {
            val end = text.indexOf("\"\"\"", start + 3)
            return if (end < 0) text.length else end + 3
        }
        var i = start + 1
        while (i < text.length) {
            when {
                text[i] == '\\' -> i += 2
                text[i] == '"' -> return i + 1
                text.startsWith("\${", i) -> i = skipBraces(text, i + 1)
                else -> i++
            }
        }
        return text.length
    }

    private fun skipBraces(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when {
                text[i] == '"' -> { i = skipString(text, i); continue }
                text[i] == '{' -> depth++
                text[i] == '}' -> if (--depth == 0) return i + 1
            }
            i++
        }
        return text.length
    }
}
