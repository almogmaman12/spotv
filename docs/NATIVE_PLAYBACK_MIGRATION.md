# Native playback migration

SpotTV is moving from controlling an external Spotify playback device through
the Web API to owning playback through an Android-native librespot engine.

## Implementation choice

The native engine is built against the MIT-licensed librespot crates. Square
was used to understand the architecture and Spotify DJ protocol research, but
its GPL-3.0 application code is not copied into SpotTV. This keeps the new
native layer independently implemented.

## Milestones

1. Native build foundation: Rust crate, Android ABI builds, JNI loading smoke
   test. This milestone does not yet play audio.
2. Session and credentials: pass the user's OAuth access token to the native
   process, establish a librespot session, persist reusable credentials in
   app-private storage, and handle refresh/reconnect safely.
3. Audio output: implement a bounded PCM bridge to Android AudioTrack, audio
   focus, lifecycle handling, and playback state events.
4. Connect player: make SpotTV a Connect device and route queue, seek, shuffle,
   repeat, volume and track changes through the native player.
5. Spotify DJ: implement the actual session, not a playlist shortcut:
   - authenticate the native session and request
     `lexicon-session-provider/context-resolve/v2/session` for the DJ context;
   - follow the session's `next_page_url` and deduplicate its track history;
   - read the per-track intro/jump/outro narration metadata;
   - request Spotify's narration audio from `client-tts/v1/fulfill`;
   - decode and mix the narration into the same PCM output as the music, with
     ducking and prefetching so the music never waits for speech.
6. UI cutover: connect the player UI to native state, retaining Web API
   metadata/catalogue calls where appropriate.

## Current status

Milestone 1 is the only completed milestone at this point. The existing Web API-based player remains active until native playback and
reconnect have passed device-level tests. Do not expose a native playback
toggle before then. The old Android TTS announcements are only temporary
voice hints, not Spotify's DJ. They cannot produce Square-style DJ narration
while playback is happening on a different Spotify Connect device. The real
DJ must run inside SpotV's native Connect player so music and speech share one
audio output.

## Build

GitHub Actions cross-compiles the native library for Android ABIs and packages
it in app/src/main/jniLibs.
