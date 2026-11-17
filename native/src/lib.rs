//! Native Spotify session foundation for SpotV's Connect player and DJ.
//!
//! This module deliberately keeps the session on a Tokio runtime owned by the
//! native library. The Kotlin side passes the short-lived OAuth access token;
//! it is never persisted here. The DJ session response is fetched through the
//! authenticated librespot session, not through a hard-coded playlist.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring, JNI_VERSION_1_6};
use jni::JNIEnv;
use jni::JavaVM;
use librespot_core::{
    authentication::Credentials,
    config::SessionConfig,
    session::Session,
};
use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use tokio::runtime::Runtime;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static SESSION: OnceLock<Mutex<Option<Session>>> = OnceLock::new();
static DJ_METADATA: OnceLock<Mutex<HashMap<String, serde_json::Value>>> = OnceLock::new();
static DJ_AUDIO: OnceLock<Mutex<HashMap<(String, String), Vec<u8>>>> = OnceLock::new();

const DJ_CONTEXT_URI: &str = "spotify:playlist:37i9dQZF1EYkqdzj48dyYq";
const SPCLIENT: &str = "https://spclient.wg.spotify.com";
const APP_VERSION: &str = "1.2.0";

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("spotv-native")
            .build()
            .expect("could not start SpotV native runtime")
    })
}

fn dj_audio() -> &'static Mutex<HashMap<(String, String), Vec<u8>>> {
    DJ_AUDIO.get_or_init(|| Mutex::new(HashMap::new()))
}

fn dj_metadata() -> &'static Mutex<HashMap<String, serde_json::Value>> {
    DJ_METADATA.get_or_init(|| Mutex::new(HashMap::new()))
}

fn session_slot() -> &'static Mutex<Option<Session>> {
    SESSION.get_or_init(|| Mutex::new(None))
}

fn into_java_string(env: &mut JNIEnv<'_>, value: String) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn get_session() -> Result<Session, String> {
    session_slot()
        .lock()
        .map_err(|_| "native session lock was poisoned".to_string())?
        .clone()
        .ok_or_else(|| "native Spotify session is not connected".to_string())
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("spotvcore"),
    );
    let _ = vm;
    JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn Java_com_almog_spotv_spotify_NativePlaybackEngine_nativeVersion(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jint {
    2
}

/// Establish a native Spotify session using the user's existing PKCE token.
/// The token is only passed to librespot for this connection and is not saved.
#[no_mangle]
pub extern "system" fn Java_com_almog_spotv_spotify_NativePlaybackEngine_nativeConnect(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    access_token: JString<'_>,
    client_id: JString<'_>,
) -> jstring {
    let token = match env.get_string(&access_token) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return into_java_string(&mut env, format!("invalid access token: {error}")),
    };
    let client = match env.get_string(&client_id) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return into_java_string(&mut env, format!("invalid client id: {error}")),
    };
    if token.is_empty() || client.is_empty() {
        return into_java_string(&mut env, "Spotify login or client ID is missing".to_string());
    }

    let result = runtime().block_on(async move {
        if let Ok(guard) = session_slot().lock() {
            if guard.as_ref().is_some_and(|session| !session.is_invalid()) {
                return Ok::<String, String>("connected".to_string());
            }
        }

        let mut config = SessionConfig::default();
        config.client_id = client;
        let session = Session::new(config, None);
        session
            .connect(Credentials::with_access_token(token), false)
            .await
            .map_err(|error| format!("Spotify native login failed: {error}"))?;

        *session_slot()
            .lock()
            .map_err(|_| "native session lock was poisoned".to_string())? = Some(session);
        Ok("connected".to_string())
    });

    into_java_string(&mut env, result.unwrap_or_else(|error| error))
}

/// Tear down the authenticated session when the user signs out.
#[no_mangle]
pub extern "system" fn Java_com_almog_spotv_spotify_NativePlaybackEngine_nativeDisconnect(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    if let Ok(mut guard) = session_slot().lock() {
        if let Some(session) = guard.take() {
            session.shutdown();
        }
    }
    if let Ok(mut known) = dj_metadata().lock() {
        known.clear();
    }
    if let Ok(mut clips) = dj_audio().lock() {
        clips.clear();
    }
}

fn protobuf_varint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8 & 0x7f) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

