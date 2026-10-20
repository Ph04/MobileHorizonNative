use std::path::Path;

use winit::platform::android::activity::AndroidApp;

fn write_client_result(home: &Path, message: &str) {
    // MainActivity lives in the main process and reads this shared app-private file
    // after NativeGameActivity returns. HORIZON_HOME is <filesDir>/horizon.
    if let Some(files_dir) = home.parent() {
        let path = files_dir.join("horizon-client-result.txt");
        let _ = std::fs::write(path, format!("{message}\n"));
    }
}

fn panic_message(payload: &(dyn std::any::Any + Send)) -> &str {
    if let Some(message) = payload.downcast_ref::<String>() {
        return message.as_str();
    }
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        return message;
    }
    "panic Rust sem mensagem textual"
}

#[unsafe(no_mangle)]
fn android_main(app: AndroidApp) {
    horizon_client::set_android_app(app);
    horizon_client::telemetry::mark_start();

    let home = horizon_client::logging::game_home();
    // The renderer smoke test has its own result file and must not be overwritten
    // by the full-client status report.
    let smoke_test = home
        .parent()
        .is_some_and(|files_dir| files_dir.join("horizon-native-smoke.flag").is_file());

    if !smoke_test {
        // Leave a failure sentinel behind so a native process crash/abort before
        // normal return is still visible in MainActivity instead of looking silent.
        write_client_result(
            &home,
            "FAIL: O processo nativo encerrou antes de concluir a inicialização; não foi possível registrar a causa. Confira o log Horizon e tente novamente.",
        );
    }

    if let Err(error) = std::fs::create_dir_all(&home) {
        let message = format!(
            "FAIL: Não foi possível criar o diretório do jogo {}: {error}",
            home.display()
        );
        eprintln!("{message}");
        if !smoke_test {
            write_client_result(&home, &message);
        }
        return;
    }
    if let Err(error) = std::env::set_current_dir(&home) {
        let message = format!(
            "FAIL: Não foi possível entrar no diretório do jogo {}: {error}",
            home.display()
        );
        eprintln!("{message}");
        if !smoke_test {
            write_client_result(&home, &message);
        }
        return;
    }

    let execution = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> horizon_client::Result<()> {
            horizon_client::logging::init(&home, "Render thread");
            horizon_client::crash::install(home.clone(), "client");
            if let Err(error) = horizon_client::game::run() {
                eprintln!("Horizon: {error}");
                horizon_client::crash::report_error(&home, "client", &*error);
                return Err(error);
            }
            Ok(())
        },
    ));

    horizon_client::logging::flush();

    if !smoke_test {
        match execution {
            Ok(Ok(())) => write_client_result(
                &home,
                "EXIT: O loop do cliente Horizon terminou sem retornar um erro explícito. Isso não confirma que o Minecraft chegou a carregar um mundo.",
            ),
            Ok(Err(error)) => write_client_result(
                &home,
                &format!("FAIL: O cliente Horizon encerrou com erro: {error:#}"),
            ),
            Err(payload) => write_client_result(
                &home,
                &format!("FAIL: O cliente Horizon sofreu um panic Rust: {}", panic_message(&*payload)),
            ),
        }
    }
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
