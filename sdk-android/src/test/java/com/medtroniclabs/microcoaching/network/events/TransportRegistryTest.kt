package com.medtroniclabs.microcoaching.network.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Spec §7a: every site this scan can recognise (OkHttp builders, URL
 * connections, ExoPlayer and OTLP builders, remote AsyncImage, ML Kit model
 * downloads) is either instrumented with [NetworkEvents.instrument] or
 * registered here as a deliberate non-goal or an inert reference. The scan
 * reads the main sources with comments stripped. It does not see
 * rememberAsyncImagePainter, Coil ImageRequest/ImageLoader, MediaItem.fromUri
 * outside ExoPlayer.Builder, WebView, DownloadManager, or a newBuilder() whose
 * receiver name lacks "client"; those need a manual review.
 */
class TransportRegistryTest {

    private enum class Kind { INSTRUMENTED, NON_GOAL, INERT }

    private enum class SitePattern { OKHTTP_CLIENT, CLIENT_NEW_BUILDER, HTTP_URL_CONNECTION, OPEN_CONNECTION, EXOPLAYER_BUILDER, REMOTE_ASYNC_IMAGE, OTLP_EXPORTER, MLKIT_MODEL_DOWNLOAD, UNUSED_OKHTTP_IMPORT }

    private data class Site(val file: String, val pattern: SitePattern)

    private data class Entry(val site: Site, val count: Int, val kind: Kind, val reason: String)

    private fun entry(file: String, pattern: SitePattern, kind: Kind, reason: String, count: Int = 1) =
        Entry(Site("$PKG/$file", pattern), count, kind, reason)

    private val registry = listOf(
        entry("network/NetworkModule.kt", SitePattern.OKHTTP_CLIENT, Kind.INSTRUMENTED, "API client behind Retrofit"),
        entry("data/asset/AssetCache.kt", SitePattern.OKHTTP_CLIENT, Kind.INSTRUMENTED, "presigned asset downloads"),
        entry("ai/download/ResumableHttpDownloader.kt", SitePattern.OKHTTP_CLIENT, Kind.INSTRUMENTED, "model and STT archive downloads, one client per call"),
        entry("ai/model/ModelSizeProbe.kt", SitePattern.OKHTTP_CLIENT, Kind.INSTRUMENTED, "model size HEAD and ranged GET"),
        entry("ui/video/ExoPlayerSurface.kt", SitePattern.EXOPLAYER_BUILDER, Kind.NON_GOAL, "ExoPlayer streams presigned media (open decision 4)"),
        entry("ui/common/AvatarCircle.kt", SitePattern.REMOTE_ASYNC_IMAGE, Kind.NON_GOAL, "Coil default loader for the avatar URL"),
        entry("domain/telemetry/TelemetryManager.kt", SitePattern.OTLP_EXPORTER, Kind.NON_GOAL, "OTLP exporter (spec §3)"),
        entry("ai/translation/OnDeviceTranslator.kt", SitePattern.MLKIT_MODEL_DOWNLOAD, Kind.NON_GOAL, "ML Kit translate pack, downloaded by Play services"),
        entry("sdk/hooks/AssessmentSubmittedHandler.kt", SitePattern.UNUSED_OKHTTP_IMPORT, Kind.INERT, "unused OkHttpClient import, no client built"),
        entry("sdk/hooks/ReferralSubmittedHandler.kt", SitePattern.UNUSED_OKHTTP_IMPORT, Kind.INERT, "unused OkHttpClient import, no client built"),
        entry("ai/model/ModelDownloadWorker.kt", SitePattern.UNUSED_OKHTTP_IMPORT, Kind.INERT, "unused OkHttpClient import, no client built"),
    )

    @Test
    fun everyTransportSiteIsRegistered() {
        val sources = mainSources()
        assertTrue("no main sources found", sources.size > 100)

        val problems = check(scan(sources), registry, sources)

        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun anUnregisteredClientFailsWithInstructions() {
        val sources = mapOf("$PKG/new/Client.kt" to "val c = OkHttpClient.Builder().build()")

        val problems = check(scan(sources), emptyList(), sources)

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("$PKG/new/Client.kt"))
        assertTrue(problems.single().contains("NetworkEvents.instrument("))
        assertTrue(problems.single().contains("register"))
    }

