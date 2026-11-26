# SDK Network Event Capture Implementation Plan (spice-coaching-android#21)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record the SDK's own HTTP requests (sanitised, bounded) in its Room database and expose them to the host app through `getNetworkEvents` / `clearNetworkEvents`, implementing the SDK half of the Network page contract v1.

**Architecture:** One application interceptor plus a connection probe, installed by a single helper on the four OkHttp clients, hand sanitised events to a process-wide bounded writer that persists them in a new Room table `network_event` (500 rows). A Kotlin `UrlSanitizer` on its own Dart-compatible parser passes the shared vectors. A reader serves contract v1 maps; a clear call wipes the store on a user switch.

**Tech Stack:** Kotlin, OkHttp 4.12, Room (DB v36 -> v37), coroutines, JUnit 4; new test-only deps: Robolectric 4.14.1 (`@Config(sdk=[34])`), MockWebServer + okhttp-tls 4.12.0, coroutines-test 1.10.2, androidx.test core 1.6.1.

**Spec:** `docs/superpowers/specs/2026-11-26-network-event-capture-design.md` (sections cited as "spec §n"). App-side contract spec (read-only reference, other repo): `docs/superpowers/specs/2026-11-26-network-log-page-design.md` §8, §7f, §13 in `Medtronic-LABS/uhis_lf_mobile` branch `feat/778-network-log-page`. Parent: leapwell#14. Branch `feat/21-network-event-capture` from `origin/development`; one PR to `development`.

## Global Constraints
- Engineering Design Standards (leapwell `CLAUDE.md`): layered packages, one home for every constant (contract field names, error vocabulary, caps), narrow catches (the interceptor/recorder boundary catches are the documented exception), no hardcoded config, tests for new behaviour. No user-facing strings.
- Verify per task: `./gradlew :sdk-android:test --tests '<pkg>.*'` then `./gradlew :sdk-android:test`; add `./gradlew :sdk-android:assembleDebug` where `main` code or Gradle changes. Lint is not documented; CI does not run on PRs to `development`, so paste the local results into the PR. Never read `local.properties`, keystores or secrets.
- Contract v1 (verbatim): events `[{id, started_at_ms, method, url, status_code|null, duration_ms, error_type|null}]`, `max_id`, `sdk_event_version` = 1, `status` `ok|notInitialized`; `error_type` vocabulary `connectionTimeout|connectionError|receiveTimeout|badCertificate|cancel|unknown`; `duration_ms` = time to response headers; cache-only responses (`networkResponse == null`) not recorded; ids monotonic AUTOINCREMENT; `started_at_ms` wall-clock epoch ms.
- Caps: table 500 rows (purged in the same transaction as the insert); writer batch <= 64 rows; queue `Channel(256, DROP_OLDEST)`; `getNetworkEvents` clamps `limit` 1..500 and `afterId` >= 0; stored url <= 2,048 chars; sanitiser input limit 8,192 UTF-16 units, 8-pass decode.
- Only the 7 contract fields are stored; never headers, bodies, exception messages, user/CHW ids, tokens, a raw URL or any query value; the interceptor never reads headers/body/`e.message`; recording never blocks, fails or changes a call and `chain.proceed` is called exactly once.
- No `Log.*` line in `network/events/` may interpolate a url/request; log exception TYPE names only, once per type.
- The shared vectors file is copied byte-identical (pinned SHA-256) from the app repo after its addendum commit; `vectors_version` must be 1.

## Review Focus
1. The interceptor never fails, delays or alters a call: original response/exception returned unchanged, `proceed` exactly once, even when the sink/DB/sanitiser throws (T3, T5).
2. A raw URL or query value (presigned `X-Goog-*`/`X-Amz-*`) never reaches the queue, the table or a log line (T2, T3, T8).
3. `clearNetworkEvents` leaves no previous-user row or in-flight event behind, including queued events and events written by workers (epoch guard) (T5, T7).
4. Sanitiser parity: every shared vector, idempotence over every input, the cap-cut rule, strict UTF-8 decoding and the Dart `Uri` quirks, on the JVM and under Android's ICU tables (T2).
5. `getNetworkEvents`/`clearNetworkEvents` never throw: not initialised, store error, `afterId` > max, empty store, huge store (T7).
6. The migration SQL byte-matches Room's `createAllTables`; upgrade from v36 keeps data (T4).

