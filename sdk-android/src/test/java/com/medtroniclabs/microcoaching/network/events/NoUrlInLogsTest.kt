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
 * Only the files in [scanned] (the five above plus every main file under
 * `network/events/`) are checked, not the rest of the SDK; adding a file to the
 * list is how its logging comes under this rule.
 *
 * Allowed in a log call: fixed text, a status code, the exception class name
 * and `UrlSanitizer.sanitize(url)`.
 */
class NoUrlInLogsTest {

    private val root = "src/main/java/com/medtroniclabs/microcoaching"

    private companion object {
        const val BASELINE = "src/test/resources/net_log/log_leak_baseline.txt"

        /** Handled separately from this rule; not part of the ratchet. */
        val EXCLUDED = setOf("LiteRtLmService.kt", "ChatMessageComposer.kt")
    }

    private val scanned = listOf(
        "$root/ai/download/ResumableHttpDownloader.kt",
        "$root/network/MediaUrlResolver.kt",
        "$root/data/asset/AssetCache.kt",
        "$root/ui/richtext/blocks/RichImageBlock.kt",
        "$root/network/SourceDocumentUrlStore.kt",
    ) + (File("$root/network/events").listFiles { f -> f.extension == "kt" }?.map { it.path }?.sorted() ?: emptyList())

