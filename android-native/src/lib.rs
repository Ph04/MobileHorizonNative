use std::{any::Any, fs::OpenOptions, io::Write, path::{Path, PathBuf}, sync::Arc, time::{Duration, Instant, SystemTime, UNIX_EPOCH}};
use jni::{JNIEnv, objects::{JClass, JString}};

use winit::{
    application::ApplicationHandler,
    event::WindowEvent,
    event_loop::{ActiveEventLoop, EventLoop},
    window::{Window, WindowId},
};

use winit::platform::android::EventLoopBuilderExtAndroid;

fn diagnostics_root(home: &Path) -> PathBuf {
    std::env::var_os("HORIZON_DIAGNOSTICS_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| home.parent().unwrap_or(home).to_path_buf())
}

fn append_native_event(home: &Path, message: &str) {
    let logs = diagnostics_root(home).join("logs");
    if std::fs::create_dir_all(&logs).is_err() {
        return;
    }
    let path = logs.join("horizon-native-lifecycle.log");
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis();
        let _ = writeln!(
            file,
            "[{timestamp}][pid={}][native] {message}",
            std::process::id()
        );
        let _ = file.flush();
    }
}

fn write_result(home: &Path, file_name: &str, message: &str) {
    // All instance profiles export diagnostics to the same app-private folder.
    // HORIZON_HOME may be filesDir/instances/<id>/game, so home.parent() is not
    // the directory MainActivity collects from.
    let root = diagnostics_root(home);
    if std::fs::create_dir_all(&root).is_ok() {
        let path = root.join(file_name);
        if let Err(error) = std::fs::write(path, format!("{message}\n")) {
            append_native_event(home, &format!("could not write {file_name}: {error}"));
        }
    }
}

fn panic_message(payload: &(dyn Any + Send)) -> &str {
    if let Some(message) = payload.downcast_ref::<String>() {
        return message.as_str();
    }
    if let Some(message) = payload.downcast_ref::<&'static str>() {
        return message;
    }
    "panic Rust sem mensagem textual"
}

struct VulkanSmokeApp {
    result: Option<std::result::Result<String, String>>,
}

impl ApplicationHandler<()> for VulkanSmokeApp {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.result.is_some() {
            return;
        }

        let attempt = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            render_vulkan_smoke_frame(event_loop)
        }));
        self.result = Some(match attempt {
            Ok(result) => result,
            Err(payload) => Err(format!("panic ao inicializar o teste Vulkan: {}", panic_message(&*payload))),
        });
        event_loop.exit();
    }

    fn window_event(&mut self, event_loop: &ActiveEventLoop, _id: WindowId, event: WindowEvent) {
        if matches!(event, WindowEvent::CloseRequested) {
            if self.result.is_none() {
                self.result = Some(Err("A janela do teste Vulkan foi fechada antes de apresentar um frame.".into()));
            }
            event_loop.exit();
        }
    }
}

fn render_vulkan_smoke_frame(event_loop: &ActiveEventLoop) -> std::result::Result<String, String> {
    let window = Arc::new(
        event_loop
            .create_window(Window::default_attributes().with_title("MobileHorizon Vulkan diagnostic"))
            .map_err(|error| format!("não foi possível criar a janela nativa: {error}"))?,
    );
    let size = window.inner_size();

    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle().with_env();
    descriptor.backends = wgpu::Backends::VULKAN;
    let instance = wgpu::Instance::new(descriptor);
    let surface = instance
        .create_surface(window)
        .map_err(|error| format!("não foi possível criar a superfície WGPU: {error}"))?;

    let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
        power_preference: wgpu::PowerPreference::HighPerformance,
        compatible_surface: Some(&surface),
        force_fallback_adapter: false,
        ..Default::default()
    }))
    .map_err(|error| format!("não foi encontrado um adaptador Vulkan compatível: {error}"))?;
    let info = adapter.get_info();

    let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
        label: Some("MobileHorizon Vulkan smoke device"),
        required_features: wgpu::Features::empty(),
        required_limits: adapter.limits(),
        ..Default::default()
    }))
    .map_err(|error| format!("não foi possível criar o dispositivo Vulkan: {error}"))?;

    let config = surface
        .get_default_config(&adapter, size.width.max(1), size.height.max(1))
        .ok_or_else(|| "a superfície não oferece uma configuração de apresentação compatível".to_owned())?;
    surface.configure(&device, &config);

    let frame = match surface.get_current_texture() {
        wgpu::CurrentSurfaceTexture::Success(frame)
        | wgpu::CurrentSurfaceTexture::Suboptimal(frame) => frame,
        status => return Err(format!("não foi possível adquirir o frame de apresentação: {status:?}")),
    };
    let view = frame.texture.create_view(&wgpu::TextureViewDescriptor::default());
    let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
        label: Some("MobileHorizon Vulkan smoke encoder"),
    });
    let attachments = [Some(wgpu::RenderPassColorAttachment {
        view: &view,
        resolve_target: None,
        depth_slice: None,
        ops: wgpu::Operations {
            load: wgpu::LoadOp::Clear(wgpu::Color {
                r: 0.035,
                g: 0.08,
                b: 0.16,
                a: 1.0,
            }),
            store: wgpu::StoreOp::Store,
        },
    })];
    {
        let _pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("MobileHorizon Vulkan smoke pass"),
            color_attachments: &attachments,
            ..Default::default()
        });
    }

    queue.submit([encoder.finish()]);
    // Wait for the clear pass to complete, so PASS means the GPU accepted work,
    // not just that a Vulkan instance or surface was created.
    let _ = device.poll(wgpu::PollType::Wait {
        submission_index: None,
        timeout: Some(Duration::from_secs(5)),
    });
    queue.present(frame);

    Ok(format!(
        "Vulkan/WGPU inicializado; adaptador='{}'; driver='{} {}'; superfície configurada e um frame enviado para apresentação.",
        info.name, info.driver, info.driver_info
    ))
}

