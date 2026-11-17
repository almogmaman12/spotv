//! Native playback core for SpotTV.
//!
//! MIT-licensed librespot crates are used as the engine. This first milestone
//! establishes reproducible Android ABI builds and a JNI loading smoke test.

use jni::objects::JClass;
use jni::sys::{jint, JNI_VERSION_1_6};
use jni::JNIEnv;
use jni::JavaVM;

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
    1
}
