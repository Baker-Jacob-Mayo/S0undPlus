# S0undPlus — Engineering advisory

Written 2026-10-10 after a full read of the repository (every Kotlin, Gradle, manifest and workflow file; about
11,400 lines). It is addressed to whoever implements the next changes, including an AI assistant with less context
than the author of this document. Every item names the files and line numbers to touch, the change to make, and how
to prove it works. Line numbers refer to the tree at commit `cb04f98`.

Nothing in this document was verified on a device or against live Twitch. The README is honest about that, and the
items below inherit the same caveat: where a claim about Twitch's behaviour could not be checked from here, it is
marked **verify**.

---

## 1. How to work on this repository

Read this section before changing anything.

**Layout.** `twitch-core/` is a plain Kotlin/JVM library (no Android imports allowed) holding every piece of Twitch
protocol logic, with 148 unit tests under `twitch-core/src/test`. `app/` is the Android TV app: Compose for TV UI,
Media3 player, DataStore persistence. The app depends on the core through the composite build declared in
`settings.gradle.kts`. The README's "Architecture" section is accurate and worth reading first.

**What you can run locally.**

| Command | Needs | Purpose |
|---|---|---|
| `./gradlew -p twitch-core test` | JDK 17+, internet to Maven Central | The core tests. Run after every core change. |
| `./gradlew :app:assembleRelease` | Android SDK platform 37 | Builds the APK. Usually only CI has the SDK. |

If the Android SDK is not available, push the branch and read the **Summary** step of the `Build` workflow: it prints
every Kotlin compiler error (`e:` lines), lint errors and an `aapt2` dump of the APK. One compile error per push is
expensive, so re-read your diff before pushing. In the environment this advisory was written in, Maven Central
rate-limited the Gradle plugin download, so even the core tests could not be run here. CI is the source of truth.

**Conventions that must be kept.**

- Core first: anything that parses, fetches or decides something about Twitch goes in `twitch-core` with a test.
  The app stays a thin UI over the core.
- Every `catch (_: Exception)` in a coroutine must be preceded by `catch (e: CancellationException) { throw e }`.
  The code does this everywhere already; keep doing it.
- One-shot decisions read settings from storage with `c.settings.settings.first()`, not from a `StateFlow` that was
  started with `AppSettings()` defaults. See the comments in `PlayerController.kt:111` and `ChatController.kt:79`.
- 4-space indent, trailing commas in multi-line calls, KDoc on public types, no wildcard imports. Match the style
  around you.
- Do not change default settings. Ad handling stays **Off** by default. Nothing is sent to a third party unless the
  viewer opts in.
- Do not change `.github/workflows/release.yml`, signing, `applicationId`, or `minSdk` without the owner's say-so.
- Do not bump dependency versions as part of a bug fix. `gradle/libs.versions.toml` was checked on 2026-10-10.
- Do not add `Log` calls that print tokens, cookies or proxy passwords.

**Working rhythm.** One advisory item per commit, with the item ID (for example `P0-1`) in the commit message.
Fix, test, push, read CI, then move to the next item. Do not batch unrelated items; a compile error in one hides the
others.

---

## 2. Priority overview

| ID | Title | Kind | Effort | Main files |
|---|---|---|---|---|
| P0-1 | VOD thumbnails are broken URLs | Bug | S | `Models.kt`, `HelixApiTest.kt` |
| P0-2 | Unhandled exceptions in screen scopes crash the app | Bug | S | `Scopes.kt`, `AppContainer.kt`, `RecentMessages.kt`, `LoginScreen.kt` |
| P1-1 | Quality menu: nothing selected or focused after choosing Auto | Bug (TV nav) | S | `PlayerScreen.kt` |
| P1-2 | Peek panel never gets focus if opened before the list loads | Bug (TV nav) | S | `PlayerScreen.kt` |
| P1-3 | Player network traffic bypasses the shared OkHttp client | Robustness | S | `PlayerScreen.kt`, `Preview.kt` |
| P1-4 | Response leaked when a call is cancelled | Robustness | S | `Http.kt` |
| P1-5 | Misleading error text for login and playback failures | Bug (UX) | S–M | `LoginScreen.kt`, `PlayerController.kt`, `PlaybackApi.kt` |
| P2-1 | Global emotes and badges re-downloaded on every stream | Performance | M | `Emotes.kt`, `HelixApi.kt`, `ChatController.kt`, `AppContainer.kt` |
| P2-2 | RaidWatcher gives up on 429 and closes before reconnecting | Robustness | S | `RaidWatcher.kt` |
| P2-3 | Following tab hides load errors when favorites exist | Bug (UX) | S | `HomeScreen.kt` |
| P2-4 | Settings text fields write to disk on every keystroke | Performance | S | `SettingsScreen.kt` |
| P2-5 | Build workflow also runs on release tags | CI | XS | `build.yml` |
| P2-6 | Deep-linked streams show the lowercase login as the title | Bug (UX) | S | `AppRoot.kt`, `PlayerController.kt` |
| P2-7 | `m.twitch.tv` links are parsed but not claimed | Bug (UX) | XS | `AndroidManifest.xml` |
| P2-8 | README test count is stale | Docs | XS | `README.md` |
| P2-9 | Version code scheme is not reproducible | Build | S | `app/build.gradle.kts` |
| P2-10 | No unit tests for app-side pure logic | Tests | M | new `app/src/test` |
| P2-11 | Missing core tests for small pure classes | Tests | S | new test files |
| P3-x | Features, in suggested order | Feature | varies | see section 6 |

