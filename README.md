# S0undPlus

A from-scratch, modern Twitch viewer for **Google TV / Android TV** — inspired by
[S0undTV](https://github.com/S0und/S0undTV), rebuilt on Kotlin, Jetpack Compose for TV and Media3.

> S0undPlus is an unofficial, independent project. It is not affiliated with or endorsed by Twitch Interactive, Inc.
> or the S0undTV project, and contains none of their code.

## Features

| | |
|---|---|
| **Sign in with your phone** | Twitch device-code login with a QR code — no typing on the TV. Read-only scope (`user:read:follows`). |
| **Browse** | Followed channels that are live, top live streams, categories, search (channels + categories). |
| **Remembers your place** | Going back from a stream returns to the same tab, scroll position and focused card. |
| **Channel pages** | Profile, live status, past broadcasts (VODs) with **resume where you left off**. |
| **Player** | Media3 / ExoPlayer HLS, quality picker + remembered preference, audio-only mode, live-edge recovery, VOD seeking. |
| **Picture-in-picture** | Press Home while watching and the stream shrinks into a PiP window (Android 8+, device permitting). |
| **Chat** | Live chat as an overlay or beside the video, with **Twitch, 7TV, BetterTTV and FrankerFaceZ emotes**, badges, and recent history from before you tuned in. |
| **Chat filters** | Hide known bots, `!commands`, blocked words and blocked users. |
| **Remote-friendly** | Everything is D-pad navigable; media keys, Back, and shortcuts work as you'd expect (see below). |

### Not (yet) in S0undPlus

S0undTV has more than this first version. Not implemented: VOD chat replay, "peek view" browsing while watching,
live-stream previews, notifications when a followed streamer goes live, themes, world clock, per-channel profiles,
language rows, and following/unfollowing from the app. Chat is read-only. PRs and issues welcome.

## Remote controls

| Where | Key | Action |
|---|---|---|
| Player (overlay hidden) | OK / Up / Down | Show controls |
| Player, live | Right / Left | Show / hide chat |
| Player, VOD | Left / Right | Seek −10 s / +10 s |
| Player | Play/Pause, Rewind, Fast-forward | As labelled (rewind/FF seek ±30 s on VODs) |
| Player | Back | Close menu → hide controls → leave the stream |
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
               7TV/BTTV/FFZ emotes, ChatFormatter, ChatFilter, recent-messages history
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

What has and hasn't been verified (as of the first release):

- ✅ `twitch-core`: 54 unit tests pass locally and on GitHub Actions (JDK 17) — request shapes, pagination, token
  refresh, device-code polling, playlist/IRC/emote parsing, a real WebSocket round-trip with reconnect.
- ✅ The Android app **compiles and packages** on GitHub Actions (AGP 9.4, Gradle 9.6, Kotlin 2.4, `compileSdk` 37).
- ⚠️ **Not yet exercised on a real device against live Twitch.** The Twitch request/response shapes were taken from
  Twitch's docs and from reading open-source clients (Streamlink, Xtra), and the 7TV/BTTV/FFZ parsers were written
  against documented response shapes — but none of these services were reachable from the environment this was built
  in. Expect to fix small things on first contact, most likely in `PlaybackApi` or the emote parsers. D-pad focus
  behaviour and playback performance on real TV hardware are likewise untested.

## License

No license has been chosen yet — all rights reserved by the repository owner until one is added.