fn dj_tts_request(line: &serde_json::Value) -> Result<Vec<u8>, String> {
    let ssml = line.get("ssml").and_then(|value| value.as_str())
        .ok_or_else(|| "DJ narration line has no SSML".to_string())?;
    let voice = line.get("voice").and_then(|value| value.as_str())
        .and_then(|value| value.strip_prefix("VOICE"))
        .and_then(|value| value.parse::<u64>().ok()).unwrap_or(1);
    let provider_name = line.get("provider").and_then(|value| value.as_str()).unwrap_or("");
    let provider = match provider_name {
        "CLOUD_TTS" => 1,
        "READSPEAKER" => 2,
        "POLLY" => 3,
        "WELL_SAID" => 4,
        "SONANTIC_DEPRECATED" => 5,
        "SONANTIC_FAST" | "" => 6,
        "OPENAI" => 7,
        "SONANTIC_LARGE" => 8,
        "ELEVEN_LABS_DEPRECATED" => 9,
        "ADS_PUBLIC" => 10,
        _ => 6,
    };

    let mut out = Vec::new();
    protobuf_varint(&mut out, (2 << 3) | 2);
    protobuf_varint(&mut out, ssml.len() as u64);
    out.extend_from_slice(ssml.as_bytes());
    for (field, value) in [(3u64, 5u64), (5, voice), (6, provider), (7, 44_100)] {
        protobuf_varint(&mut out, field << 3);
        protobuf_varint(&mut out, value);
    }
    Ok(out)
}

async fn fetch_dj_clip(
    session: &Session,
    token_type: &str,
    access_token: &str,
    line: &serde_json::Value,
) -> Result<Vec<u8>, String> {
    let request = http::Request::builder()
        .method(http::Method::POST)
        .uri(format!("{SPCLIENT}/client-tts/v1/fulfill"))
        .header(
            http::header::AUTHORIZATION,
            format!("{token_type} {access_token}"),
        )
        .header(http::header::ACCEPT, "application/json")
        .header(http::header::CONTENT_TYPE, "application/x-protobuf")
        .header("app-platform", "Android")
        .header("spotify-app-version", APP_VERSION)
        .body(bytes::Bytes::from(dj_tts_request(line)?))
        .map_err(|error| format!("could not create DJ narration request: {error}"))?;

    // The TTS service responds with a redirect whose Location is the generated clip.
    let response = session
        .http_client()
        .request_fut(request)
        .map_err(|error| format!("could not send DJ narration request: {error}"))?
        .await
        .map_err(|error| format!("DJ narration request failed: {error}"))?;
    let location = response
        .headers()
        .get(http::header::LOCATION)
        .and_then(|value| value.to_str().ok())
        .ok_or_else(|| format!("DJ narration service returned HTTP {} without a clip URL", response.status()))?
        .to_string();

    let audio_request = http::Request::builder()
        .method(http::Method::GET)
        .uri(location.as_str())
        .body(bytes::Bytes::new())
        .map_err(|error| format!("could not create DJ clip download: {error}"))?;
    let audio = session
        .http_client()
        .request_body(audio_request)
        .await
        .map_err(|error| format!("could not download DJ narration clip: {error}"))?;
    if audio.is_empty() {
        return Err("Spotify returned an empty DJ narration clip".to_string());
    }
    Ok(audio.to_vec())
}

/// Start fetching narration for the first few DJ tracks without blocking playback.
#[no_mangle]
pub extern "system" fn Java_com_almog_spotv_spotify_NativePlaybackEngine_nativePrefetchDj(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    tracks_json: JString<'_>,
) -> jstring {
    let raw = match env.get_string(&tracks_json) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return into_java_string(&mut env, format!("invalid DJ track list: {error}")),
    };
    let tracks: Vec<String> = match serde_json::from_str(&raw) {
        Ok(tracks) => tracks,
        Err(error) => return into_java_string(&mut env, format!("invalid DJ track list: {error}")),
    };

    let session = match get_session() {
        Ok(session) => session,
        Err(error) => return into_java_string(&mut env, error),
    };
    let metadata = match dj_metadata().lock() {
        Ok(known) => known.clone(),
        Err(_) => return into_java_string(&mut env, "DJ metadata lock was poisoned".to_string()),
    };

    let mut work = Vec::<(String, String, serde_json::Value)>::new();
    for uri in tracks {
        if let Some(lines) = metadata.get(&uri) {
            for kind in ["intro", "jump", "outro"] {
                if let Some(line) = lines.get(kind) {
                    let already_cached = dj_audio().lock()
                        .map(|clips| clips.contains_key(&(uri.clone(), kind.to_string())))
                        .unwrap_or(false);
                    if !already_cached {
                        work.push((uri.clone(), kind.to_string(), line.clone()));
                    }
                }
            }
        }
    }
    work.truncate(10);
    let scheduled = work.len();
    if scheduled == 0 {
        return into_java_string(&mut env, "scheduled:0".to_string());
    }

    let runtime_handle = runtime().handle().clone();
    runtime_handle.spawn(async move {
        let token = match session.login5().auth_token().await {
            Ok(token) => token,
            Err(error) => {
                log::warn!("DJ narration prefetch could not obtain a token: {error}");
                return;
            }
        };
        for (uri, kind, line) in work {
            match tokio::time::timeout(
                std::time::Duration::from_secs(20),
                fetch_dj_clip(&session, &token.token_type, &token.access_token, &line),
            ).await {
                Ok(Ok(audio)) => {
                    if let Ok(mut clips) = dj_audio().lock() {
                        clips.insert((uri.clone(), kind.clone()), audio);
                    }
                    log::info!("DJ narration cached: {kind} for {uri}");
                }
                Ok(Err(error)) => log::warn!("DJ narration failed for {uri} ({kind}): {error}"),
                Err(_) => log::warn!("DJ narration timed out for {uri} ({kind})"),
            }
        }
    });

    into_java_string(&mut env, format!("scheduled:{scheduled}"))
}

