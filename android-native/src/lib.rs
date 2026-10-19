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
