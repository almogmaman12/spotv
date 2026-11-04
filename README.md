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
album, duration and playback position when available, then loads lyrics for that track. Apps that hide
metadata or do not publish a media session cannot be identified this way. Timing follows the session
clock when the source provides one.

## Free cross-app music recognition

The recognition work is built in layers. One metadata layer reads Android media sessions and media-style
notifications after the user enables SpotTV under Android **Notification access**. An optional Accessibility fallback can
inspect visible song-like text on-device when apps expose it. That text is not uploaded.

The opt-in audio path is also implemented:
- SpotTV asks for microphone permission and Android's MediaProjection consent.
- The user can choose internal playback capture or microphone-only mode.
- Internal capture is tried first; if the playing app blocks it or returns silence, SpotTV falls back to the microphone.
- It records a 12-second mono sample, not a continuous recording, and sends it to the configured
  recognition bridge. The notification includes a Stop action.
- Recognition can be stopped from Settings or the foreground notification. A successful match is passed into the existing lyrics engine. MusicBrainz is queried to enrich
  title/artist/album/duration when it finds a matching recording.

### Free recognition bridge

The `recognition-server/` folder contains an optional FastAPI service:
- ShazamIO recognition at `POST /v1/recognize`. ShazamIO is an unofficial, reverse-engineered
  client for Shazam, not an official API. It can break if Shazam changes its service.
- AcoustID + Chromaprint fallback at `POST /v1/acoustid/recognize`. The server computes the
  fingerprint using Chromaprint and looks it up through AcoustID. Register a free AcoustID client
  key and set `ACOUSTID_CLIENT_KEY` in the bridge's environment. AcoustID's free service is for
  non-commercial use and has rate limits.
- Audio samples are temporarily written only while Chromaprint calculates a fingerprint, then
  deleted. ShazamIO and AcoustID receive the sample/fingerprint for matching. The bridge does not
  retain a music database.

You can deploy the bridge using the included Render Blueprint on its free plan, or run the Docker
container on your own server. Render's free service may sleep when idle and cold starts can delay
the first recognition. Free hosting limits can change. The bridge is not guaranteed to remain free
or available forever.

After deployment, set `MUSIC_RECOGNITION_URL` to the bridge's HTTPS base URL in
`local.properties` or as a GitHub Actions repository variable. Do not put the AcoustID client key
in the APK. Set it only as `ACOUSTID_CLIENT_KEY` on the bridge.

### Radio and metadata helpers

- Radio Browser station search is available in the Android code.
- ICY `StreamTitle` extraction is implemented for radio stations that publish it. Radio Browser
  itself does not guarantee current-track metadata, and HLS/other stream formats may need separate
  handling.
- MusicBrainz is used to enrich recognised tracks, not to identify audio by itself.
- AudioTag is not included because a stable, documented free app API has not been verified.
- Google Song Search and ShazamKit are excluded from the free-only implementation.

### Limitations

Android internal playback capture requires user consent and the source app must allow capture.
Some protected apps and HDMI/external inputs cannot be captured internally. Microphone fallback can
hear TV speakers but depends on volume, room noise and speaker placement. Some stations do not
publish song metadata. ShazamIO is unofficial, and AcoustID can miss live/noisy recordings.

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