## File Structure
New under `sdk-android/src/main/java/com/medtroniclabs/microcoaching/`: `network/events/` (`UrlSanitizer.kt`, `DartUriLite.kt`, `NetworkErrorType.kt`, `NetworkErrorClassifier.kt`, `NetworkEventSink.kt`, `NetworkEventInterceptor.kt`, `ConnectionProbe.kt`, `NetworkEvents.kt`, `NetworkEventRecorder.kt`), `data/db/entity/NetworkEventEntity.kt`, `data/db/dao/NetworkEventDao.kt`, `data/db/migration/MIGRATION_36_37.kt`, `sdk/diagnostics/` (`NetworkEvent.kt`, `NetworkEventsResult.kt`, `NetworkEventsReader.kt`). Modified: `data/db/MicroCoachingDatabase.kt` (:82), `network/NetworkModule.kt` (:128), `data/asset/AssetCache.kt` (:70-75), `ai/download/ResumableHttpDownloader.kt` (:99-102), `ai/model/ModelSizeProbe.kt` (:40-45), `MicroCoachingInitializer`, `MicroCoachingSDK.kt` (companion API), `network/MediaUrlResolver.kt`, `sdk-android/build.gradle.kts`, `gradle/libs.versions.toml`, `docs/ARCHITECTURE.md`, `README.md`. Tests under `sdk-android/src/test/java/...` and `src/test/resources/net_log/`.

---

### Task 1: Test toolchain
**Files:** Modify `gradle/libs.versions.toml`, `sdk-android/build.gradle.kts` (testImplementation only; no change to the AAR). Test `sdk-android/src/test/java/com/medtroniclabs/microcoaching/testing/RobolectricSmokeTest.kt`.
**Interfaces:** Produces the test libraries listed in the Tech Stack for later tasks.
- [ ] **Step 1:** Write `RobolectricSmokeTest`: an in-memory `MicroCoachingDatabase` (`@Config(sdk=[34])`) inserts and reads one `LearningCompletionEntity`; also one MockWebServer round trip and one `runTest` coroutine test.
- [ ] **Step 2:** `./gradlew :sdk-android:test --tests '*RobolectricSmokeTest'` -> FAIL (dependencies missing).
- [ ] **Step 3:** Add the dependencies (pinned versions from the Tech Stack) as `testImplementation`.
- [ ] **Step 4:** Run it -> PASS, then the full `./gradlew :sdk-android:test` (existing tests unchanged). If Robolectric 4.14.1 does not run under AGP 9.1/compileSdk 36, stop and report BLOCKED with the error (spec §10 fallback).
- [ ] **Step 5:** Commit `build(sdk): add Robolectric, MockWebServer and coroutines-test for network events (#21)`.

### Task 2: Kotlin UrlSanitizer and shared vectors
**Files:** Create `network/events/UrlSanitizer.kt`, `network/events/DartUriLite.kt`; Copy `url_sanitizer_vectors.json` (from the app repo, post-addendum) to `src/test/resources/net_log/`; Test `network/events/UrlSanitizerVectorsTest.kt`.
**Produces:** `internal object UrlSanitizer { fun sanitize(raw: String): String }` pure and total.
- [ ] **Step 1:** Write `UrlSanitizerVectorsTest`: asserts `vectors_version == 1`, the case count and the pinned SHA-256 of the copied file; every case `sanitize(input) == expected`; `sanitize(expected) == expected`; `sanitize(sanitize(input)) == sanitize(input)` and length <= 2048 for all inputs; a seeded random-garbage totality test (never throws, never > 2048). Parse JSON with `kotlinx.serialization.json`.
- [ ] **Step 2:** Run it -> FAIL (missing classes).
- [ ] **Step 3:** Implement `UrlSanitizer` as a line-for-line port of the Dart reference (spec §7f: same constants, regexes with `\z` not `$`, order, trailing `…` strip/re-add, cap-cut `_dropPartialToken`, hand-written strict UTF-8 decoder with leading-BOM strip, `decodeStable` 8 passes, query rule, `catch RuntimeException -> "<unparsable>"`) and `DartUriLite` (the ~150-line parser reproducing Dart 3.12.2 `Uri.parse` for the cases in spec §7f items 1-7).
- [ ] **Step 4:** Run the test -> PASS (all vectors). Any vector that cannot be matched: stop and report BLOCKED with the case.
- [ ] **Step 5:** Commit `feat(sdk): Kotlin URL sanitiser passing the shared vectors (#21)`.