    @Test
    fun logCallsDoNotLeakUrlsBodiesOrMessages() {
        assertTrue("network/events files missing from the scan", scanned.size > 3)
        val violations = scanned.flatMap { path ->
            val file = File(path)
            assertTrue("missing $path (cwd=${File(".").absolutePath})", file.exists())
            scan(file.name, file.readText())
        }
        assertTrue("log calls leak sensitive data:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    /**
     * Ratchet for the rest of the SDK. Files outside [scanned] still hold log
     * calls that predate this rule (mostly exception messages). The counts per
     * file and rule are recorded in [BASELINE]; a count may only go down. A new
     * leak, or a file not listed, fails here; fixing one fails until the
     * baseline is lowered (run with LOGLEAK_BASELINE_WRITE=true to regenerate).
     * Files whose logging is handled separately are listed in [EXCLUDED].
     */
    @Test
    fun otherFilesDoNotGetWorse() {
        val covered = scanned.map { File(it).canonicalPath }.toSet()
        val current = File(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.canonicalPath !in covered && it.name !in EXCLUDED }
            .flatMap { f ->
                scan(f.name, f.readText()).map { v -> "${f.name}|${v.substringAfter(' ').substringBefore(':')}" }
            }
            .groupingBy { it }.eachCount().toSortedMap()
        val lines = current.map { (k, n) -> "$k|$n" }
        if (System.getenv("LOGLEAK_BASELINE_WRITE") == "true") {
            File(BASELINE).writeText(lines.joinToString("\n", postfix = "\n"))
        }
        val baseline = File(BASELINE).readLines().filter { it.isNotBlank() }.associate {
            it.substringBeforeLast('|') to it.substringAfterLast('|').toInt()
        }
        val worse = current.filter { (k, n) -> n > (baseline[k] ?: 0) }.map { (k, n) -> "$k: $n (baseline ${baseline[k] ?: 0})" }
        val better = baseline.filter { (k, n) -> (current[k] ?: 0) < n }.map { (k, n) -> "$k: ${current[k] ?: 0} (baseline $n)" }
        assertTrue("new log leaks outside the scanned files:\n" + worse.joinToString("\n"), worse.isEmpty())
        assertTrue("lower the baseline ($BASELINE):\n" + better.joinToString("\n"), better.isEmpty())
    }

    // Scanner self-tests: each synthetic leak must be flagged, each safe line must not.

    private fun log(call: String) = "fun f() {\n    $call\n}\n"

    private fun flagged(call: String) = scan("Synthetic.kt", log(call)).isNotEmpty()

    @Test fun flagsAnAliasOfAUrlLikeName() {
        assertTrue(scan("Synthetic.kt", "fun f() {\n    val u = request.url.trim()\n    Log.d(TAG, \"x \$u\")\n}\n").isNotEmpty())
        assertEquals(
            false,
            scan("Synthetic.kt", "fun f() {\n    val n = body?.missingPaths?.size ?: 0\n    Log.d(TAG, \"x \$n\")\n}\n").isNotEmpty(),
        )
        assertEquals(
            false,
            scan("Synthetic.kt", "fun f() {\n    val ref = LogRef.of(key)\n    Log.d(TAG, \"x \$ref\")\n}\n").isNotEmpty(),
        )
    }

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
        listOf("url", "signedUri", "downloadLink", "src", "hrefValue", "presignedURL", "missingPaths", "storagePath").forEach { n ->
            assertTrue(n, flagged("Log.d(TAG, \"x \$$n\")"))
            assertTrue(n, flagged("Log.d(TAG, \"x \${$n.trim()}\")"))
        }
        assertEquals(false, flagged("Log.d(TAG, \"x \${UrlSanitizer.sanitize(url)}\")"))
        assertEquals(false, flagged("Log.d(TAG, \"x \${UrlSanitizer.sanitize(a.b(c), url)}\")"))
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
        assertTrue(flagged("Log.d(TAG, \"cache HIT key=\$key\")"))
        assertTrue(flagged("Log.w(TAG, \"oops ) here \${e.message}\")"))
        assertTrue(flagged("Log.w(TAG, \"( open\", e)"))
        assertEquals(false, flagged("Log.w(TAG, \"lone ) paren\")"))
    }

    @Test fun flagsBodyAndHeaders() {
        assertTrue(flagged("Log.w(TAG, \"b=\${response.body?.string()}\")"))
        assertTrue(flagged("Log.w(TAG, \"h \${resp.headers}\")"))
    }

    @Test fun flagsUrlishNamesOutsideTemplates() {
        assertTrue(flagged("Log.d(TAG, resolved.url)"))
        assertTrue(flagged("Log.d(TAG, \"x \" + url)"))
        assertTrue(flagged("Log.d(TAG, \"x \$renewed\")"))
        assertTrue(flagged("Log.d(TAG, \"x \" + renewed)"))
        assertTrue(flagged("Log.d(TAG, \"x \" + entry.presignedUrl)"))
        assertTrue(flagged("Log.d(TAG, \"x \" + signed_url)"))
        assertEquals(false, flagged("Log.d(TAG, \"x \" + during + security + curl)"))
        assertEquals(false, flagged("Log.d(TAG, \"fetchUrl returned a link to the src\")"))
    }

    @Test fun flagsAnyFinalIdentifierArgument() {
        assertTrue(flagged("Log.w(TAG, msg, e)"))
        assertTrue(flagged("Log.w(TAG, buildMsg(), e)"))
        assertTrue(flagged("Log.w(TAG, \"x\" + y, e)"))
        assertTrue(flagged("Log.w(TAG, \"x\" + y, error)"))
    }

    @Test fun flagsCauseAndVarMessageLocal() {
        assertTrue(flagged("Log.w(TAG, \"x \${e.cause}\")"))
        val src = "fun f() {\n    var m = e.message\n    Log.w(TAG, \"x \$m\")\n}\n"
        assertTrue(scan("Synthetic.kt", src).isNotEmpty())
    }

    private val logStart = Regex("""\b(?:Log|Timber)\.(?:[divwe]|wtf)\(""")
    private val sanitizeStart = Regex("""UrlSanitizer\.sanitize\(""")
    private val simpleChain = Regex("""[\w.?!]+(?:\(\))?(?:\.[\w?!]+(?:\(\))?)*""")
    private val localAssignment = Regex("""\b(?:val|var)\s+(\w+)\s*=([^\n]*)""")
    private val messageLocal = Regex("""\b(?:val|var)\s+(\w+)\s*=[^\n]*\.(?:message|localizedMessage)\b""")
    private val throwableNames = "e|ex|err|error|exception|throwable|cause|it|t"
    private val urlWords = setOf("url", "urls", "uri", "uris", "link", "links", "src", "href", "paths", "object")
    private val urlNames = setOf("renewed", "key", "storagePath")
    private val identifier = Regex("""[A-Za-z_]\w*""")
    private val nameSegment = Regex("""[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+""")

    private val rules = listOf(
        "bare throwable in template" to
            Regex("""\$(?:$throwableNames)\b|\$\{\s*(?:$throwableNames)\s*\}"""),
        "exception or response message" to Regex("""\.message\b|\.cause\b|\.throwable\b|localizedMessage|\.toString\(\)"""),
        "response or error body" to Regex("""\bbody=|errorBody|response\.body|\.string\(\)"""),
        "response headers" to Regex("""\bheaders\b"""),
        "throwable argument" to Regex(""",\s*[A-Za-z_]\w*\s*,?\s*\)\z"""),
    )

    private fun scan(name: String, text: String): List<String> {
        val messageLocals = messageLocal.findAll(text).map { it.groupValues[1] }.toSet()
        // An alias is a plain copy or member chain of a URL-like name (`val u = url`,
        // `val p = request.url.trim()`); anything with arguments or operators is a
        // computed value (a hash, a count) and is left to the name check.
        val urlLocals = localAssignment.findAll(text).filter { m ->
            val rhs = m.groupValues[2].trim()
            simpleChain.matches(rhs) && identifier.findAll(rhs).any { isUrlName(it.value) }
        }.map { it.groupValues[1] }.toSet()
        val violations = mutableListOf<String>()
        logStart.findAll(text).forEach { match ->
            val call = callText(text, match.range.last)
            val line = text.substring(0, match.range.first).count { it == '\n' } + 1
            val checked = stripSanitized(call)
            val hits = rules.filter { it.second.containsMatchIn(checked) }.map { it.first }.toMutableList()
            val names = identifier.findAll(codeView(checked)).map { it.value }.toList()
            if (names.any(::isUrlName)) hits += "URL-like value"
            if (names.any { it in messageLocals }) hits += "message held in a local"
            if (names.any { it in urlLocals }) hits += "URL-like value held in a local"
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

    /** A name is URL-like if it is listed, or any camelCase or snake_case segment is a URL word. */
    private fun isUrlName(name: String): Boolean =
        name in urlNames || nameSegment.findAll(name).any { it.value.lowercase() in urlWords }

    /**
     * The call with string-literal text removed, keeping the code of `$name` and
     * `${...}` templates, so a name is matched only where it is a value, not in prose.
     */
    private fun codeView(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] != '"') {
                sb.append(text[i++])
                continue
            }
            if (text.startsWith("\"\"\"", i)) {
                i = skipString(text, i)
                continue
            }
            i++
            while (i < text.length && text[i] != '"') {
                when {
                    text[i] == '\\' -> i += 2
                    text.startsWith("\${", i) -> {
                        val end = skipBraces(text, i + 1)
                        sb.append(' ').append(codeView(text.substring(i + 2, end - 1))).append(' ')
                        i = end
                    }
                    text[i] == '$' -> {
                        val m = identifier.matchAt(text, i + 1)
                        if (m != null) sb.append(' ').append(m.value).append(' ')
                        i += 1 + (m?.value?.length ?: 0)
                    }
                    else -> i++
                }
            }
            i++
        }
        return sb.toString()
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
