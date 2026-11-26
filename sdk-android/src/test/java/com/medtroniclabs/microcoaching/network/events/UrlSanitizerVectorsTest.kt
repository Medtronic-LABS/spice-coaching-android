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
    fun `random garbage never throws and never exceeds the cap`() {
        val random = Random(GARBAGE_SEED)
        repeat(GARBAGE_SAMPLES) {
            val input = garbage(random)
            val out = UrlSanitizer.sanitize(input)
            assertTrue("length ${out.length} for input of length ${input.length}", out.length <= OUTPUT_CAP)
        }
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
        val mathDigits = "𝟘𝟙𝟚𝟛𝟜𝟝"
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
        while (builder.length < length) builder.append(ALPHABET[random.nextInt(ALPHABET.size)])
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
        val PREFIXES = listOf("https://", "http://h.test/", "wss://[", "ws://u@", "HTTPS:\\\\")
        val ALPHABET: List<String> = listOf(
            "%", "%", "0", "7", "a", "F", "e", "@", ";", "\\", "/", "?", "#", "&", "=", "[", "]", ":",
            "1", "9", "১", "٠", "𝟘", "\uD800", "\uDC00", "﻿", "…", " ",
            "%2", "%25", "%EF%BB%BF", "%E0%A4", "{id}", "limit=", "x", ".", "..",
        )
    }
}