Effort: XS under 15 minutes, S under an hour, M a few hours, L a day or more.

---

## 3. P0 — fix first

### P0-1. VOD thumbnails are broken URLs

**Where.** `twitch-core/src/main/kotlin/com/s0undplus/twitch/Models.kt:113-114`:

```kotlin
internal fun String.sized(width: Int, height: Int): String =
    replace("{width}", width.toString()).replace("{height}", height.toString())
```

**Why it is wrong.** Helix `Get Streams` returns thumbnails with `{width}x{height}` placeholders, and that case is
handled. Helix `Get Videos` returns `%{width}x%{height}` (with percent signs). The replacement above turns
`thumb0-%{width}x%{height}.jpg` into `thumb0-%440x%248.jpg`, which is not a valid image URL. Every `VideoCard` on a
channel page shows a blank thumbnail, and because `Video.toPlayTarget()` (`app/.../ui/nav/Navigation.kt:36-37`)
copies the URL into `PlayTarget.Vod.thumbnailUrl`, the broken URL is also persisted into watch history
(`HistoryEntry.thumbnailUrl`). The test fixture at `HelixApiTest.kt:116` uses the stream-style placeholder for a
video, so the test passes while real responses fail. **Verify** on first contact that Twitch still sends
`%{width}`; this is long-standing documented behaviour.

**Fix.**

```kotlin
/** Helix thumbnails/box art contain `{width}x{height}` placeholders; videos use `%{width}x%{height}`. */
internal fun String.sized(width: Int, height: Int): String =
    replace("%{width}", width.toString())
        .replace("%{height}", height.toString())
        .replace("{width}", width.toString())
        .replace("{height}", height.toString())
```

Order matters: the percent forms must be replaced first, otherwise `%{width}` becomes `%440`.

**Tests.** Change the fixture at `HelixApiTest.kt:116` to `https://cdn/v-%{width}x%{height}.jpg` and assert that
`video.thumbnail()` equals `https://cdn/v-440x248.jpg`. Add a `ModelsTest` with three cases: stream-style
placeholder, video-style placeholder, and an empty string (a VOD that is still being recorded has an empty
`thumbnail_url`), which must stay empty.

**Note.** History entries saved by earlier builds keep the broken URL. That is acceptable; they age out of the
50-entry history. Do not write a migration.

### P0-2. Unhandled exceptions in screen scopes crash the app

**Where.**

- `app/src/main/kotlin/com/s0undplus/tv/ui/util/Scopes.kt:17` creates
  `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`.
- `app/src/main/kotlin/com/s0undplus/tv/AppContainer.kt:38` creates `appScope` the same way.
- `twitch-core/src/main/kotlin/com/s0undplus/twitch/chat/RecentMessages.kt:24-31` catches only `IOException`.
- `app/src/main/kotlin/com/s0undplus/tv/ui/login/LoginScreen.kt:59-80` catches only `IOException` subclasses.

**Why it is wrong.** Neither scope has a `CoroutineExceptionHandler`. On Android, an exception that escapes a
`launch` in such a scope goes to the thread's uncaught-exception handler and kills the process. There is at least
one realistic path: `ChatController.start()` (`ChatController.kt:78-86`) calls `RecentMessagesApi.fetch`, which
decodes the body with kotlinx.serialization. A `200` response whose body is not JSON (a captive portal, a Cloudflare
error page, an outage page from the community service) throws `SerializationException`, which is an
`IllegalArgumentException`, not an `IOException`. It escapes `fetch`, escapes the `launch`, and the app dies on
opening any stream while "Load recent messages" is on. The login screen has the same shape: a non-JSON reply from
the device-code endpoint throws inside the `LaunchedEffect` and crashes.