### Task 3: Interceptor, error classifier, probe
**Files:** Create `network/events/NetworkErrorType.kt` (enum with each wire name defined once), `NetworkErrorClassifier.kt`, `NetworkEventSink.kt`, `NetworkEventInterceptor.kt`, `ConnectionProbe.kt`, `NetworkEvents.kt`; Test `NetworkErrorClassifierTest.kt`, `NetworkEventInterceptorTest.kt`.
**Consumes:** `UrlSanitizer.sanitize`. **Produces:** `internal data class NetworkEventRow(val startedAtMs: Long, val method: String, val url: String /* already sanitised */, val statusCode: Int?, val durationMs: Long, val errorType: NetworkErrorType?)` and `internal fun interface NetworkEventSink { fun offer(event: NetworkEventRow) }` (Task 5 maps a `NetworkEventRow` to `NetworkEventEntity` at write time); `internal object NetworkEvents { fun instrument(b: OkHttpClient.Builder): OkHttpClient.Builder }` (adds the application interceptor + `ConnectionProbe` network interceptor; the sink is resolved from `NetworkEventRecorder.current()` at call time).
- [ ] **Step 1:** Tests: classifier covers every row of spec §7b (cancel; `SSLPeerUnverifiedException` / `SSLHandshakeException` with a `CertificateException` cause -> `badCertificate`; timeout not connected -> `connectionTimeout`, connected -> `receiveTimeout`; `UnknownHostException`/`ConnectException`/other `SocketException`/`SSLException` -> `connectionError`; anything else -> `unknown`). Interceptor tests with MockWebServer + capturing sink + fake clock: 200/404/500 one row each; a presigned `X-Goog-Signature` URL stores no query and the signature string appears nowhere; 302 -> 200 gives one row with the original URL and status 200; cache-hit (temp `Cache`) gives 0 rows, a 304 gives 1; read timeout -> `receiveTimeout`; throwing `SocketFactory` -> `connectionTimeout`; refused port / throwing `Dns` -> `connectionError`; untrusted `HeldCertificate` -> `badCertificate`; `call.cancel()` -> `cancel`; a throwing sink still returns the 200 with the body intact; a counting downstream interceptor sees exactly one `proceed`; status 999 -> `null` + `unknown`; Authorization header and request body markers never appear in any stored field.
- [ ] **Step 2:** Run -> FAIL. **Step 3:** Implement per spec §7b (`startedAt` wall, `t0` mono, single `proceed`, boundary `try/catch RuntimeException` around recording only, `finally` clears the probe mark, sanitise on the calling thread). **Step 4:** Run -> PASS.
- [ ] **Step 5:** Commit `feat(sdk): network event interceptor and error classifier (#21)`.

### Task 4: Entity, DAO, migration 36 -> 37
**Files:** Create `data/db/entity/NetworkEventEntity.kt`, `data/db/dao/NetworkEventDao.kt`, `data/db/migration/MIGRATION_36_37.kt`; Modify `MicroCoachingDatabase.kt` (add entity + `abstract fun networkEventDao()`, `MICRO_COACHING_ROOM_VERSION` 36 -> 37, `.addMigrations(MIGRATION_36_37)`); Test `NetworkEventDaoTest.kt`, `Migration36To37Test.kt`.
**Produces:** `NetworkEventDao`: `suspend insertAll(rows)`, `suspend purge(keep: Int)` (`DELETE ... WHERE id <= (SELECT MAX(id) FROM network_event) - :keep`), `@Transaction suspend insertAndPurge(rows, keep = 500)`, `suspend after(afterId: Long, limit: Int): List<NetworkEventEntity>` (`id > :afterId ORDER BY id ASC LIMIT :limit`), `suspend maxId(): Long` (`COALESCE(MAX(id),0)`), `suspend deleteAll()`. DDL exactly as spec §7c (AUTOINCREMENT, no index, no CHECK).
- [ ] **Step 1:** Tests (Robolectric, in-memory Room): 550 inserts leave the newest 500; `after` ordering and limit; `maxId` 0 when empty; ids keep growing after `deleteAll`. Migration test: `sqlite_master.sql` for `network_event` from a Room-created v37 DB equals the SQL from running `MIGRATION_36_37.migrate` on a blank `SupportSQLiteDatabase`; running it twice is idempotent (`IF NOT EXISTS`); `MICRO_COACHING_ROOM_VERSION == 37`.
- [ ] **Step 2:** Run -> FAIL. **Step 3:** Implement (follow the `MIGRATION_35_36.kt` pattern). **Step 4:** Run -> PASS, plus the full suite.
- [ ] **Step 5:** Commit `feat(sdk): network_event table and migration 36 to 37 (#21)`.

