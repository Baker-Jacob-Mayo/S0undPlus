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
| **Browse** | **Following** (favorites → live now → offline), **Live**, **Language** row, **Categories**, **History**, **Search**. Long-press any card to open the channel page. |
| **Remembers your place** | Going back from a stream returns to the same tab, scroll position and focused card. |
| **Favorites** | Star channels (player controls or channel page); they're pinned at the top of Following, live or not. |
| **Channel pages & VODs** | Profile, live status, past broadcasts with **resume where you left off**, plus a **History** tab of what you watched. |
| **Player** | Media3 HLS, quality picker with a remembered preference, audio-only mode, live-edge recovery, VOD seeking, touch/swipe gestures (volume, chat, seek). |
| **Auto-refresh** | If a stream ends or stalls, the app checks the streamer is still online, then re-requests a fresh playback token and reconnects (normal or aggressive mode, configurable retries and interval). |
| **Peek view** | Browse other live channels from inside the player without leaving the stream (thumbnails only — no second video decoder). |
| **Raid redirect** | When the channel you're watching raids another one, you're taken along after a 10 s countdown (Back to stay). Uses Twitch's official EventSub. |
| **Picture-in-picture** | Press Home while watching and the stream shrinks into a PiP window (Android 8+, device permitting). |
| **Chat** | Overlay or beside the video; **Twitch, 7TV, BetterTTV and FrankerFaceZ emotes**, badges, recent history, timestamps, emote/text sizes, deleted-message styling. **VOD chat replay** in sync with the video. |
| **Chat filters** | Hide known bots, `!commands`, blocked words and blocked users. |
| **Chat on your phone** | QR code that opens the channel's chat on your phone, since TV chat is read-only. |
| **Live previews** | Optional (off by default): a focused card plays a muted, lowest-quality preview using one shared player. |
| **Go-live notifications** | Optional: checks about every 15 minutes and notifies when a followed channel goes live. Optional Android TV home-screen "Live now" row. |
| **Themes & extras** | 5 themes, optional world clock, keep-screen-on setting, remote colour-button / channel +/- shortcuts, deep links (`twitch.tv/<login>`, `s0undplus://live/<login>`). |
| **Updates** | In-app updater that checks GitHub Releases and verifies the APK is signed with the same key before installing. |

### What's different from S0undTV

- **Follow / unfollow isn't possible.** Twitch removed the follow API for third-party apps in July 2021 and offers no
  replacement. S0undPlus gives you local **favorites** and a **"Follow on Twitch" QR code** on each channel page instead.
- **Chat is read-only** (anonymous). Use the "Chat on phone" QR code to talk.
- **"Recommendation channels"** are implemented as an Android TV home-screen row. Launchers that don't support app
  channels (Google TV's newer home screen may not) will never show it.
- **"Channel profiles"** are the channel pages (profile, VODs, favorite toggle); per-channel settings aren't implemented.
- **Standalone vs Play versions** don't apply: this is distributed as a GitHub-released APK.

## Remote controls

| Where | Key | Action |
|---|---|---|
| Player (overlay hidden) | OK / Up | Show controls |
| Player, live | Down | Peek at other live channels |
| Player, live | Right / Left | Show / hide chat |
| Player, VOD | Left / Right | Seek −10 s / +10 s |
| Player | Play/Pause, Rewind, Fast-forward | As labelled (rewind/FF seek ±30 s on VODs) |
| Player | Red / Green / Yellow / Blue | Chat / quality / audio-only / peek |
| Player | Channel +/− | Next / previous followed live channel |
| Player | Info or Menu | Show controls |
| Player | Back | Close menu → hide controls → leave the stream |
| Home | long-press OK on a card | Open the channel page (or remove from History) |
| Anywhere | Back | Previous screen |

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

…or put the APK somewhere your TV can fetch it and install it with the *Downloader* app.

## Architecture

```
twitch-core/   Plain Kotlin/JVM library (included build) — all Twitch protocol logic, fully unit tested
  api/         HelixApi: streams, follows, categories, search, users, videos, badges
  auth/        Device-code OAuth + AuthSession (serialized refresh, retry-on-401)
  playback/    PlaybackApi (token → usher → master playlist), MasterPlaylist parser, QualityPreference
  chat/        IRC parser + ChatProtocol, ChatClient (anonymous WebSocket + backoff),
               7TV/BTTV/FFZ emotes, ChatFormatter, ChatFilter, recent-messages history,
               VOD chat API + VodChatReplay (seek-aware buffering)
  eventsub/    RaidWatcher over the official EventSub WebSocket
  update/      GitHub-release update checker + version comparison
  library/     bounded favorites / watch-history operations
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
- Playback is anonymous: **no Turbo or subscriber perks**, and ads that Twitch stitches into the stream play as they
  do on the website. There is no ad-blocking.
- Using unofficial endpoints may be against Twitch's Terms of Service. This project is for personal use; use it at
  your own risk.

Chat uses Twitch's documented anonymous IRC gateway. Chat history comes from the community-run
[recent-messages](https://recent-messages.robotty.de) service; emotes from the public 7TV/BTTV/FFZ APIs. All three
are best-effort and optional.

## Project status

What has and hasn't been verified:

- ✅ `twitch-core`: **85 unit tests** pass locally and on GitHub Actions (JDK 17) — request shapes, pagination, token
  refresh, device-code polling, playlist/IRC/emote parsing, a real WebSocket round-trip with reconnect, an EventSub
  WebSocket flow (welcome → subscribe → raid), VOD chat parsing and replay across seeks, auto-refresh rules, version
  comparison, favorites/history bounds, deep-link parsing.
- ✅ The Android app **compiles and packages** on GitHub Actions (AGP 9.4, Gradle 9.6, Kotlin 2.4, `compileSdk` 37);
  Android lint reports **0 errors**; CI inspects the built APK with `aapt2` and `apksigner` (Leanback launcher, banner,
  permissions, `uses-feature`, signer).
- ⚠️ **Never exercised on a real device or against live Twitch.** The environment this was built in could not reach
  Twitch, 7TV/BTTV/FFZ or the Android SDK hosts. Twitch request/response shapes come from Twitch's docs and from reading
  open-source clients (Streamlink, Xtra, chat-downloader). Expect first-contact fixes, most likely in `PlaybackApi`, the
  VOD-chat response parser, the emote parsers, and EventSub (the exact welcome payload and auth requirements for
  `channel.raid` were not confirmed against Twitch's own docs). D-pad focus behaviour, playback performance,
  notifications, the TV home row, PiP and the installer flow on real TV hardware are untested.
- ⚠️ The **release workflow has never run** (it needs your signing secrets), and the **in-app updater has never installed
  anything**.
- ⚠️ VOD chat replay and playback use **undocumented Twitch endpoints** that can change without notice; both degrade
  gracefully ("Chat replay isn't available") and never block the video.

## License

No license has been chosen yet — all rights reserved by the repository owner until one is added.