**Fix, in three places.**

1. `RecentMessages.kt`: replace `catch (_: IOException)` with

   ```kotlin
   } catch (e: CancellationException) {
       throw e
   } catch (_: Exception) {
       emptyList()
   }
   ```

   (import `kotlin.coroutines.cancellation.CancellationException`).

2. `Scopes.kt` and `AppContainer.kt`: add a handler that logs and swallows:

   ```kotlin
   private val crashGuard = CoroutineExceptionHandler { _, e -> Log.e("S0undPlus", "Unhandled coroutine failure", e) }
   // ...
   CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + crashGuard)
   ```

   Put one shared definition in a small file such as `app/.../ui/util/CrashGuard.kt` and use it from both. This is a
   safety net, not a license to stop catching exceptions at the call site.

3. `LoginScreen.kt:75-78`: after the `IOException` branch add `catch (_: Exception)` that sets
   `LoginState.Failed("Twitch sent an unexpected reply. Try again.")`. Also see P1-5 for the `TwitchApiException`
   branch that should come *before* the `IOException` one.

**Tests.** In `twitch-core`, add `RecentMessagesTest` with MockWebServer: a `200` response with body `<html>`
must yield an empty list, and a `200` with a valid `{"messages":[...]}` body must parse. The app-side handler has
no test; it is covered by compiling on CI.

---

## 4. P1 — fix next

### P1-1. Quality menu: nothing selected or focused after choosing Auto

**Where.** `app/src/main/kotlin/com/s0undplus/tv/ui/player/PlayerScreen.kt:716` passes
`current = if (qualityChoice == null) qualityLabel else qualityChoice` into `QualityMenu` (lines 948-971).

**Why it is wrong.** Picking Auto sets `qualityChoice = QualityPreference.AUTO`, which is the string `"auto"`.
The menu compares against the label `"Auto"` (line 964), so no row is marked selected and the `selectedQualityItem`
`FocusRequester` is attached to no row. The focus request at line 458 then throws `IllegalStateException`, which is
swallowed at line 462, and the menu opens with nothing focused. The same happens in audio-only mode, where
`qualityLabel` is `"Audio only"` and the menu filters audio-only rungs out. On a TV, a menu with no focus is a
dead end until the viewer presses Back.

**Fix.** Pass `current = qualityLabel` (it is already `"Auto"` or the variant name, computed by `resolve()` at
line 378). In `QualityMenu`, attach the requester to the Auto row whenever no variant matches:

```kotlin
val matched = variants.any { it.name == current }
QualityRow("Auto", current == "Auto", { onPick(null) }, if (!matched) Modifier.focusRequester(selectedItem) else Modifier)
for (v in variants) {
    val selected = current == v.name
    QualityRow(v.name, selected, { onPick(v) }, if (selected) Modifier.focusRequester(selectedItem) else Modifier)
}
```

**Verify (device).** Open Quality, pick Auto, open Quality again: Auto is ticked and focused. Pick 720p, reopen:
720p is ticked and focused. Turn on audio-only, open Quality: Auto is focused.

### P1-2. Peek panel never gets focus if opened before the list loads

**Where.** `PlayerScreen.kt:455-466` requests `firstPeekItem` once, when `menu` changes. `PeekPanel`
(lines 896-945) attaches that requester only to the first stream card, and shows "Loading channels…" when
`streams` is empty.

**Why it is wrong.** Pressing Down (or Blue) calls `openPeek()`, which starts loading and opens the panel in the
same frame. The list is still empty, the requester is attached to nothing, the request throws and is swallowed.
When the streams arrive a moment later nothing re-requests focus.

**Fix.** In `PeekPanel`, request focus when the list becomes non-empty:

```kotlin
LaunchedEffect(streams.isNotEmpty()) {
    if (streams.isNotEmpty()) {
        try {
            firstItem.requestFocus()
        } catch (_: IllegalStateException) {
            // not attached this frame
        }
    }
}
```

Keep the existing request in `PlayerScreen` for the case where the list was already loaded.

### P1-3. Player network traffic bypasses the shared OkHttp client

**Where.** `PlayerScreen.kt:147-154` builds the `ExoPlayer` with defaults, and `exo.setMediaItem(item, start)` at
line 391 therefore uses Media3's `DefaultHttpDataSource` (built on `HttpURLConnection`). Only the HTTP-proxy
branch at line 389 uses OkHttp. `Preview.kt:43` has the same default.