### Task 5: NetworkEventRecorder
**Files:** Create `network/events/NetworkEventRecorder.kt`; Test `NetworkEventRecorderTest.kt`.
**Consumes:** `NetworkEventDao`, `NetworkEventSink`. **Produces:** `internal object NetworkEventRecorder : NetworkEventSink { fun bind(context: Context); fun current(): NetworkEventSink?; suspend fun clear(): Long /* clearedThroughId */; val epoch: Int }`.
- [ ] **Step 1:** Tests (coroutines-test, fake DAO): batching <= 64 per transaction; 200 parallel offers yield 200 rows; with the DAO blocked, 500 offers all return within a bound and the drop counter > 0; a DAO throwing `SQLiteException` / `IllegalStateException` is swallowed and the writer continues (type logged once); events queued before `clear()` are not written (epoch); clear is serialised after an in-flight batch; unbound recorder drops and counts; `bind` is idempotent and never opens the DB on the caller thread.
- [ ] **Step 2:** Run -> FAIL. **Step 3:** Implement per spec §7d (own `SupervisorJob + Dispatchers.IO` scope with a type-only `CoroutineExceptionHandler`, `Channel(256, DROP_OLDEST, onUndeliveredElement)`, `trySend`, single consumer, `Mutex` for writes and clears, `epoch`). **Step 4:** Run -> PASS.
- [ ] **Step 5:** Commit `feat(sdk): bounded single-writer network event recorder (#21)`.

### Task 6: Wiring and transport registry
**Files:** Modify `network/NetworkModule.kt` (:128, before `builder.build()`), `data/asset/AssetCache.kt` (:70-75), `ai/download/ResumableHttpDownloader.kt` (:99-102 per-call builder), `ai/model/ModelSizeProbe.kt` (:40-45; make `probe` `internal` for tests), `MicroCoachingInitializer.kt` and the `MicroCoachingSDK` constructor (call `NetworkEventRecorder.bind(context)`); Test: per-client row tests, `TransportRegistryTest.kt`.
**Consumes:** `NetworkEvents.instrument`, `NetworkEventRecorder.bind`.
- [ ] **Step 1:** Tests: `ResumableHttpDownloader.download` against MockWebServer records 1 row; `ModelSizeProbe.probe` records 1-2 rows (HEAD then ranged GET); `NetworkModule.createOkHttpClient(config)` records 1 row; an `AssetCache` download (Robolectric, in-memory DB) records 1 row. `TransportRegistryTest` (JVM source scan of `sdk-android/src/main/**/*.kt`, comments stripped) for `OkHttpClient.Builder()`, `.newBuilder()` on clients, `HttpURLConnection`, `openConnection`, `ExoPlayer.Builder`, `AsyncImage(` with a non-file model: the (file, count) set must equal a registry listing kind (instrumented | non-goal | inert) and reason (ExoPlayer, Coil avatar, OTLP exporter, ML Kit, the three unused `OkHttpClient` imports); instrumented files must contain `NetworkEvents.instrument(`; adding or removing a site fails with a message telling the developer to instrument it and register it.
- [ ] **Step 2:** Run -> FAIL. **Step 3:** Add `NetworkEvents.instrument(builder)` at the four builders (innermost application interceptor on the API client, after auth and logging) and the `bind` calls. **Step 4:** Run -> PASS plus `./gradlew :sdk-android:assembleDebug`.
- [ ] **Step 5:** Commit `feat(sdk): record requests from the four SDK HTTP clients (#21)`.

