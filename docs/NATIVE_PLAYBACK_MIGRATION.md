# Native playback and Spotify DJ migration

SpotV is moving from controlling an external Spotify playback device through
the Web API to owning playback through an Android-native librespot engine.

## Implementation choice

The native engine is built against the MIT-licensed librespot crates. Square
was used to understand the architecture and Spotify DJ protocol, but its
GPL-3.0 application code is not copied into SpotV.

## Milestones

1. Native build foundation: Rust crate, Android ABI builds, JNI loading smoke
   test. Complete.
2. Native session and DJ context: connect with the existing short-lived PKCE
   access token, obtain a session token through librespot Login5, request the
   Spotify DJ session endpoint, and expose its track pages to Kotlin. The JNI
   bridge and first-page request are implemented; device testing and reliable
   reconnect handling remain.
3. Audio output: implement a bounded PCM bridge to Android AudioTrack, audio
   focus, lifecycle handling, and playback state events.
4. Connect player: make SpotV a Connect device and route queue, seek, shuffle,
   repeat, volume and track changes through the native player.
5. Spotify DJ narration: read per-track intro/jump/outro narration metadata,
   decode the prefetched voice clips and mix them into the same PCM output as
   music. Prefetch narration and duck music
   without pausing playback.
6. UI cutover: connect the player UI to native state, retaining Web API
   metadata/catalogue calls where appropriate.

## Current status

The native session handshake and Spotify DJ context-page request are now
wired through JNI and exposed by the experimental "Connect Spotify DJ"
settings action. It retrieves the real DJ session's track URIs, continuation
address, and available per-track intro/jump/outro narration metadata. It
also starts background requests to Spotify's TTS service and caches returned
voice clips in native memory. The clips are not decoded or mixed into music
yet, and the DJ tracks are not yet played by the native Connect player.

The existing Web API player remains active until native playback and reconnect
have passed device-level tests. Do not expose a native playback toggle before
then. The old Android TTS announcements are temporary voice hints, not
Spotify's DJ. The actual DJ still requires native Connect playback and
narration mixed into the same audio output.

## Build

GitHub Actions cross-compiles the native library for Android ABIs and packages
it in app/src/main/jniLibs. Automatic push and pull-request triggers are
disabled; run the workflow manually only when explicitly requested.