**Why it matters.** The app otherwise funnels everything through one `OkHttpClient`
(`AppContainer.kt:26-30`) with its timeouts and connection pool. The player is the heaviest network user and
uses a different stack: separate pool, separate TLS session cache, different timeout defaults, and a different
`User-Agent`. It also means a future "route playback through X" change has two code paths to cover.

**Fix.** Give both players a media-source factory backed by the shared client:

```kotlin
ExoPlayer.Builder(context)
    .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(container.http)))
    // existing audio attributes etc.
```

`DefaultMediaSourceFactory(DataSource.Factory)` is in `androidx.media3.exoplayer.source`. Keep the explicit
`HlsMediaSource` branch for the proxied case; it still needs the per-source client. `OkHttpDataSource` is marked
`@UnstableApi`; the file already opts in.

**Verify.** Compiles on CI. On a device: a live stream and a VOD both play; quality switching still works.

### P1-4. Response leaked when a call is cancelled

**Where.** `twitch-core/src/main/kotlin/com/s0undplus/twitch/Http.kt:35-37`:

```kotlin
override fun onResponse(call: Call, response: Response) {
    cont.resume(response)
}
```

**Why it is wrong.** If the coroutine was cancelled between `enqueue` and `onResponse`, the resume is ignored and
the `Response` is never closed. OkHttp then logs "A connection to … was leaked" and holds the socket until GC. This
happens routinely: every time the viewer leaves a screen while a request is in flight.

**Fix.** Use the cancellation callback form of `resume`. With kotlinx.coroutines 1.11 it is:

```kotlin
override fun onResponse(call: Call, response: Response) {
    cont.resume(response) { _, _, _ -> response.close() }
}
```

If that overload does not compile, use `if (cont.isCancelled) response.close() else cont.resume(response)`,
which has a tiny race but is still better than leaking.

**Tests.** None needed beyond the existing suite; this is covered by compilation.

### P1-5. Misleading error text for login and playback failures

**Where and what.**

1. `LoginScreen.kt:75-78` catches `IOException` and says "Couldn't reach Twitch. Check your connection."
   `TwitchApiException` extends `IOException`, so an HTTP 400 for a wrong or disabled client ID shows the same
   message. The viewer will check their Wi-Fi when the problem is the build. Add, *before* the `IOException`
   branch:

   ```kotlin
   } catch (e: TwitchApiException) {
       state = LoginState.Failed("Twitch rejected the request (HTTP ${e.status}). Check the client ID this build was made with.")
   ```

2. `PlayerController.kt:99-101` maps any exception through `describe()` (`Paged.kt:108-112`), whose 401 text is
   "Your Twitch session expired". Playback is anonymous and never uses the session, so a GQL or usher 401/403
   produces a false instruction to sign in again. In `PlayerController.load()` add a branch for
   `TwitchApiException` that says `"Twitch refused the playback request (HTTP ${e.status})."`.

3. Subscriber-only VODs. Usher answers a restricted VOD with HTTP 403 and a JSON error body (**verify** the exact
   `error_code`; it has been `vod_manifest_restricted`). `PlaybackApi.load()` (`PlaybackApi.kt:123-134`) turns it
   into the generic exception from item 2. Add `class VodRestrictedException(val videoId: String) : IOException(...)`
   next to `ChannelOfflineException`, throw it from `vod()` when the status is 403, and show
   "This video is for subscribers only." with a Back action, like the offline case. Test it in `PlaybackApiTest`
   with a MockWebServer `403`.

---

## 5. P2 — improvements

### P2-1. Global emotes and badges are re-downloaded on every stream

`ThirdPartyEmotes.load` (`Emotes.kt:51-68`) fetches FFZ, BTTV and 7TV global sets plus the channel sets every time
`ChatController.loadDecorations()` runs, and `HelixApi.badges()` (`HelixApi.kt:122-130`) refetches global badges
too. Channel surfing (peek, channel +/-, raids) makes that four to seven requests and roughly a megabyte of JSON per
switch; the 7TV global set is large.

Suggested shape, all in the core so it can be tested:

- Split `ThirdPartyEmotes.load(channelId)` into `loadGlobal(): List<Emote>` and `loadChannel(id): List<Emote>`,
  keeping `load(channelId)` as a convenience that merges both.
- Add `class EmoteCache(private val source: ThirdPartyEmotes, private val ttlMs: Long = 60 * 60_000, private val nowMs: () -> Long = System::currentTimeMillis)`
  with `suspend fun directory(channelId: String?): EmoteDirectory`. Cache the global list and up to ~10 channel
  lists with their fetch time; refetch after the TTL. Guard concurrent loads with a `Mutex`.
