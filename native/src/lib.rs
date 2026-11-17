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
use std::sync::{Mutex, OnceLock};
use tokio::runtime::Runtime;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static SESSION: OnceLock<Mutex<Option<Session>>> = OnceLock::new();

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
            })
            .to_string(),
        )
    });

    into_java_string(&mut env, result.unwrap_or_else(|error| {
        serde_json::json!({ "error": error, "tracks": [], "next": "" }).to_string()
    }))
}
