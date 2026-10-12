# S0undPlus

A from-scratch, modern Twitch viewer for **Google TV / Android TV** — inspired by
[S0undTV](https://github.com/S0und/S0undTV), rebuilt on Kotlin, Jetpack Compose for TV and Media3.

> S0undPlus is an unofficial, independent project. It is not affiliated with or endorsed by Twitch Interactive, Inc.
> or the S0undTV project, and contains none of their code.

## Quick start (3 steps — the CI APK needs your Twitch client ID)

The APK that GitHub Actions builds only works if a Twitch **client ID** was provided at build time. Without it, the app
opens on a "needs a Twitch client ID" screen. To get a working build:

1. Register a free **Public** application at <https://dev.twitch.tv/console> (details in [Setup](#setup)).
2. In this repository: **Settings → Secrets and variables → Actions → New repository secret**, name it
   `TWITCH_CLIENT_ID`, paste the client ID.
3. Re-run the **Build** workflow, download the **S0undPlus-release-apk** artifact, and [sideload it](#build--install).

> **Recommended:** also set up [signing](#signing-and-releases) once. Without your own signing key every CI build is signed
> differently, so each new APK has to be installed *after uninstalling the old one*, and the in-app updater can't work.

## Features

Everything in S0undTV's feature list is here except where noted in [What's different](#whats-different-from-s0undtv).

| | |
|---|---|
| **Sign in with your phone** | Twitch device-code login with a QR code — no typing on the TV. Read-only scope (`user:read:follows`). |
| **Browse** | A streaming-service layout: a navigation rail down the left (Search, Following, Live, Categories, History, Settings) that opens over the page when focused. **Following** has a hero describing the focused channel over rows of favorites, live now (followed channels, then followed categories), continue watching and an offline wall; **Live**, **Categories** and **History** are grids; **Search** shows channel and category rows. Long-press any stream card to open the channel page. |
| **Sort and filter** | **Live** and each category's page sort by most viewers, most recently started or fewest viewers, and narrow to one or more broadcast languages and to the tags streamers set (offered from what's live). **Categories** narrows to game types (Shooter, RPG, MOBA, IRL…). The choices are kept until you change them; Live starts in your language. A refresh button sits on every page (top right on Following). |
| **Remembers your place** | Going back from a stream or a channel page returns to the same page, scroll position and focused card. |
| **Follow in the app** | Follow channels and categories from their pages, kept in the app alongside your Twitch account's follows: they're in Following, Channel +/−, peek and go-live notifications like the rest. Followed categories sit after the live channels in Live now. Every channel card shows where it's followed with a small badge at the lower right: Twitch's mark (your Twitch account), a plus (this app), or the two split across the corner (both). |
| **Favorites** | Star channels (player controls or channel page); they're pinned at the top of Following, live or not. |
| **Channel pages & VODs** | A show-page layout: the channel's picture, live status and buttons, then shelves of past broadcasts, highlights and uploads, with **resume where you left off** (progress bars), plus **History** and a **Continue watching** row. |
| **Player** | Media3 HLS on the app's shared OkHttp client, quality picker (best first, source marked) with a remembered preference, audio-only mode, live-edge recovery, **viewer count and uptime**, **seconds behind live with a Jump to live button**, Fit/Fill for 4:3 streams, touch/swipe gestures (volume, chat, seek). YouTube-style controls: a seek bar you can focus and scrub on past broadcasts, with **preview pictures** of the moment you're moving to, one row of buttons (the focused one shows its name) and an Options panel for the rest. |
| **Frame-rate matching** | On by default: the TV switches to a refresh rate that fits the stream (50 Hz for a 50 fps stream, which judders on 60 Hz), once per stream, and back when you leave. Works even when the TV's own "Match content frame rate" is set to Seamless only, which on most boxes never switches. |
| **Auto-refresh** | If a stream ends or stalls, the app checks the streamer is still online, then re-requests a fresh playback token and reconnects (normal or aggressive mode, configurable retries and interval). |
| **Peek view** | Browse other live channels from inside the player without leaving the stream (thumbnails only — no second video decoder). Peek and Channel +/− use your followed live channels; Settings → Playback can add popular ones after them. |
| **Raid redirect** | When the channel you're watching raids another one, you're taken along after a 10 s countdown (Back to stay). Uses Twitch's official EventSub. |
| **Picture-in-picture** | Press Home while watching and the stream shrinks into a PiP window (Android 8+, device permitting). |
| **Chat** | Overlay or beside the video; **Twitch, 7TV, BetterTTV and FrankerFaceZ emotes**, badges, recent history, timestamps, emote/text sizes, deleted-message styling, highlighted `@you` mentions, cheer amounts and reply context. **VOD chat replay** in sync with the video. |
| **Chat filters** | Hide known bots, `!commands`, blocked words and blocked users. |
| **Chat on your phone** | QR code that opens the channel's chat on your phone, since TV chat is read-only. |
| **Live previews** | Optional (off by default): a focused card plays a muted, low-quality preview using one shared player. |
| **Go-live notifications** | Optional: checks about every 15 minutes and notifies when a followed channel goes live. Optional Android TV home-screen "Live now" row. |
| **Themes & extras** | 5 themes, optional world clock, keep-screen-on setting, remote colour-button / channel +/- shortcuts, deep links (`twitch.tv/<login>`, `s0undplus://live/<login>`). |
| **Ad handling** | **Off by default.** Settings → Ads: hide ads, or get an ad-free copy through proxy URLs, an HTTP proxy, or a different token identity — the same approaches Xtra uses. Read [Ads](#ads) before turning it on. |
| **Updates** | In-app updater that checks GitHub Releases and verifies the APK is signed with the same key before installing. |

### What's different from S0undTV

- **Following on your Twitch account isn't possible.** Twitch removed the follow API for third-party apps in July 2021
  and offers no replacement. S0undPlus follows channels and categories **in the app** instead, and each channel page has a
  **"Follow on Twitch" QR code** that opens the channel on your phone. Channels followed on Twitch are unfollowed there.
- **Chat is read-only** (anonymous). Use the "Chat on phone" QR code to talk.
- **"Recommendation channels"** are implemented as an Android TV home-screen row. Launchers that don't support app
  channels (Google TV's newer home screen may not) will never show it.
- **"Channel profiles"** are the channel pages (profile, VODs, favorite toggle); per-channel settings aren't implemented.
- **Standalone vs Play versions** don't apply: this is distributed as a GitHub-released APK.

## Remote controls

| Where | Key | Action |
|---|---|---|
| Player (overlay hidden) | OK / Up / Down | Show controls (Play/Pause focused) |
| Player, controls | Down | Hide the controls |
| Player, controls (video) | Up, then Left / Right, then OK | Focus the seek bar, scrub with preview pictures (faster while held), jump there; Back cancels, Up again hides the controls |
| Player, controls (live) | Up | Hide the controls |
| Player, live | Right / Left | Show / hide chat |
| Player, VOD | Left / Right | Seek −10 s / +10 s |
| Player | Play/Pause, Rewind, Fast-forward | As labelled (rewind/FF seek ±30 s on VODs) |
| Player | Red / Green / Yellow / Blue | Chat / quality / audio-only / peek |
| Player | Channel +/− | Next / previous live channel (followed only unless Settings adds popular ones) |
| Player | Info or Menu | Show controls |
| Player | Back | Close menu → hide controls → leave the stream (one press each) |
| Home | Left (from the first card in a row) | Open the navigation rail |
| Home, rail | Up / Down, then OK or Right | Choose a page (resting on an item for a moment also opens it) |
| Home | Back | Back to the navigation (the page returns to its top); Back on the navigation leaves the app |
| Home | long-press OK on a card | Open the channel page (or remove from History) |
| Anywhere else | Back | Previous screen |

The player's controls are laid out like YouTube on TV: a full-width seek bar, and under it one row of round buttons.
Left to right: play/pause (with back / forward 10 s on videos, and Go live when you're behind), chat, other channels;
then favorite, channel page and Options. Options holds quality, audio only, picture fit/fill, picture-in-picture and
chat on your phone. Blue (or the other-channels button) opens the peek view.

## Setup

S0undPlus uses Twitch's official Helix API for everything except playback, so you need a (free) Twitch application:

1. Go to <https://dev.twitch.tv/console> → **Register Your Application**.
2. **OAuth Redirect URL**: `http://localhost` (required by the form; the TV device flow never uses it).
3. **Client Type**: **Public** (the device-code flow doesn't use a client secret).
4. Copy the **Client ID**.

Give the client ID to the build in any one of these ways:

```properties
# local.properties (not committed)
twitchClientId=YOUR_CLIENT_ID
```

or `./gradlew :app:assembleRelease -PtwitchClientId=YOUR_CLIENT_ID`, or the `TWITCH_CLIENT_ID` environment variable.
For CI, add it as a repository secret named `TWITCH_CLIENT_ID`. Without it the app builds and launches, but shows a
"needs a Twitch client ID" screen.

## Signing and releases

Android only installs an update over an existing app if both APKs are signed with the **same key**. GitHub's runners
create a throwaway debug key on every run, so until you provide your own key **each CI APK has a different signature
and must be installed after uninstalling the previous one** (you'd sign in again). To fix that once:

1. Run `scripts/make-keystore.sh` (needs a JDK's `keytool`). It creates `s0undplus-release.jks` — **keep it and its
   password safe and private; never commit it** (`*.jks` is git-ignored) — and prints the secrets to add.
2. Add these repository secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
   `SIGNING_KEY_PASSWORD` (and `TWITCH_CLIENT_ID`).
3. Uninstall any older S0undPlus from the TV **once**, then install a build signed with your key. From then on builds
   update in place.
4. To publish a release the in-app updater can find: `git tag v1.1.0 && git push origin v1.1.0`. The **Release**
   workflow builds, refuses to publish unless the secrets exist or if the APK is debug-signed, and attaches
   `S0undPlus-v1.1.0.apk` to a GitHub Release.

The updater only looks at the repository named in `UPDATE_REPO` (default: this one) and calls GitHub's API without
authentication, so **that repository (or a public releases repo you point `UPDATE_REPO` at) must be public**. Version
codes come from the build time, so a release always counts as newer than any earlier build. It verifies the download's signing
certificate matches the installed app before launching Android's installer, and explains the mismatch instead of failing
with a generic "App not installed".

## Build & install

Requirements: JDK 17+, the Android SDK (API 37 platform). Gradle is provided by the wrapper.

```bash
./gradlew :app:assembleRelease        # sideloadable APK (debug-signed) → app/build/outputs/apk/release/
./gradlew -p twitch-core test          # Twitch protocol library tests — no Android SDK needed
```

Every push also builds in GitHub Actions; download the **S0undPlus-release-apk** artifact from the run.

Sideload with adb (enable Developer options → USB/Network debugging on the TV):

```bash
adb connect <tv-ip>:5555
adb install -r S0undPlus-release.apk
```

Then, once, after installing or updating (it makes the app noticeably smoother straight away):

```bash
adb shell cmd package compile -m speed-profile -f com.s0undplus.tv
```

The APK carries a baseline profile, but for a sideloaded app Android only compiles it during its background optimisation, which on a TV box can be overnight; until then the app runs partly interpreted. Measured on an onn 4K Pro (Google TV), D-pad navigation on Home went from 40% janky frames to about 6% with this step.

…or put the APK somewhere your TV can fetch it and install it with the *Downloader* app.

## Architecture

```
twitch-core/   Plain Kotlin/JVM library (included build) — all Twitch protocol logic, fully unit tested
  api/         HelixApi: streams, follows, categories, search, users, videos, badges; DirectoryApi (the sorted and
               filtered browse lists), Browse (directory first, Helix when it refuses)
  auth/        Device-code OAuth + AuthSession (serialized refresh, retry-on-401)
  playback/    PlaybackApi (token → usher → master playlist), MasterPlaylist parser, QualityPreference,
               AdBreak (ad-marker detector, break-timing planner, media-playlist parser), Proxy (routes, host-routed
               HTTP proxy, custom-URL templating, health check, Xtra's default lists)
  chat/        IRC parser + ChatProtocol, ChatClient (anonymous WebSocket + backoff),
               7TV/BTTV/FFZ emotes, ChatFormatter, ChatFilter, recent-messages history,
               VOD chat API + VodChatReplay (seek-aware buffering)
  eventsub/    RaidWatcher over the official EventSub WebSocket
  update/      GitHub-release update checker + version comparison
  library/     bounded favorites, app follows and watch-history operations; the browse filters kept between launches
  (root)       AutoRefresh policy + stall detector, DeepLinkParser, languages
app/           Android TV app — Compose for TV UI, Media3 player, DataStore persistence
```

The split is deliberate: everything that can break when Twitch changes something lives in `twitch-core`, where it can
be tested with `MockWebServer` and fixtures on any machine. The app is a thin UI over it.

Highlights:

- **Chat performance**: messages are buffered and published every 250 ms with a 120-message cap, because a busy chat
  would otherwise recompose the list dozens of times a second on a TV-class CPU.
- **Emote indices** in Twitch's `emotes` tag count Unicode code points, not UTF-16 units — the formatter handles emoji
  correctly (and skips bad ranges instead of crashing chat).
- **Back stack** is a 30-line class in a ViewModel; Compose Navigation is in maintenance mode and the app has four
  destinations.
- **Pagination** de-duplicates by key, because Twitch's cursors can overlap and duplicate keys crash `LazyGrid`.

## Playback and Twitch's terms

Helix (the official API) has no endpoint that returns a stream URL. Like the Twitch website, and like every
third-party player (Streamlink, Xtra, …), S0undPlus requests an anonymous playback token from Twitch's **undocumented**
GraphQL endpoint and then fetches the HLS playlist from `usher.ttvnw.net`. Please be aware:

- It is **unofficial and can break without notice**. All of it is in
  [`PlaybackApi.kt`](twitch-core/src/main/kotlin/com/s0undplus/twitch/playback/PlaybackApi.kt) so a fix is one file.
- It uses the public web-player client ID (`TwitchConfig.webClientId`), not yours. Your own client ID is used only for
  Helix and login.
- Playback is anonymous: **no Turbo or subscriber perks**. By default, ads that Twitch stitches into the stream play as
  they do on the website; see [Ads](#ads) for the optional, opt-in handling.
- Using unofficial endpoints may be against Twitch's Terms of Service. This project is for personal use; use it at
  your own risk.

## Ads

**How Xtra is "ad-free on everything".** It doesn't strip ads out of the stream. Twitch stitches ads into the video
itself, so there is nothing to strip. What Xtra does instead (read from its source, `MainViewModel`, `ExoPlayerService`
and `PlayerRepository`):

1. **Custom proxy URLs — on by default in Xtra.** Every live stream is requested from a list of third-party proxy
   services (`eu.luminous.dev/live/$channel`, `lb-eu.cdn-perfprod.com/live/$channel`, …) instead of from Twitch. Those
   services fetch the stream from somewhere ads aren't served and hand back a playlist without them. If one fails
   (HTTP error or timeout) Xtra moves to the next, and finally to Twitch directly.
2. **HTTP proxy**, 3. **video swap** (ask Twitch for the stream with a different player identity), and 4. **hide ads**
   (mute and black out): all of which watch the playlist for Twitch's ad markers and only step in while an ad plays.

So "ad-free everywhere" means "a proxy service somebody else runs happens to be working today".

**What S0undPlus does.** Settings → **Ads** → **Ad handling**:

| Mode | What happens |
|---|---|
| **Off** *(default)* | Exactly the app as before. Nothing extra is requested; nothing goes to anyone but Twitch. |
| **Hide ads** | Reads Twitch's ad markers, mutes and blacks out the picture for the length of the ad. You still wait it out. Nothing goes to anyone but Twitch. |
| **Ad-free copy via proxy URLs** | Plays the whole stream from the proxy list (Xtra's approach 1). Each load works down the list for up to about 20 s; a proxy that fails is skipped for five minutes and then tried again; if none can be used it plays from Twitch, hides the ads and says so on screen. No Twitch token is created or sent to a proxy. |
| **Ad-free copy via HTTP proxy** | While an ad plays, the stream's playlists are fetched through your HTTP proxy (token / master / media-playlist requests individually switchable; segments always go direct). If the copy fails, or turns out to carry an ad itself, the ad is hidden instead. |
| **Ad-free copy via video swap** | While an ad plays, re-requests the stream with a different token identity (`platform` / `playerType`) and shows that, checking that copy for ads too and trying the next identity within the same break. |

Details worth knowing:

- **The default lists are copied from Xtra** (12 proxy URLs, 9 enabled as in Xtra; one HTTP proxy,
  `firefox.api.cdn-perfprod.com:2023`; three video-swap identities) as they are in its source today. They are run by
  third parties, not by this project, and can vanish or change any time — several were already shown offline or
  "loading" in a screenshot of Xtra's own list. **Check which are online** in the settings pings each one's `/ping`
  endpoint the way Xtra does. Add, remove or reset entries there; first entry is tried first.
- **Privacy.** A proxy service sees your IP address and which channel you watch for as long as you use it. This is the
  real cost of proxy modes, and why they are opt-in.
- **Terms and the streamer.** Getting around ads may go against Twitch's Terms of Service, and ad revenue is a large part
  of how streamers get paid. **Twitch Turbo or subscribing to a channel removes ads without any of this.**
- The "Playback token player type" setting changes which player identity the token request claims. Whether that changes
  what Twitch serves is up to Twitch and changes over time.
- Ad detection uses Twitch's own markers (segment titles `Amazon`/`Adform`/`DCM`, `stitched-ad` date ranges). If Twitch
  changes them detection silently stops and the stream just plays normally.
- Proxy URLs must be `https` and contain `$channel` (an address that would resolve to the same place for every channel is
  rejected). A proxy that doesn't answer within 5 seconds is skipped.
- While an ad is hidden the cover shows how much of it is left, when the playlist says how long the break is. The picture
  stays covered and muted until an ad-free copy has actually drawn a frame, and a swap keeps a pause a pause.
- Ad handling only runs while the screen is on: no polling, token requests or proxy traffic while the app is in the
  background.
- **From Xtra's source, read rather than guessed:** a custom proxy request carries no Twitch token and no special headers
  (Xtra sends none either); the HTTP proxy only carries the media *playlists*, never video segments; a video-swap entry
  changes just `platform` and `playerType`. Xtra pairs the Android identity with Client-ID `ue6666qo983tsx6so1t0vnawi233wa`
  (its effective default on a fresh install), which is what S0undPlus now sends for `platform=android`, with a fresh
  device id per swap request as Xtra does. **Whether Twitch serves different ads for any of that is not something source
  code can show.**

**What has and hasn't been checked:** the ad logic is unit-tested (parsers, detector, break timing, routing, proxy auth,
failover, timeouts), and the real player is run through ad breaks on an Android TV emulator in CI — hiding the ad, and
swapping to an ad-free copy — against a fake channel (see [Emulator tests](#emulator-tests)). But every playlist in those
tests is **synthetic, written from Xtra's rules**, not captured from a real ad break, and **nothing has been tried
against real Twitch ads, a real proxy service, or on real TV hardware.**

Chat uses Twitch's documented anonymous IRC gateway. Chat history comes from the community-run
[recent-messages](https://recent-messages.robotty.de) service; emotes from the public 7TV/BTTV/FFZ APIs. All three
are best-effort and optional.

## Emulator tests

`app/src/androidTest` holds instrumented tests that run the real app on an **Android TV emulator**. The *Emulator tests*
workflow boots one on a GitHub runner (hardware-accelerated KVM) on every push, runs them, and prints the results,
failures, the app's own logs and **screenshots** into the job log (so you can read a run without downloading anything; the
screenshots are base64 JPEGs between `SCREENSHOT-BEGIN` / `SCREENSHOT-END` markers).

- **Nothing real is contacted.** A `MockWebServer` on the emulator's loopback plays every service (Helix, OAuth, GQL, usher,
  the HLS playlists and segments); a test Application hands the app a container pointed at it, and any other host is
  refused and fails the test. The video segments are tiny real MPEG-TS clips made with ffmpeg
  (`app/src/androidTest/assets/hls`), so ExoPlayer genuinely decodes them.
- **Run them yourself** with an emulator or TV connected: `./gradlew :app:connectedDebugAndroidTest -PtwitchClientId=test`
  (the client ID can be any non-empty value; the tests never send it anywhere). Set `SUITE_REPEATS=3` for the CI script to
  repeat the whole suite and tell a flaky test from a broken one.
- **Debug builds only** allow cleartext traffic, and only to `localhost` / `127.0.0.1`, for the fake server; release builds
  are unchanged.
- **What they don't cover:** real Twitch, real ads, real proxy services, real TV decoders and GPUs (the emulator decodes in
  software), the Google TV launcher, and anything needing hardware (HDMI-CEC, a real remote's quirks).

## Project status

What has and hasn't been verified:

- ✅ `twitch-core`: **over 200 unit tests** pass locally and on GitHub Actions (JDK 17) — request shapes, pagination, token
  refresh, device-code polling, playlist/IRC/emote parsing, a real WebSocket round-trip with reconnect, an EventSub
  WebSocket flow (welcome → subscribe → raid), VOD chat parsing and replay across seeks, auto-refresh rules, version
  comparison, favorites/history bounds, deep-link parsing, and the ad-handling logic described under [Ads](#ads).
- ✅ The Android app **compiles and packages** on GitHub Actions (AGP 9.4, Gradle 9.6, Kotlin 2.4, `compileSdk` 37);
  Android lint reports **0 errors**; CI inspects the built APK with `aapt2` and `apksigner` (Leanback launcher, banner,
  permissions, `uses-feature`, signer).
- ✅ **20 instrumented tests run on an Android TV emulator** (API 34, x86, in GitHub Actions) against a fake Twitch served from
  the emulator itself: signing in with the device code, the Home tabs and D-pad focus, Live's language list (keeping the
  D-pad inside it, storing what was ticked), Settings (saving, text fields), the on-screen keyboard, and the **real player (ExoPlayer, decoding
  real video segments) through hide-the-ad and swap-to-an-ad-free-copy breaks**. They have already found and fixed three
  real bugs that compilation and the unit tests could not: network reads on the main thread (Android refuses them, so
  lists and playback would fail intermittently), a keyboard that popped up whenever the D-pad passed a text field, and
  ad detection that threw on every poll because ExoPlayer was read from the wrong thread. See
  [Emulator tests](#emulator-tests).
- ⚠️ **Never exercised on real TV hardware or against live Twitch.** The environment this was built in could not reach
  Twitch or 7TV/BTTV/FFZ, so the emulator tests talk to a fake. Twitch request/response shapes come from Twitch's docs and
  from reading open-source clients (Streamlink, Xtra, chat-downloader). Expect first-contact fixes, most likely in
  `PlaybackApi`, the VOD-chat response parser, the emote parsers, and EventSub (the exact welcome payload and auth
  requirements for `channel.raid` were not confirmed against Twitch's own docs). Playback performance on a real TV's
  decoders, notifications, the TV home row, PiP, the Google TV launcher, and the installer flow are untested.
- ⚠️ **Ad handling has never met a real ad.** The emulator tests prove the app reacts correctly to the ad markers Xtra's
  source describes, using synthetic playlists; whether Twitch's actual breaks still look like that, and whether any of
  the default proxies still work, is unknown.
- ⚠️ The **release workflow has never run** (it needs your signing secrets), and the **in-app updater has never installed
  anything**.
- ⚠️ VOD chat replay and playback use **undocumented Twitch endpoints** that can change without notice; both degrade
  gracefully ("Chat replay isn't available") and never block the video.

## License

No license has been chosen yet — all rights reserved by the repository owner until one is added.