### Task 7: Public API
**Files:** Create `sdk/diagnostics/NetworkEvent.kt`, `NetworkEventsResult.kt`, `NetworkEventsReader.kt`; Modify `MicroCoachingSDK.kt` (companion); Test `NetworkEventsReaderTest.kt`, `ClearNetworkEventsTest.kt`.
**Produces (keep exactly; package `com.medtroniclabs.microcoaching.sdk.diagnostics`, kept by `consumer-rules.pro`):** `suspend fun MicroCoachingSDK.getNetworkEvents(afterId: Long, limit: Int): NetworkEventsResult` and `suspend fun clearNetworkEvents(): NetworkEventsClearResult` (companion; never throw; run on IO internally); `enum class NetworkEventsStatus(val wire: String?) { OK("ok"), NOT_INITIALIZED("notInitialized"), STORE_ERROR(null) }`; `data class NetworkEvent(id: Long, startedAtMs: Long, method: String, url: String, statusCode: Int?, durationMs: Long, errorType: String?)`; `data class NetworkEventsResult(status, events: List<NetworkEvent>, maxId: Long)` with `sdkEventVersion` = 1 (single constant `SDK_EVENT_VERSION`) and `toContractMap(): Map<String, Any?>` (the ONLY home of the wire key names); `data class NetworkEventsClearResult(status, clearedThroughId: Long)`. Logic in `NetworkEventsReader(isSdkInitialized: () -> Boolean, store)` so it is testable without building the SDK.
- [ ] **Step 1:** Tests (Robolectric + Room): not initialised -> `NOT_INITIALIZED`, `[]`, 0; `afterId -5` treated as 0; limit 0 -> 1, 10,000 -> 500; empty store; `afterId` > max returns empty with the real `max_id`; a 500-row store paged in three calls; a store error -> `STORE_ERROR`, nothing thrown; `toContractMap` key set and value types match the v1 schema (Long ids, nullable `status_code`/`error_type`, `duration_ms >= 0`). Clear: returns `clearedThroughId`, store empty afterwards, a stale queued event is not written, a new event gets an id above `clearedThroughId`, works when the SDK is not initialised but the store is bound.
- [ ] **Step 2:** Run -> FAIL. **Step 3:** Implement per spec §7e-7h (same-transaction rows + `maxId`, clamps, epoch bump then locked `deleteAll`). **Step 4:** Run -> PASS.
- [ ] **Step 5:** Commit `feat(sdk): getNetworkEvents and clearNetworkEvents public API (#21)`.

### Task 8: Log leak fixes
**Files:** Modify `network/MediaUrlResolver.kt` (:45, :58, :78, :96, :125), `ai/download/ResumableHttpDownloader.kt` (:117, :261), `data/asset/AssetCache.kt` (:275-279); Test `NoUrlInLogsTest.kt`.
- [ ] **Step 1:** `NoUrlInLogsTest` (JVM source scan): no `Log.*` line in the listed files interpolates a url, `src`, `it.message`/`cause.message` or a response/error body. **Step 2:** Run -> FAIL.
- [ ] **Step 3:** Drop the URL/body from each line or log `UrlSanitizer.sanitize(url)` and the status code only. **Step 4:** Run -> PASS plus the full suite.
- [ ] **Step 5:** Commit `fix(sdk): stop logging presigned URLs and storage error bodies (#21)`.

### Task 9: Docs, version, AAR and hand-off
**Files:** Modify `docs/ARCHITECTURE.md` (v37, 25 tables, "Network events" section, transport inventory), `README.md`, `sdk-android/build.gradle.kts` (`SDK_VERSION` and publishing `version` -> `0.7.0-SNAPSHOT`).
- [ ] **Step 1:** Update the docs and version. **Step 2:** `./gradlew :sdk-android:test` and `./gradlew :sdk-android:assembleRelease` (the AAR is `sdk-android/build/outputs/aar/sdk-android-release.aar`; a person copies it into the app's `android/app/libs/micro-coaching-sdk.aar` in the app PR - do not copy it from here). **Step 3:** Write the hand-off notes in the PR body draft: the exact public API, the plugin handler sketch (spec §11), the compatibility matrix, the one-time full resync side effect of the DB bump, the unverified Room downgrade behaviour. **Step 4:** Commit `docs(sdk): network events architecture, version 0.7.0-SNAPSHOT and hand-off (#21)`.

## Task ledger (filled during execution)
| Task | Commit | Spec review | Quality review | Security / QA |
|---|---|---|---|---|
| T1-T9 | | | | `lw-security` on T2, T3, T5, T7, T8 |