fn run_vulkan_smoke_test() -> std::result::Result<String, String> {
    let android_app = horizon_client::android_app()
        .map_err(|error| format!("Android NativeActivity ainda não foi configurada: {error}"))?;
    let mut builder = EventLoop::<()>::with_user_event();
    builder.with_android_app(android_app);
    let event_loop = builder
        .build()
        .map_err(|error| format!("não foi possível criar o event loop Winit para Vulkan: {error}"))?;

    let mut app = VulkanSmokeApp { result: None };
    event_loop
        .run_app(&mut app)
        .map_err(|error| format!("o event loop Winit falhou: {error}"))?;
    app.result
        .take()
        .unwrap_or_else(|| Err("Winit saiu sem executar a fase de inicialização/resume.".into()))
}

#[unsafe(no_mangle)]
fn android_main(app: winit::platform::android::activity::AndroidApp) {
    // winit's android-activity glue initializes ndk-context before android_main and
    // releases it after this function returns. Do not initialize it a second time:
    // ndk-context asserts on duplicate initialization and both game/Vulkan launch crash.
    horizon_client::set_android_app(app);

    // The native activity and Winit have reached the engine entry point. Preserve fallback
    // markers so the Java manager can select the bundled runtime on the next launch.
    if std::env::var("HORIZON_RUNTIME_USING_FALLBACK").ok().as_deref() != Some("1") {
        if let Ok(status_path) = std::env::var("HORIZON_RUNTIME_STATUS_FILE") {
            let _ = std::fs::write(status_path, "RUNNING:libhorizon_engine.so\n");
        }
    }
    horizon_client::telemetry::mark_start();

    let home = horizon_client::logging::game_home();
    append_native_event(
        &home,
        &format!(
            "android_main entered; runtimeVersion={}; fallback={}; instance={}",
            std::env::var("HORIZON_RUNTIME_VERSION").unwrap_or_else(|_| "unknown".into()),
            std::env::var("HORIZON_RUNTIME_USING_FALLBACK").unwrap_or_else(|_| "0".into()),
            std::env::var("HORIZON_INSTANCE_ID").unwrap_or_else(|_| "default".into())
        ),
    );
    if let Err(error) = std::fs::create_dir_all(&home) {
        let message = format!(
            "FAIL: Não foi possível criar o diretório do jogo {}: {error}",
            home.display()
        );
        eprintln!("{message}");
        let smoke_test = std::env::var("HORIZON_NATIVE_SMOKE_TEST").ok().as_deref() == Some("1");
        if smoke_test {
            write_result(&home, "horizon-native-smoke-result.txt", &message);
        } else {
            write_result(&home, "horizon-client-result.txt", &message);
        }
        return;
    }

    // The smoke mode is explicitly handed over by NativeGameActivity for this launch.
    // A leftover filesDir marker is no longer allowed to suppress client diagnostics.
    let smoke_test = std::env::var("HORIZON_NATIVE_SMOKE_TEST").ok().as_deref() == Some("1");
    if smoke_test {
        write_result(
            &home,
            "horizon-native-smoke-result.txt",
            "FAIL: O teste Vulkan iniciou, mas não concluiu; a atividade ou o processo pode ter sido encerrado durante a inicialização. Este relatório será substituído se o diagnóstico terminar.",
        );
        let attempt = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| run_vulkan_smoke_test()));
        let result = match attempt {
            Ok(Ok(details)) => format!("PASS: {details}"),
            Ok(Err(error)) => format!("FAIL: {error}"),
            Err(payload) => format!("FAIL: panic Rust durante o teste Vulkan: {}", panic_message(&*payload)),
        };
        write_result(&home, "horizon-native-smoke-result.txt", &result);
        return;
    }

    // Write a sentinel before initializing the game. If a native driver aborts the
    // process, MainActivity can at least report that startup stopped unexpectedly.
    write_result(
        &home,
        "horizon-client-result.txt",
        "FAIL: O processo nativo iniciou, mas encerrou antes de concluir a inicialização do cliente. Consulte logs/crash-reports para mais detalhes.",
    );

    if let Err(error) = std::env::set_current_dir(&home) {
        let message = format!(
            "FAIL: Não foi possível entrar no diretório do jogo {}: {error}",
            home.display()
        );
        eprintln!("{message}");
        write_result(&home, "horizon-client-result.txt", &message);
        return;
    }

    let execution = std::panic::catch_unwind(std::panic::AssertUnwindSafe(
        || -> horizon_client::Result<()> {
            horizon_client::logging::init(&home, "Render thread");
            horizon_client::crash::install(home.clone(), "client");
            // The Horizon patcher selects game::run() for Stable and rewrites this
            // call to game::run_with() when the selected Nightly exports that API.
            let game_started = Instant::now();
            append_native_event(&home, "game::run BEGIN");
            let game_result = horizon_client::game::run();
            append_native_event(
                &home,
                &format!(
                    "game::run RETURN; result={}; elapsedMs={}",
                    if game_result.is_ok() { "Ok" } else { "Err" },
                    game_started.elapsed().as_millis()
                ),
            );
            if let Err(error) = game_result {
                eprintln!("Horizon: {error}");
                horizon_client::crash::report_error(&home, "client", &*error);
                return Err(error);
            }
            Ok(())
        },
    ));

    horizon_client::logging::flush();
    match execution {
        Ok(Ok(())) => {
            let message =
                "EXIT: O loop do cliente Horizon terminou sem retornar um erro explícito.".to_owned();
            append_native_event(&home, &message);
            write_result(&home, "horizon-client-result.txt", &message);
        }
        Ok(Err(error)) => {
            let message = format!("FAIL: O cliente Horizon encerrou com erro: {error:#}");
            append_native_event(&home, &message);
            write_result(&home, "horizon-client-result.txt", &message);
        }
        Err(payload) => {
            let message = format!(
                "FAIL: O cliente Horizon sofreu um panic Rust: {}",
                panic_message(&*payload)
            );
            append_native_event(&home, &message);
            write_result(&home, "horizon-client-result.txt", &message);
        }
    }
}