- Do the same for global badges in a tiny `BadgeCache`, or add the TTL inside `HelixApi.badges`.
- Create one instance of each in `AppContainer` and use them from `ChatController`.

Test with MockWebServer: two `directory()` calls within the TTL produce one set of requests; a call after the TTL
(advance the injected clock) refetches.

### P2-2. RaidWatcher gives up on 429 and closes before reconnecting

`RaidWatcher.kt:130-132` treats every 4xx from the subscribe call as permanent, including 429 (rate limit), which
silently disables raid redirect for the rest of the session. Let 429 fall through to the backoff path.

`RaidWatcher.kt:116-120` handles `session_reconnect` by throwing, which closes the old socket before the new one
is open. Twitch's guidance is to connect to the new URL first and let the old socket close after the new welcome
(**verify** against the EventSub WebSocket docs). The current ordering can drop a raid notification that lands in
the gap. This is rare; fix it only if you are already in the file, and keep `RaidWatcherTest` green. The test at
`RaidWatcherTest.kt` already covers welcome → subscribe → raid; add a case for a 429 followed by a successful
subscribe.

### P2-3. Following tab hides load errors when favorites exist

`HomeScreen.kt:359-365` only shows the loading, error and empty states when the live list, favorites and offline
list are all empty. With one favorite saved, a failed `followedStreams` call renders the favorites and nothing else,
with no hint that the "Live now" section is missing because of an error. Add a full-span grid item after the
favorites section that shows `live.error` with a "Try again" `PillButton` calling `vm.refreshFollowing()` when
`live.error != null && live.items.isEmpty()`.

### P2-4. Settings text fields write to disk on every keystroke

`SettingsScreen.kt:154-160` (blocked words and users) and `231-254` (HTTP proxy host, port, user, password) call
`update { }` from `onValueChange`, so every character typed on the TV keyboard is a DataStore transaction that
rewrites every key. Debounce: keep the local `remember` state as now, and persist from a `LaunchedEffect` keyed on
the local value with a `delay(500)` before `update`. Make sure the final value is saved when the field loses focus
too (`onFocusChanged` is already available in `TvTextField`).

### P2-5. Build workflow also runs on release tags

`.github/workflows/build.yml:3-6` triggers on every push, including `v*` tags, so a release builds twice. Change to

```yaml
on:
  push:
    branches: ['**']
  pull_request:
  workflow_dispatch:
```

### P2-6. Deep-linked streams show the lowercase login as the title

`AppRoot.kt:76` builds `PlayTarget.Live(l.login, l.login, null)` for `s0undplus://live/<login>` and `twitch.tv/<login>`
links, so the player overlay and notifications-opened streams show `xqc` instead of `xQc`. `PlayerController.watchRaids`
(`PlayerController.kt:194-201`) already calls `helix.userByLogin` when `userId` is null; capture the display name there
too and expose it as a `StateFlow<String>` the overlay can read, or resolve the user in `AppRoot` before pushing the
route (one Helix call, show a spinner meanwhile). Prefer the first; it never delays playback.

### P2-7. `m.twitch.tv` links are parsed but not claimed

`DeepLinkParser` accepts `m.twitch.tv`, but the manifest intent-filter (`AndroidManifest.xml:52-60`) lists only
`www.twitch.tv` and `twitch.tv`. Add `<data android:host="m.twitch.tv" />`.

### P2-8. README test count is stale

README "Project status" says 146 unit tests; there are 148 `@Test` methods. Update the number when you next touch
the README, and stop quoting an exact count if it keeps drifting ("about 150").

### P2-9. Version code scheme is not reproducible

`app/build.gradle.kts:26-27` derives `versionCode` from the wall clock at configuration time. Two consequences:
the same commit built twice gets two different APKs, and with `org.gradle.configuration-cache=true` Gradle does not
treat `System.currentTimeMillis()` as an input, so consecutive local builds can reuse a cached, older code.

If reproducibility matters, switch to `git rev-list --count HEAD` **plus a fixed offset larger than any code already
installed**. The clock scheme has produced codes in the tens of thousands and grows by about 525,000 a year; a plain
commit count would be lower than what is on existing TVs and Android refuses downgrades. Use something like
`2_000_000 + commitCount`, keep the `-PappVersionCode` override, and document the offset in the comment. This is
optional; the current scheme works, it is just not reproducible.

### P2-10. No unit tests for app-side pure logic

