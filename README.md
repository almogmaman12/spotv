# SpotTV

A TV version of [SpotifyTablet](https://github.com/almogmaman12/SpotifyTablet): a big, remote-friendly
"now playing" screen for Android TV with the album cover, large playback controls and the full
synchronized lyrics engine. Everything else from the tablet app (lights, always-on display, weather,
calendar, Jellyfin, settings pages...) is gone.

## What it does

- Shows whatever is playing on your Spotify account (phone, speaker, the TV's own Spotify app...).
- Cover art with a blurred, colour-matched backdrop.
- Lyrics: the same engine as SpotifyTablet (Spicy Lyrics Mobile renderer, word-by-word karaoke sync,
  LRCLIB / Spicy / AMLL / Unison / NetEase / ... sources, translation and romanization, disk cache,
  prefetching of the next songs). The text size follows the TV's screen height.
- Big controls you can reach with the D-pad: shuffle, previous, play/pause, next, repeat. The focused
  button grows and turns white. The controls fade out while a song plays; the first button press
  wakes them.
- D-pad up from the buttons focuses the progress bar: left/right jump 10 s.
- The remote's own media keys (play/pause, next, previous, rewind, fast-forward, stop) work anywhere.
- Login happens on your phone (scan a QR code), so you never type a password with a TV remote.

## Cross-app music metadata (new)

SpotTV can now read the metadata that another Android TV app publishes through an Android media
session. This is the first recognition layer and does not capture or upload audio.

1. On the TV, open Android Settings.
2. Find **Apps** or **Special app access**.
3. Open **Notification access** / **Notification listener access**.
4. Enable access for **SpotTV**.

When a supported app publishes an active playing media session, SpotTV reads its title, artist,
album and duration and loads lyrics for that track. Apps that hide metadata or do not publish a media
session cannot be identified this way. Playback position is not consistently available, so this
initial metadata integration does not claim word-perfect timing for external apps.

## Planned free recognition layers

The next stages are audio-based recognition using short samples, with internal playback capture where
the source app allows it and microphone capture as a fallback. Candidate services are ShazamIO
(unofficial and subject to breakage), AcoustID + Chromaprint (non-commercial use), Radio Browser
(stream metadata where provided), and MusicBrainz (metadata enrichment). AudioTag will only be added
if a stable, permitted integration is verified. No paid recognition credentials, subscriptions,
trials, local music database, or local matching catalogue are planned. Google Song Search and
ShazamKit are excluded from the free-only implementation.

## Setup

1. **Spotify app.** Create one at <https://developer.spotify.com/dashboard>. Add this Redirect URI:
   `https://almogmaman12.github.io/spotv/callback.html`
   (no client secret is needed: the login uses PKCE).
2. **GitHub Pages.** In this repo: Settings -> Pages -> deploy from branch `main`, folder `/docs`.
   `docs/callback.html` is the small page Spotify redirects to; it hands the login code to the TV over
   your local network. If you host it elsewhere, set `SPOTIFY_REDIRECT_URI` to that URL.
3. **Secrets.** Copy `local.properties.example` to `local.properties` and fill in
   `SPOTIFY_CLIENT_ID` and `SPICY_LYRICS_API_KEY`. (Or add them as repository secrets and download the
   APK from the Build workflow.)
4. **Build and install.** Open in Android Studio, or `./gradlew assembleDebug` and
   `adb install app/build/outputs/apk/debug/app-debug.apk` (Android TV 10+ / API 29+).
5. **First start.** The TV shows a QR code. Scan it with a phone on the same Wi-Fi, log in to Spotify
   and tap Agree. The TV picks up from there and remembers the login.

## How the login works

Spotify TVs have no browser, so the TV serves a tiny page on your network
(`http://<tv-ip>:8765/`). Your phone opens it and is sent to Spotify. Spotify redirects to the https page
in `docs/` which bounces the phone back to the TV with the login code (only private network addresses
are accepted). The TV trades the code for tokens using the PKCE verifier it kept to itself.

## Lyrics timing

If lyrics run early or late because of audio delay on your TV or soundbar, set the
`lyrics_offset_ms` value (milliseconds, positive = earlier) in the `SpotifyPrefs` shared preferences,
for example with `adb shell`. An on-screen setting is a good next step.

## Layout of the code

- `lyrics/` - the lyrics engine, copied unchanged from SpotifyTablet (package renamed).
- `spotify/` - login (`SpotifyAuth`, `PairingServer`), Web API client, polling + commands.
- `ui/` - the TV screens, focusable controls, cover loading.
- `MainActivity.kt` - wires Spotify and Android media-session metadata to the lyrics engine and
  handles the remote's media keys.

## Licence notes

The lyrics renderer is a port of [Spicy Lyrics Mobile](https://github.com/spicylyrics/mobile)
(AGPL-3.0); see `app/src/main/java/com/almog/spotv/lyrics/mobile/LICENSE-spicy-lyrics-mobile` and the
NOTICE files next to it.
