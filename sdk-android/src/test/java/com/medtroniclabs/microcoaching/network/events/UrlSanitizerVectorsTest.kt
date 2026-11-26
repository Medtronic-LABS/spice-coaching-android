package com.medtroniclabs.microcoaching.network.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Runs [UrlSanitizer] against the vectors shared with the mobile app, whose Dart
 * `UrlSanitizer` is the reference. The file is a byte-identical copy pinned by
 * SHA-256, so a local edit fails here and the copy is refreshed from the app repo
 * instead.
 */
class UrlSanitizerVectorsTest {

    private val bytes: ByteArray = readResource(VECTORS_PATH)
    private val root: JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    private val cases: List<Pair<String, String>> = root.getValue("cases").jsonArray.map {
        val case = it.jsonObject
        case.getValue("input").jsonPrimitive.content to case.getValue("expected").jsonPrimitive.content
    }
    private val names: List<String> = root.getValue("cases").jsonArray.map {
        it.jsonObject.getValue("name").jsonPrimitive.content
    }

    @Test
    fun `vectors file is the pinned version 1 copy`() {
        assertEquals(1, root.getValue("vectors_version").jsonPrimitive.int)
        assertEquals(EXPECTED_CASES, cases.size)
        assertEquals(VECTORS_SHA256, sha256Hex(bytes))
    }