There is no `app/src/test`. Several app classes have no Android dependencies and are cheap to test on the JVM:
`Format` (`ui/util/Format.kt`: `count`, `clock`, `uptime`, `ago`), `AppSettings.adSources()` and
`AppSettings.chatFilter` (`data/AppSettings.kt`), `UpdateUi.statusLabel()`. Add
`testImplementation("junit:junit:4.13.2")` and `testImplementation(kotlin("test"))` to `app/build.gradle.kts`,
create `app/src/test/kotlin/com/s0undplus/tv/...`, and run them in the `android` CI job with
`./gradlew :app:testReleaseUnitTest`. Note `Format.ago` says "1 months ago" for 30–59 days; fix the plural while
adding the test.

### P2-11. Missing core tests for small pure classes

Not directly tested: `TwitchLanguages`, `parseTwitchDuration` (`Models.kt:119-123`), `Backoff`,
`BadgeDirectory.from`, and `RecentMessagesApi` (see P0-2). Each is a 10-line test. `ChatFilter` is only exercised
inside `ChatFormatterTest`; give it its own file covering bots, commands, blocked words (case-insensitive) and
blocked users.

---

## 6. P3 — features, in suggested order

Each entry says what, where it would live, and the smallest version worth shipping. Pick with the owner; see the
open questions in section 9.

1. **Stream info in the player.** Show viewer count, uptime and the current title/category in `ControlsOverlay`,
   refreshed every 60–120 s with `helix.streamByLogin` from `PlayerController`. Small; everything exists.

