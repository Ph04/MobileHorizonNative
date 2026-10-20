use winit::platform::android::activity::AndroidApp;

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    horizon_client::set_android_app(app);
    horizon_client::telemetry::mark_start();

    let home = horizon_client::logging::game_home();
    if let Err(error) = std::fs::create_dir_all(&home) {
        eprintln!("Horizon: cannot create game directory {}: {error}", home.display());
        return;
    }
    if let Err(error) = std::env::set_current_dir(&home) {
        eprintln!("Horizon: cannot enter game directory {}: {error}", home.display());
        return;
    }

    horizon_client::logging::init(&home, "Render thread");
    horizon_client::crash::install(home.clone(), "client");
    if let Err(error) = horizon_client::game::run() {
        eprintln!("Horizon: {error}");
        horizon_client::crash::report_error(&home, "client", &*error);
    }
    horizon_client::logging::flush();
}

// JNI bridge for the Android overlay. Events are enqueued onto Winit's own loop,
// so they reach Horizon's normal MouseInput/CursorMoved handlers without Accessibility.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectCursorMove(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
    x: f32,
    y: f32,
) {
    horizon_client::inject_android_input(horizon_client::AndroidInjectedInput::CursorMoved {
        x: x as f64,
        y: y as f64,
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectMouseButton(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
    button: i32,
    pressed: u8,
    x: f32,
    y: f32,
) {
    horizon_client::inject_android_input(horizon_client::AndroidInjectedInput::MouseButton {
        button: button as u32,
        pressed: pressed != 0,
        x: x as f64,
        y: y as f64,
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectMouseScroll(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
    amount: f32,
    x: f32,
    y: f32,
) {
    horizon_client::inject_android_input(horizon_client::AndroidInjectedInput::MouseScroll {
        amount,
        x: x as f64,
        y: y as f64,
    });
}