// JNI bridge for the overlay; events are enqueued onto Winit's own loop.
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
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectMouseMotion(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
    dx: f32,
    dy: f32,
) {
    horizon_client::inject_android_input(horizon_client::AndroidInjectedInput::MouseMotion {
        dx: dx as f64,
        dy: dy as f64,
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectKeyCode(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
    key_code: i32,
    pressed: u8,
) {
    horizon_client::inject_android_input(horizon_client::AndroidInjectedInput::KeyInput {
        key_code,
        pressed: pressed != 0,
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectKeyboardText(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    value: JString<'_>,
    submit: u8,
) {
    let Ok(java_text) = env.get_string(&value) else { return; };
    let text = java_text.to_string_lossy().into_owned();
    horizon_client::inject_android_input(horizon_client::AndroidInjectedInput::KeyboardText {
        text,
        submit: submit != 0,
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeBeginChatDraft(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
) {
    horizon_client::inject_android_input(horizon_client::AndroidInjectedInput::BeginChatDraft);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeInjectImeText(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    value: JString<'_>,
    inserted: JString<'_>,
    backspaces: i32,
    submit: u8,
) {
    let Ok(java_value) = env.get_string(&value) else { return; };
    let Ok(java_inserted) = env.get_string(&inserted) else { return; };
    horizon_client::inject_android_input(horizon_client::AndroidInjectedInput::ImeText {
        value: java_value.to_string_lossy().into_owned(),
        inserted: java_inserted.to_string_lossy().into_owned(),
        backspaces: backspaces.max(0) as u32,
        submit: submit != 0,
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeGetChatDraft(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jni::sys::jstring {
    match env.new_string(horizon_client::android_chat_draft()) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeIsChatTarget(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
) -> u8 {
    u8::from(horizon_client::android_is_chat_target())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeIsGameplay(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
) -> u8 {
    u8::from(horizon_client::android_is_gameplay())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeHasLoadedWorld(
    _env: *mut core::ffi::c_void,
    _class: *mut core::ffi::c_void,
) -> u8 {
    u8::from(horizon_client::android_has_world())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mobilehorizon_app_NativeGameActivity_nativeGetGuiMetrics(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jni::sys::jintArray {
    let metrics = horizon_client::hud::android_gui_metrics();
    let Ok(array) = env.new_int_array(metrics.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_int_array_region(&array, 0, &metrics).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}