    @Test
    fun `every vector input sanitises to its expected output`() {
        val failures = cases.indices.mapNotNull { i ->
            val (input, expected) = cases[i]
            val actual = UrlSanitizer.sanitize(input)
            if (actual == expected) null else "${names[i]}: expected <$expected> but was <$actual>"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `every expected output is a fixed point`() {
        val failures = cases.indices.mapNotNull { i ->
            val expected = cases[i].second
            val actual = UrlSanitizer.sanitize(expected)
            if (actual == expected) null else "${names[i]}: <$expected> became <$actual>"
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `sanitising twice equals sanitising once and stays within the cap`() {
        cases.forEachIndexed { i, (input, _) ->
            val once = UrlSanitizer.sanitize(input)
            assertEquals(names[i], once, UrlSanitizer.sanitize(once))
            assertTrue(names[i], once.length <= OUTPUT_CAP)
        }
    }

    @Test
    fun `random garbage never throws and never exceeds the cap on a small stack`() = onSmallStack(FUZZ_STACK_BYTES) {
        val random = Random(GARBAGE_SEED)
        repeat(GARBAGE_SAMPLES) {
            val input = garbage(random)
            val out = UrlSanitizer.sanitize(input)
            assertTrue("length ${out.length} for input of length ${input.length}", out.length <= OUTPUT_CAP)
        }
    }

    /** Long escape runs, multi-byte runs and `{`/`{id` tails reach the cap cut without deep recursion. */
    @Test
    fun `long runs at the cap are stack-safe on a 128 KB thread`() = onSmallStack(REGRESSION_STACK_BYTES) {
        listOf(
            "https://h.test/x/" + "%".repeat(8000),
            "https://h.test/x/" + "%25".repeat(2700),
            "https://h.test/" + "%E0%A6%95".repeat(300),
            "https://h.test/" + "\u0995".repeat(300),
            "https://h.test/x/" + "{".repeat(8000),
            "https://h.test/" + "{id/".repeat(2000),
            "https://h.test/" + "a/{id}/".repeat(1100),
            "https://h.test/x/" + "1".repeat(8000),
            "https://h.test/x/" + "a".repeat(8000),
            "https://h.test/x/" + "\u09E7".repeat(8000),
        ).forEach { input ->
            val once = UrlSanitizer.sanitize(input)
            assertTrue("length ${once.length}", once.length <= OUTPUT_CAP)
            assertEquals(once, UrlSanitizer.sanitize(once))
        }
    }

    @Test
    fun `Garay digits added in Unicode 16 count as digits`() = assertDigitRange(0x10D40)

    @Test
    fun `Sunuwar digits added in Unicode 16 count as digits`() = assertDigitRange(0x11BF0)

    @Test
    fun `Myanmar Pao and Eastern Pwo Karen digits added in Unicode 16 count as digits`() = assertDigitRange(0x116D0)

    @Test
    fun `Gurung Khema digits added in Unicode 16 count as digits`() = assertDigitRange(0x16130)

    @Test
    fun `Kirat Rai digits added in Unicode 16 count as digits`() = assertDigitRange(0x16D70)

    @Test
    fun `outlined digits added in Unicode 16 count as digits`() = assertDigitRange(0x1CCF0)

    @Test
    fun `Ol Onal digits added in Unicode 16 count as digits`() = assertDigitRange(0x1E5F1)

    /** The table is the whole reference digit set, so it holds on platforms with older Unicode data. */
    @Test
    fun `every range of the reference digit table masks six and keeps five`() {
        UrlSanitizer.DART_DECIMAL_DIGIT_RANGES.toList().chunked(2).forEach { (first, last) ->
            assertDigitRange(first)
            assertDigitRange(last - 5)
        }
    }

    /** Six consecutive digits from [first] form a masked run; five stay as percent-encoded text. */
    private fun assertDigitRange(first: Int) {
        fun digits(count: Int) = (first until first + count).joinToString("") { String(Character.toChars(it)) }
        val label = "U+%04X".format(first)
        assertEquals(label, "https://h.test/x/{id}", UrlSanitizer.sanitize("https://h.test/x/${digits(6)}"))
        assertEquals(
            label,
            "https://h.test/x/${DartUriLite.encodeComponent(digits(5))}",
            UrlSanitizer.sanitize("https://h.test/x/${digits(5)}"),
        )
    }

    /** Runs [block] on a thread with [stackBytes] of stack and rethrows anything it throws, Errors included. */
    private fun onSmallStack(stackBytes: Long, block: () -> Unit) {
        var failure: Throwable? = null
        val thread = Thread(null, { failure = runCatching(block).exceptionOrNull() }, "small-stack", stackBytes)
        thread.start()
        thread.join()
        failure?.let { throw it }
    }

    @Test
    fun `a port above 65535 is kept as written`() {
        assertEquals("https://h.test:65536/x", UrlSanitizer.sanitize("https://h.test:65536/x"))
    }

    @Test
    fun `a leading byte order mark is stripped by the decoder`() {
        assertEquals("https://h.test/x/abc", UrlSanitizer.sanitize("https://h.test/x/%EF%BB%BFabc"))
    }

    @Test
    fun `a lone surrogate becomes the replacement character`() {
        assertEquals("https://h.test/x/a%EF%BF%BDb", UrlSanitizer.sanitize("https://h.test/x/a\uD800b"))
        assertEquals("https://h.test/x/a%EF%BF%BDb", UrlSanitizer.sanitize("https://h.test/x/a\uDC00b"))
    }

    @Test
    fun `an invalid UTF-8 query key drops the whole query`() {
        assertEquals("https://h.test/q", UrlSanitizer.sanitize("https://h.test/q?%E0%A4=1&limit=2"))
    }

    @Test
    fun `supplementary-plane decimal digits count towards a masked run`() {
        val mathDigits = "\uD835\uDFD8\uD835\uDFD9\uD835\uDFDA\uD835\uDFDB\uD835\uDFDC\uD835\uDFDD"
        assertEquals("https://h.test/x/{id}", UrlSanitizer.sanitize("https://h.test/x/$mathDigits"))
    }

    /** Further Dart `Uri.parse` quirks, each output probed on the Dart 3.12.2 reference. */
    @Test
    fun `dart parser quirks outside the shared vectors match the reference`() {
        mapOf(
            "https:\\\\h.test/x" to "https://h.test/x",
            "https:/\\h.test/x" to "https://h.test/x",
            "https://h.test:0x50/x" to "https://h.test:80/x",
            "https://h.test:-1/x" to "https://h.test:-1/x",
            "https://h.test: 80/x" to "https://h.test:80/x",
            "https://h.test:99999999999999999999/x" to "<unparsable>",
            "ws://h.test:0/x" to "ws://h.test/x",
            "http://h.test:0/x" to "http://h.test:0/x",
            "https://a:b:c@h/x" to "https://h/x",
            "https://h:8@9/" to "https://9/",
            "https://h:1:2/x" to "<unparsable>",
            "https://[::1%eth0]/x" to "https://[::1%25eth0]/x",
            "https://[::1%25a@b]/" to "https://[::1%25a%40b]/",
            "https://[::FFFF:1.2.3.4]/" to "https://[::ffff:1.2.3.4]/",
            "https://[::01.2.3.4]/" to "<unparsable>",
            "https://h|x/" to "https://h%7cx/",
        ).forEach { (input, expected) -> assertEquals(input, expected, UrlSanitizer.sanitize(input)) }
    }

    private fun garbage(random: Random): String {
        val length = random.nextInt(GARBAGE_MAX_LENGTH + 1)
        val builder = StringBuilder(length + PREFIXES.maxOf { it.length })
        if (random.nextBoolean()) builder.append(PREFIXES[random.nextInt(PREFIXES.size)])
        // A third of the samples repeat one token for thousands of characters, the
        // shape that reaches the cap cut with a long escape or `{id` run.
        val runToken = if (random.nextInt(3) == 0) RUN_TOKENS[random.nextInt(RUN_TOKENS.size)] else null
        while (builder.length < length) {
            val repeatRun = runToken != null && random.nextInt(RUN_BREAK_ODDS) != 0
            builder.append(if (repeatRun) runToken else ALPHABET[random.nextInt(ALPHABET.size)])
        }
        return builder.toString()
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun readResource(path: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }.use { it.readBytes() }

    private companion object {
        const val VECTORS_PATH = "net_log/url_sanitizer_vectors.json"
        const val VECTORS_SHA256 = "df4218c144aa6627a4afb32cd29fdea19c621b4cddf97384a66eca15d2cc9955"
        const val EXPECTED_CASES = 153
        const val OUTPUT_CAP = 2048
        const val GARBAGE_SEED = 20261126L
        const val GARBAGE_SAMPLES = 3000
        const val GARBAGE_MAX_LENGTH = 9000
        const val RUN_BREAK_ODDS = 200
        const val FUZZ_STACK_BYTES = 256L * 1024
        const val REGRESSION_STACK_BYTES = 128L * 1024
        val RUN_TOKENS = listOf("%", "%25", "%EF%BB%BF", "{id", "{", "%E0%A6%95", "%2", "\u0995", "/{id}")
        val PREFIXES = listOf("https://", "http://h.test/", "wss://[", "ws://u@", "HTTPS:\\\\")
        val ALPHABET: List<String> = listOf(
            "%", "%", "0", "7", "a", "F", "e", "@", ";", "\\", "/", "?", "#", "&", "=", "[", "]", ":",
            "1", "9", "\u09E7", "\u0660", "\uD835\uDFD8", "\uD800", "\uDC00", "\uFEFF", "\u2026", " ",
            "%2", "%25", "%EF%BB%BF", "%E0%A4", "{id}", "limit=", "x", ".", "..",
        )
    }
}