    @Test
    fun aRemovedSiteFails() {
        val problems = check(emptyMap(), registry.take(1), emptyMap())

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("$PKG/network/NetworkModule.kt"))
    }

    @Test
    fun anInstrumentedEntryWithoutTheCallFails() {
        val sources = mapOf("$PKG/network/NetworkModule.kt" to "val b = OkHttpClient.Builder()\nb.build()")

        val problems = check(scan(sources), registry.take(1), sources)

        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("NetworkEvents.instrument("))
    }

    @Test
    fun scanCountsEachPatternAndIgnoresComments() {
        val source = """
            import okhttp3.OkHttpClient
            // OkHttpClient.Builder() in a line comment
            /* ExoPlayer.Builder(ctx) /* nested */ HttpURLConnection */
            val url = "https://example.test/x" // trailing
            val a = OkHttpClient.Builder().build()
            val b = OkHttpClient()
            val c = sharedClient.newBuilder().build()
            val r = request.newBuilder().build()
            val d = URL(url).openConnection() as HttpURLConnection
            val p = ExoPlayer.Builder(ctx).build()
            val e = OtlpHttpSpanExporter.builder()
            translator.downloadModelIfNeeded(conditions)
        """.trimIndent()

        val found = scan(mapOf("F.kt" to source))

        assertEquals(
            mapOf(
                Site("F.kt", SitePattern.OKHTTP_CLIENT) to 2,
                Site("F.kt", SitePattern.CLIENT_NEW_BUILDER) to 1,
                Site("F.kt", SitePattern.OPEN_CONNECTION) to 1,
                Site("F.kt", SitePattern.HTTP_URL_CONNECTION) to 1,
                Site("F.kt", SitePattern.EXOPLAYER_BUILDER) to 1,
                Site("F.kt", SitePattern.OTLP_EXPORTER) to 1,
                Site("F.kt", SitePattern.MLKIT_MODEL_DOWNLOAD) to 1,
            ),
            found,
        )
    }

    @Test
    fun asyncImageCountsOnlyNonFileModels() {
        val source = """
            fun Screen(file: File, url: String) {
                AsyncImage(model = file, contentDescription = null)
                AsyncImage(model = File(dir, "a.png"), contentDescription = null)
                SubcomposeAsyncImage(model = url, contentDescription = null)
            }
        """.trimIndent()

        assertEquals(mapOf(Site("F.kt", SitePattern.REMOTE_ASYNC_IMAGE) to 1), scan(mapOf("F.kt" to source)))
    }

    @Test
    fun anUnusedOkHttpImportIsInertButAUsedOneIsNot() {
        val unused = "import okhttp3.OkHttpClient\nimport okhttp3.Request\nval r: Request? = null"
        val used = "import okhttp3.OkHttpClient\nval c: OkHttpClient? = null"

        assertEquals(
            mapOf(Site("U.kt", SitePattern.UNUSED_OKHTTP_IMPORT) to 1),
            scan(mapOf("U.kt" to unused, "V.kt" to used)),
        )
    }

    // --- scan and check -------------------------------------------------------

    private fun check(found: Map<Site, Int>, registry: List<Entry>, sources: Map<String, String>): List<String> {
        val expected = registry.associate { it.site to it.count }
        val problems = mutableListOf<String>()
        (found.keys + expected.keys).sortedBy { "${it.file}:${it.pattern}" }.forEach { site ->
            val actual = found[site] ?: 0
            val registered = expected[site] ?: 0
            when {
                registered == 0 -> problems += "New transport site ${site.pattern} x$actual in ${site.file}: " +
                    "if it builds an OkHttp client, wrap the builder in NetworkEvents.instrument( immediately " +
                    "before build(); then register it in TransportRegistryTest with its kind and reason."
                actual != registered -> problems += "Transport site ${site.pattern} in ${site.file}: found $actual, " +
                    "registered $registered. Instrument any new client with NetworkEvents.instrument( and " +
                    "register it, or update the registry entry in TransportRegistryTest for a removed site."
            }
        }
        registry.filter { it.kind == Kind.INSTRUMENTED }.forEach { entry ->
            val source = sources[entry.site.file] ?: return@forEach
            val calls = INSTRUMENT_CALL.findAll(stripComments(source)).count()
            if (calls < entry.count) {
                problems += "${entry.site.file} is registered as instrumented but calls NetworkEvents.instrument( " +
                    "$calls time(s) for ${entry.count} client(s)."
            }
        }
        return problems
    }

    private fun scan(sources: Map<String, String>): Map<Site, Int> {
        val found = mutableMapOf<Site, Int>()
        sources.forEach { (file, raw) ->
            val code = stripComments(raw)
            countSites(code).forEach { (pattern, count) ->
                if (count > 0) found[Site(file, pattern)] = count
            }
        }
        return found
    }

    private fun countSites(code: String): Map<SitePattern, Int> = mapOf(
        SitePattern.OKHTTP_CLIENT to OKHTTP_CLIENT.findAll(code).count(),
        SitePattern.CLIENT_NEW_BUILDER to CLIENT_NEW_BUILDER.findAll(code).count(),
        SitePattern.HTTP_URL_CONNECTION to HTTP_URL_CONNECTION.findAll(code).count(),
        SitePattern.OPEN_CONNECTION to OPEN_CONNECTION.findAll(code).count(),
        SitePattern.EXOPLAYER_BUILDER to EXOPLAYER_BUILDER.findAll(code).count(),
        SitePattern.REMOTE_ASYNC_IMAGE to ASYNC_IMAGE.findAll(code).count { !isFileModel(code, it.groupValues[1]) },
        SitePattern.OTLP_EXPORTER to OTLP_EXPORTER.findAll(code).count(),
        SitePattern.MLKIT_MODEL_DOWNLOAD to MLKIT_DOWNLOAD.findAll(code).count(),
        SitePattern.UNUSED_OKHTTP_IMPORT to if (isUnusedOkHttpImport(code)) 1 else 0,
    )

    private fun isFileModel(code: String, model: String): Boolean {
        val expression = model.trim()
        if (expression.startsWith("File(") || expression.startsWith("java.io.File(")) return true
        if (!IDENTIFIER.matches(expression)) return false
        return Regex("""\b${Regex.escape(expression)}\s*:\s*(?:java\.io\.)?File\b""").containsMatchIn(code)
    }

    private fun isUnusedOkHttpImport(code: String): Boolean {
        if (!OKHTTP_IMPORT.containsMatchIn(code)) return false
        return !Regex("""\bOkHttpClient\b""").containsMatchIn(OKHTTP_IMPORT.replace(code, ""))
    }

    /** Removes line and (nested) block comments, leaving string and char literals intact. */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                    out.append(source, i, end)
                    i = end
                }
                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length && source[j] != quote && source[j] != '\n') {
                        j += if (source[j] == '\\') 2 else 1
                    }
                    val end = minOf(j + 1, source.length)
                    out.append(source, i, end)
                    i = end
                }
                source.startsWith("//", i) -> {
                    i = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                }
                source.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < source.length) {
                        if (source.startsWith("/*", i)) {
                            depth++
                            i += 2
                        } else if (source.startsWith("*/", i)) {
                            depth--
                            i += 2
                            if (depth == 0) break
                        } else {
                            if (source[i] == '\n') out.append('\n')
                            i++
                        }
                    }
                    out.append(' ')
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun mainSources(): Map<String, String> {
        val root = listOf(File("src/main"), File("sdk-android/src/main")).first { it.isDirectory }
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
    }

    private companion object {
        const val PKG = "java/com/medtroniclabs/microcoaching"

        val OKHTTP_CLIENT = Regex("""\bOkHttpClient\s*(?:\.\s*Builder\s*)?\(\s*\)""")
        val CLIENT_NEW_BUILDER = Regex("""(?i)\b\w*client\w*\s*(?:\(\s*\))?\s*\.\s*newBuilder\s*\(""")
        val HTTP_URL_CONNECTION = Regex("""\bHttps?URLConnection\b""")
        val OPEN_CONNECTION = Regex("""\.\s*openConnection\s*\(""")
        val EXOPLAYER_BUILDER = Regex("""\bExoPlayer\s*\.\s*Builder\s*\(""")
        val ASYNC_IMAGE = Regex("""\b\w*AsyncImage\s*\(\s*(?:model\s*=\s*)?([^,)\n]+(?:\([^)]*\))?)""")
        val OTLP_EXPORTER = Regex("""\bOtlp\w*Exporter\s*\.\s*builder\s*\(""")
        val MLKIT_DOWNLOAD = Regex("""\.\s*downloadModelIfNeeded\s*\(""")
        val OKHTTP_IMPORT = Regex("""(?m)^\s*import\s+okhttp3\.OkHttpClient\s*$""")
        val INSTRUMENT_CALL = Regex("""\bNetworkEvents\s*\.\s*instrument\s*\(""")
        val IDENTIFIER = Regex("""[A-Za-z_]\w*""")
    }
}