2. **Highlights and uploads on channel pages.** `HelixApi.videos` hard-codes `type=archive`
   (`HelixApi.kt:103-111`). Add a `type` parameter and a three-way filter row ("Past broadcasts · Highlights ·
   Uploads") above the grid in `ChannelScreen`. Small.

3. **Ad-break countdown while hiding.** In Hide mode the screen says "Ad break — hidden" with no duration.
   `AdBreakDetector` already parses `DURATION` / `PLANNED-DURATION` from the DATERANGE tag; expose the remaining
   seconds through `AdBreakPlanner` and show "Ad break · 0:28 left". Medium; core change with tests, then UI.

4. **Mention highlight.** The viewer is signed in, so their login is known
   (`container.session.tokens.value?.login`). Highlight chat lines containing `@login` with a brand-coloured
   background in `ChatPanel`. Small.

5. **Bits and reply context in chat.** `ChatMessage.bits` is parsed but never shown; reply threads
   (`reply-parent-display-name`, `reply-parent-msg-body` tags) are ignored. Show a small "⬥ 500" chip and an
   "@user:" prefix. Small–medium; add the tags to `ChatProtocol.parseMessage` with tests first.

6. **Animated emote toggle.** Decoding dozens of animated WebP/GIF emotes is heavy on cheap TV chips. Add a setting
   that swaps `twitchEmoteUrl` to `/static/`, uses 7TV/BTTV `1x`/static variants where available, and removes
   `AnimatedImageDecoder` from the Coil loader when off. Medium.

7. **Zero-width 7TV emotes.** 7TV flags them with bit `1 shl 8` in the emote's `flags`; they should overlay the
   previous emote instead of taking their own slot. Medium; parser change in `Emotes.kt` with a fixture, then
   layout in `ChatPanel`.

8. **Language picker.** The "Language row" setting cycles through 26 entries one click at a time
   (`SettingsScreen.kt:127-131`). Replace with a dialog listing all languages. Small.

9. **Clips.** Channel clips tab and `clips.twitch.tv` / `twitch.tv/<login>/clip/<slug>` deep links. Playback needs
   another undocumented GraphQL operation (`VideoAccessToken_Clip`) that returns direct MP4 URLs; keep it in
   `PlaybackApi` next to the existing token code and treat it as equally fragile. Large; ask the owner first.

10. **Live-edge indicator and latency.** Show `exo.currentLiveOffset` in the overlay ("2.4 s behind live") and a
    "Jump to live" button. Small.

11. **Video resize mode.** A Fit/Zoom toggle for 4:3 or letterboxed streams (`AspectRatioFrameLayout` resize mode).
    Small.

12. **Split `PlayerScreen.kt`.** At 980 lines it holds key handling, recovery, ad wiring, overlay, peek and quality
    menu. Move `ControlsOverlay`, `PeekPanel`, `QualityMenu` and `SeekBar` into their own files with no behaviour
    change. Do this only as its own commit, verified by a green CI build, never mixed with a feature.

Not recommended: enabling R8/minify (`isMinifyEnabled = false` at `app/build.gradle.kts:72`) until someone can run
the APK on a device. The gain (smaller APK, faster cold start) is real, but a wrong keep rule for
kotlinx.serialization or Media3 shows up only at runtime. Also not feasible: Turbo or subscriber ad-free playback,
which would require the viewer's web-player token; the app's own client ID cannot be used for the playback token.

---

## 7. Known fragilities to verify on first contact with real Twitch

Nothing below is known to be broken. These are the assumptions the code makes that nobody has yet confirmed against
the live service. Check them in this order the first time the app runs on a TV with a real client ID.

| Area | Code | What to confirm |
|---|---|---|
| Device login | `TwitchAuth.kt:88-115` | Polling error strings are `authorization_pending`, `slow_down`, `access_denied`, and `expired_token` or `invalid device code`. The QR encodes `verification_uri`; confirm it pre-fills the code on the phone. |
| Token refresh | `AuthSession.kt`, `TwitchAuth.refresh` | Twitch rotates refresh tokens on each refresh (handled: the new one is stored). Refresh tokens for *public* clients have a limited lifetime (30 days has been documented); if so, a TV unused for a month will need a fresh sign-in, which the code handles by signing out on 400/401. |
| Playback token | `PlaybackApi.kt:140-212` | The full-query form is tried first, then persisted hash `ed230aa1…`. Watch for `errors[].message` such as `PersistedQueryNotFound` or an integrity error; if Twitch starts requiring `Client-Integrity` for anonymous tokens, playback breaks here and the fix is this file only. |
| Usher errors | `PlaybackApi.kt:123-134` | Offline channel → 404 with a JSON error body (handled). Sub-only VOD → 403 (see P1-5). |
| Chat | `ChatClient.kt`, `Irc.kt` | Anonymous `justinfan` login, `CAP REQ`, `JOIN`; `ROOMSTATE` arrives and sets connected. A suspended channel yields a `NOTICE` which is shown as a chat notice. |
| Recent messages | `RecentMessages.kt` | `GET /api/v2/recent-messages/<login>?limit=60` returns `{"messages":[...]}` with raw IRC lines. |
| Emotes | `Emotes.kt:52-58` | 7TV `GET /v3/emote-sets/global` and `/v3/users/twitch/{id}` (7TV has been migrating its API; confirm v3 still answers). BTTV `/3/cached/emotes/global`, `/3/cached/users/twitch/{id}`. FFZ `/v1/set/global`, `/v1/room/id/{id}`. |
| VOD chat | `VodChat.kt:117-118` | Persisted hash `b70a3591…` for `VideoCommentsByOffsetOrCursor`; response shape `data.video.comments.edges[].node`. |
| EventSub | `RaidWatcher.kt` | Welcome payload is `payload.session.id`; `channel.raid` with `from_broadcaster_user_id` subscribes with the viewer's user token and no extra scope; subscribe within 10 s of welcome (the code does it immediately). |
| Ad markers | `AdBreak.kt:35-83` | Segment titles `Amazon|…`, `Adform`, `DCM`; DATERANGE `CLASS="twitch-stitched-ad"` or `X-TV-TWITCH-AD-*`. Fixtures were written from Xtra's rules, not captured traffic. |
| Proxies | `Proxy.kt:199-221` | Which of Xtra's default hosts still answer `/ping`. Expect several to be dead. |
| TV home row | `Background.kt:139-220` | Whether the launcher shows the preview channel at all. The manifest declares `READ_EPG_DATA`/`WRITE_EPG_DATA`; app-owned preview channels should not need them, so if the row works, try removing both permissions in a follow-up. |
| Updater | `Updates.kt` | The `UPDATE_REPO` repository must be public; the first real install over an existing build must be signed with the stable key. |

---

## 8. Things not to do

- Do not add Android classes to `twitch-core`. If you need one, you are putting the code in the wrong module.
- Do not remove the `catch (e: CancellationException) { throw e }` lines to "simplify".
- Do not turn ad handling on by default, add proxies to the default list, or make the proxy list load without the
  viewer choosing a proxy mode.
- Do not replace the hand-rolled back stack (`Navigation.kt`) with Compose Navigation. It is 30 lines and works.
- Do not change the DataStore key names in `AppSettings.kt`; existing installs would lose their settings and login.
- Do not touch `release.yml`, the keystore script, or `applicationId`.
- Do not enable R8 without a device to test on (see end of section 6).
- Do not "fix" the StateFlow-defaults pattern by removing `c.settings.settings.first()` reads; they are deliberate.

---

## 9. Open questions for the owner

Answers change what is worth doing next.

1. **Is there a TV or Android TV emulator to test on?** Without one, P1-3, R8, PiP, the home row and the updater
   stay unverified, and feature work should stay in the core where it can be tested.
2. **Has the app run against real Twitch yet?** If so, which rows of section 7 are confirmed? If not, the first
   session with a device should go through that table before any feature work.
3. **Scope: parity with S0undTV, or polish what exists?** Section 6 is ordered for polish. If parity is the goal,
   say which S0undTV features matter most and this list gets reordered.
4. **Are Clips in scope?** They need another undocumented endpoint and a day or more of work.
5. **Localization?** All UI strings are Kotlin literals; `strings.xml` holds only the app name. Externalizing them
   is a mechanical, large diff that should be done once and early if other languages are ever wanted.
6. **License.** The README says none has been chosen. Contributors, including automated ones, should know the terms.
7. **Is `Baker-Jacob-Mayo/S0undPlus` public?** The updater calls the GitHub API unauthenticated and needs it to be.

---

## 10. Implementation status (2026-10-11)

Added by the session that worked through this document, so the next person doesn't redo or second-guess it. Each
item is its own commit, with the item ID in the message. Every claim in the advisory was checked against the code
first; where it was wrong or needed adjusting, that is said here.

| ID | Status | Notes |
|---|---|---|
| P0-1 | Done | `sized()` fills `%{width}`/`%{height}` first. Tests: `ModelsTest`, `HelixApiTest`. |
| P0-2 | Done | `RecentMessagesApi` swallows any failure; `CrashGuard` on screen and app scopes (logs only the class and first frame, never messages); login shows a message. |
| P1-1 | Done | Menu gets `qualityLabel`; focus falls back to Auto when nothing matches. |
| P1-2 | Done | `PeekPanel` requests focus when its list becomes non-empty. |
| P1-3 | Done | Both the player and the live-preview player use `DefaultMediaSourceFactory(OkHttpDataSource.Factory(container.http))`. |
| P1-4 | Done | The three-argument `resume` overload, as suggested (the one-argument one is deprecated in coroutines 1.11). |
| P1-5 | Done | Login says "Twitch rejected the request (HTTP n)"; playback says "Twitch refused the playback request"; a 403 on a VOD becomes `VodRestrictedException`. The exact `error_code` was not checked, so any 403 on a VOD playlist is treated as restricted. |
| P2-1 | Done | `TtlCache` (single-flight, LRU, one hour) behind `EmoteCache` and the Helix badge lookup. A set is only cached when every provider answered; a 404 for a channel set counts as an answer. |
| P2-2 | Partly | 429 is retried. The `session_reconnect` ordering (open the new socket before closing the old) was left alone: rare, invasive, unverifiable here. |
| P2-3 | Done | |
| P2-4 | Done | `SavedTextField` saves 500 ms after typing stops, on blur and on dispose. Settings updates now run on the app scope so a pending save survives leaving the screen. |
| P2-5 | Done | |
| P2-6 | Done | `PlayerController.displayName` upgrades to Twitch's capitalised name; the user lookup is shared with the raid watcher. |
| P2-7 | Done | |
| P2-8 | Done | The README no longer quotes an exact count. |
| P2-9 | **Declined** | A commit-count version code is `1` on every CI build: both workflows use the default shallow checkout (`fetch-depth: 1`), and `release.yml` must not be changed without the owner. The configuration-cache worry only affects local builds. |
| P2-10 | Done, with a correction | The task is `:app:testDebugUnitTest`; `testReleaseUnitTest` does not exist under AGP 9 (CI failed on it). `UpdateUi.statusLabel` is not covered because it shares a file with Compose code. `Format.ago` now pluralises correctly. |
| P2-11 | Done | |
| P3-1 | Done | Viewer count, uptime, title and category, fetched while the controls are shown. |
| P3-2 | Done | Past broadcasts / Highlights / Uploads. |
| P3-3 | Done | `AdBreakDetector.plannedDurationMs` and `AdBreakPlanner.remainingMs`; shown on the cover. |
| P3-4, P3-5 | Done | `ChatMentions`, bits chip, reply line. |
| P3-6, P3-7 | Not done | Both need live checks (which static emote variants 7TV/BTTV serve; what 7TV's zero-width flag looks like in the current API). Twitch's own emotes are already static. |
| P3-8 | Done | |
| P3-9 | Not done | Clips: the advisory itself says to ask the owner first. |
| P3-10, P3-11 | Done | Latency text and Jump to live; Fit/Fill (persisted). |
| P3-12 | See git log | The `PlayerScreen.kt` split is its own commit. |

Beyond this document, an adversarial review of the ad-handling code produced further fixes (planner follows the
live lag; fetch timeouts and capped reads; overflow-safe dates; swap-key duplicates; failure cool-downs instead of a
one-way cursor; swap keeps a pause; lifecycle-gated polling). They are in the commit messages.