/// Fetch one page of Spotify's DJ session. The response contains track URIs
/// and the next page address; narration audio is a separate next milestone.
#[no_mangle]
pub extern "system" fn Java_com_almog_spotv_spotify_NativePlaybackEngine_nativeDjSession(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    page: JString<'_>,
    language: JString<'_>,
) -> jstring {
    let page = match env.get_string(&page) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return into_java_string(&mut env, format!("invalid page: {error}")),
    };
    let language = match env.get_string(&language) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return into_java_string(&mut env, format!("invalid language: {error}")),
    };

    let result = runtime().block_on(async move {
        let session = get_session()?;
        let token = session
            .login5()
            .auth_token()
            .await
            .map_err(|error| format!("could not obtain Spotify session token: {error}"))?;

        let path = if page.is_empty() {
            format!("/lexicon-session-provider/context-resolve/v2/session?contextUri={DJ_CONTEXT_URI}")
        } else {
            let rest = page
                .strip_prefix("hm://")
                .ok_or_else(|| "Spotify DJ returned an invalid next-page address".to_string())?;
            format!("/{rest}")
        };
        let url = format!("{SPCLIENT}{path}");
        let language = if language.is_empty() { "en" } else { &language };

        let request = http::Request::builder()
            .method(http::Method::GET)
            .uri(url.as_str())
            .header(
                http::header::AUTHORIZATION,
                format!("{} {}", token.token_type, token.access_token),
            )
            .header(http::header::ACCEPT, "application/json")
            .header("app-platform", "Android")
            .header("spotify-app-version", APP_VERSION)
            .header(http::header::ACCEPT_LANGUAGE, language)
            .body(bytes::Bytes::new())
            .map_err(|error| format!("could not create DJ request: {error}"))?;

        let body = session
            .http_client()
            .request_body(request)
            .await
            .map_err(|error| format!("Spotify DJ session request failed: {error}"))?;
        let document: serde_json::Value = serde_json::from_slice(&body)
            .map_err(|error| format!("Spotify DJ returned unreadable data: {error}"))?;

        let pages: Vec<&serde_json::Value> = match document.get("pages").and_then(|v| v.as_array()) {
            Some(pages) => pages.iter().collect(),
            None => vec![&document],
        };
        let mut tracks = Vec::<String>::new();
        let mut narration = serde_json::Map::new();
        let mut next = String::new();
        for page in pages {
            if let Some(url) = page.get("next_page_url").and_then(|v| v.as_str()) {
                if !url.is_empty() {
                    next = url.to_string();
                }
            }
            if let Some(items) = page.get("tracks").and_then(|v| v.as_array()) {
                for item in items {
                    if let Some(uri) = item.get("uri").and_then(|v| v.as_str()) {
                        if uri.starts_with("spotify:track:") && !tracks.iter().any(|old| old == uri) {
                            tracks.push(uri.to_string());
                            let metadata = item.get("metadata").unwrap_or(&serde_json::Value::Null);
                            let mut track_lines = serde_json::Map::new();
                            for kind in ["intro", "jump", "outro"] {
                                let field = |name: &str| {
                                    let key = format!("narration.{kind}.{name}");
                                    metadata.get(key.as_str()).and_then(|value| value.as_str()).map(str::to_string)
                                };
                                if let Some(ssml) = field("ssml").filter(|value| !value.is_empty()) {
                                    let line = serde_json::json!({
                                        "ssml": ssml,
                                        "voice": field("voice").unwrap_or_default(),
                                        "provider": field("tts_provider").unwrap_or_default(),
                                        "loudness": field("loudness"),
                                        "true_peak": field("true_peak"),
                                    });
                                    track_lines.insert(kind.to_string(), line);
                                }
                            }
                            if !track_lines.is_empty() {
                                let lines = serde_json::Value::Object(track_lines);
                                narration.insert(uri.to_string(), lines.clone());
                                if let Ok(mut known) = dj_metadata().lock() {
                                    known.insert(uri.to_string(), lines);
                                }
                            }
                        }
                    }
                }
            }
        }

        let count = tracks.len();
        Ok::<String, String>(
            serde_json::json!({
                "tracks": tracks,
                "next": next,
                "count": count,
                "narration": narration,
            })
            .to_string(),
        )
    });

    into_java_string(&mut env, result.unwrap_or_else(|error| {
        serde_json::json!({ "error": error, "tracks": [], "next": "" }).to_string()
    }))
}
