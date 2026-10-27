# Ordered patch step. See README.md in this directory for scope and order.
replace_once(audio,
    """#[cfg(feature = "audio")]
fn open_device(
    device: &cpal::Device,
    mixer: Arc<Mutex<Mixer>>,
) -> Result<(cpal::Stream, cpal::SupportedStreamConfig)> {
    use cpal::SampleFormat as F;
    let config = device.default_output_config()?;
    let stream = match config.sample_format() {
        F::F32 => build_stream::<f32>(device, &config, mixer),
        F::F64 => build_stream::<f64>(device, &config, mixer),
        F::I16 => build_stream::<i16>(device, &config, mixer),
        F::I32 => build_stream::<i32>(device, &config, mixer),
        F::I8 => build_stream::<i8>(device, &config, mixer),
        F::U16 => build_stream::<u16>(device, &config, mixer),
        F::U32 => build_stream::<u32>(device, &config, mixer),
        F::U8 => build_stream::<u8>(device, &config, mixer),
        other => Err(format!("unsupported sample format {other}").into()),
    }?;
    stream.play()?;
    Ok((stream, config))
}""",
    """#[cfg(feature = "audio")]
fn open_device(
    device: &cpal::Device,
    mixer: Arc<Mutex<Mixer>>,
) -> Result<(cpal::Stream, cpal::SupportedStreamConfig)> {
    use cpal::SampleFormat as F;

    // Android may reject the preferred config: try the device's supported formats/rates.
    let preferred_rate = |range: &cpal::SupportedStreamConfigRange| {
        let min = range.min_sample_rate().0;
        let max = range.max_sample_rate().0;
        for rate in [48_000u32, 44_100, 32_000, 22_050] {
            if min <= rate && rate <= max { return rate; }
        }
        max
    };
    let mut configs: Vec<_> = device.supported_output_configs()?.collect();
    configs.sort_by_key(|range| {
        let format_priority = match range.sample_format() { F::F32 => 0u8, F::I16 => 1u8, _ => 2u8 };
        let channel_priority = if range.channels() >= 2 { 0u8 } else { 1u8 };
        (format_priority, preferred_rate(range).abs_diff(48_000), channel_priority)
    });

    let mut errors = Vec::new();
    for range in configs {
        let rate = preferred_rate(&range);
        let config = range.with_sample_rate(cpal::SampleRate(rate));
        let description = format!("{} Hz, {} channel(s), {}", config.sample_rate().0, config.channels(), config.sample_format());
        let stream = match config.sample_format() {
            F::F32 => build_stream::<f32>(device, &config, mixer.clone()),
            F::F64 => build_stream::<f64>(device, &config, mixer.clone()),
            F::I16 => build_stream::<i16>(device, &config, mixer.clone()),
            F::I32 => build_stream::<i32>(device, &config, mixer.clone()),
            F::I8 => build_stream::<i8>(device, &config, mixer.clone()),
            F::U16 => build_stream::<u16>(device, &config, mixer.clone()),
            F::U32 => build_stream::<u32>(device, &config, mixer.clone()),
            F::U8 => build_stream::<u8>(device, &config, mixer.clone()),
            other => Err(format!("unsupported sample format {other}").into()),
        };
        match stream {
            Ok(stream) => match stream.play() {
                Ok(()) => {
                    log::info!("Horizon: audio output {description}");
                    return Ok((stream, config));
                }
                Err(error) => errors.push(format!("{description}: stream start failed: {error}")),
            },
            Err(error) => errors.push(format!("{description}: {error}")),
        }
    }
    if errors.is_empty() {
        Err("Android audio device reported no usable output configurations".into())
    } else {
        Err(format!("no usable output audio configuration: {}", errors.join("; ")).into())
    }
}""")

replace_once(client_cargo, 'winit = "0.30.13"',
             'winit = { version = "0.30.13", features = ["android-native-activity"] }')

# Keep the desktop clipboard for desktop builds, but don't compile it on Android.
replace_once(client_cargo,
    'arboard = { version = "3.6.1", default-features = false, features = ["wayland-data-control"] }',
    'arboard = { version = "3.6.1", default-features = false, features = ["wayland-data-control"], optional = true }')
replace_once(client_cargo,
    'default = ["audio", "controllers"]',
    'default = ["audio", "controllers", "system-clipboard"]')
replace_once(client_cargo,
    'controllers = ["dep:gilrs", "dep:horizon-dualsense"]',
    'controllers = ["dep:gilrs", "dep:horizon-dualsense"]\nsystem-clipboard = ["dep:arboard"]')

# Keep the existing SystemClipboard type, but make its desktop-only backend optional.
# The Android NativeActivity is built with --no-default-features and uses memory clipboard.
clipboard_struct_old = """#[derive(Default)]
pub struct SystemClipboard {
    system: Option<arboard::Clipboard>,
    /// The text Horizon last copied (`horizon-ctl status`).
    copied: Option<String>,
}"""
clipboard_struct_new = """#[derive(Default)]
pub struct SystemClipboard {
    #[cfg(feature = "system-clipboard")]
    system: Option<arboard::Clipboard>,
    /// The text Horizon last copied (`horizon-ctl status`).
    copied: Option<String>,
}"""
replace_once(clipboard, clipboard_struct_old, clipboard_struct_new)

clipboard_method_old = """    fn system(&mut self) -> Option<&mut arboard::Clipboard> {
        if self.system.is_none() {
            self.system = arboard::Clipboard::new().ok();
        }
        self.system.as_mut()
    }"""
clipboard_method_new = """    #[cfg(feature = "system-clipboard")]
    fn system(&mut self) -> Option<&mut arboard::Clipboard> {
        if self.system.is_none() {
            self.system = arboard::Clipboard::new().ok();
        }
        self.system.as_mut()
    }"""
replace_once(clipboard, clipboard_method_old, clipboard_method_new)

clipboard_impl_old = """impl Clipboard for SystemClipboard {
    fn get(&mut self) -> String {
        let Some(system) = self.system() else {
            return self.copied.clone().unwrap_or_default();
        };
        match system.get_text() {
            Ok(text) => text,
            Err(arboard::Error::ContentNotAvailable) => String::new(),
            Err(_) => {
                // A lost connection (a restarted compositor): reconnect next time.
                self.system = None;
                String::new()
            }
        }
    }
    fn set(&mut self, text: &str) {
        if text.is_empty() {
            return;
        }
        self.copied = Some(text.to_owned());
        if let Some(system) = self.system()
            && system.set_text(text.to_owned()).is_err()
        {
            self.system = None;
        }
    }
}"""
clipboard_impl_new = """impl Clipboard for SystemClipboard {
    fn get(&mut self) -> String {
        #[cfg(feature = "system-clipboard")]
        {
            let Some(system) = self.system() else {
                return self.copied.clone().unwrap_or_default();
            };
            match system.get_text() {
                Ok(text) => text,
                Err(arboard::Error::ContentNotAvailable) => String::new(),
                Err(_) => {
                    // A lost connection (a restarted compositor): reconnect next time.
                    self.system = None;
                    self.copied.clone().unwrap_or_default()
                }
            }
        }
        #[cfg(not(feature = "system-clipboard"))]
        {
            self.copied.clone().unwrap_or_default()
        }
    }
    fn set(&mut self, text: &str) {
        if text.is_empty() {
            return;
        }
        self.copied = Some(text.to_owned());
        #[cfg(feature = "system-clipboard")]
        {
            if let Some(system) = self.system()
                && system.set_text(text.to_owned()).is_err()
            {
                self.system = None;
            }
        }
    }
}"""
replace_once(clipboard, clipboard_impl_old, clipboard_impl_new)

# The Android keyboard can edit title/menu fields before a world exists, including world names
# and multiplayer names/addresses. Use Horizon's own focused-field editing APIs.
replace_once(pad_menu,
    "    fn pad_osk_output(&mut self, out: osk::Output) {",
    "    pub(super) fn pad_osk_output(&mut self, out: osk::Output) {")

replace_once(startup_menu,
    "    pub(super) fn menu_event(",
    """    pub(super) fn android_keyboard_text(&mut self, text: &str, submit: bool) {
        if self.menu.pending.is_some() {
            return;
        }
        if self.menu.options_open {
            if let Some(gui) = self.gui.as_ref() {
                self.menu.options.type_text(&gui.atlas, text);
            }
            return;
        }
        let (Some(title), clipboard) =
            (self.menu.title.as_mut(), &mut self.menu.clipboard)
        else {
            return;
        };
        if title.focused_field().is_none() {
            return;
        }
        if title.has_caret_field() {
            title.field_shortcut(
                horizon_client::clipboard::EditShortcut::SelectAll,
                clipboard,
            );
            if text.is_empty() {
                title.field_key(winit::keyboard::KeyCode::Backspace, false, false);
            } else {
                title.type_text(text);
            }
        } else {
            if let Some((_, value, _)) = title.focused_field() {
                value.clear();
            }
            title.type_text(text);
        }
        if submit {
            title.confirm_edit();
        }
        self.save_menu_address();
    }

    pub(super) fn menu_event(""")

android_runtime_api = '''pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

#[derive(Clone, Debug)]
pub enum AndroidInjectedInput {
    CursorMoved { x: f64, y: f64 },
    MouseButton { button: u32, pressed: bool, x: f64, y: f64 },
    MouseScroll { amount: f32, x: f64, y: f64 },
    /// Android KeyEvent key code and edge state from the touchscreen control layer.
    /// Relative movement from a finger drag; consumed as DeviceEvent::MouseMotion when captured.
    MouseMotion { dx: f64, dy: f64 },
    KeyInput { key_code: i32, pressed: bool },
    /// Text from Android's keyboard and whether to commit the focused editor.
    KeyboardText { text: String, submit: bool },
    /// Open Horizon's real chat input before showing Android's IME.
    BeginChatDraft,
    /// Full IME value plus the edit delta for non-chat fields.
    ImeText { value: String, inserted: String, backspaces: u32, submit: bool },
}

static HORIZON_ANDROID_CHAT_OPEN: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);
static HORIZON_ANDROID_CHAT_TARGET: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);
static HORIZON_ANDROID_CHAT_DRAFT: std::sync::OnceLock<std::sync::Mutex<String>> =
    std::sync::OnceLock::new();

pub fn set_android_chat_open(open: bool) {
    HORIZON_ANDROID_CHAT_OPEN.store(open, std::sync::atomic::Ordering::Relaxed);
    if !open {
        set_android_chat_draft("");
    }
}

pub fn set_android_chat_target(active: bool) {
    HORIZON_ANDROID_CHAT_TARGET.store(active, std::sync::atomic::Ordering::Relaxed);
}

pub fn android_is_chat_target() -> bool {
    HORIZON_ANDROID_CHAT_TARGET.load(std::sync::atomic::Ordering::Relaxed)
}

pub fn set_android_chat_draft(text: &str) {
    let slot = HORIZON_ANDROID_CHAT_DRAFT
        .get_or_init(|| std::sync::Mutex::new(String::new()));
    if let Ok(mut guard) = slot.lock() {
        *guard = text.to_owned();
    }
}

pub fn android_chat_draft() -> String {
    if !HORIZON_ANDROID_CHAT_OPEN.load(std::sync::atomic::Ordering::Relaxed) {
        return String::new();
    }
    let slot = HORIZON_ANDROID_CHAT_DRAFT
        .get_or_init(|| std::sync::Mutex::new(String::new()));
    slot.lock().map(|guard| guard.clone()).unwrap_or_default()
}

static HORIZON_ANDROID_GAMEPLAY: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

pub fn set_android_gameplay(active: bool) {
    HORIZON_ANDROID_GAMEPLAY.store(active, std::sync::atomic::Ordering::Relaxed);
}

pub fn android_is_gameplay() -> bool {
    HORIZON_ANDROID_GAMEPLAY.load(std::sync::atomic::Ordering::Relaxed)
}

static HORIZON_ANDROID_WORLD_ACTIVE: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

pub fn set_android_world_active(active: bool) {
    HORIZON_ANDROID_WORLD_ACTIVE.store(active, std::sync::atomic::Ordering::Relaxed);
}

pub fn android_has_world() -> bool {
    HORIZON_ANDROID_WORLD_ACTIVE.load(std::sync::atomic::Ordering::Relaxed)
}

static HORIZON_ANDROID_EVENT_LOOP_PROXY:
    std::sync::OnceLock<std::sync::Mutex<Option<winit::event_loop::EventLoopProxy<AndroidInjectedInput>>>> =
    std::sync::OnceLock::new();

pub fn set_android_event_loop_proxy(proxy: winit::event_loop::EventLoopProxy<AndroidInjectedInput>) {
    let slot = HORIZON_ANDROID_EVENT_LOOP_PROXY.get_or_init(|| std::sync::Mutex::new(None));
    if let Ok(mut guard) = slot.lock() {
        *guard = Some(proxy);
    }
}

pub fn inject_android_input(event: AndroidInjectedInput) {
    let Some(slot) = HORIZON_ANDROID_EVENT_LOOP_PROXY.get() else { return; };
    if let Ok(guard) = slot.lock() {
        if let Some(proxy) = guard.as_ref() {
            let _ = proxy.send_event(event);
        }
    }
}

#[cfg(target_os = "android")]
extern crate self as horizon_client;

#[cfg(target_os = "android")]
pub mod game;

#[cfg(target_os = "android")]
std::thread_local! {
    static HORIZON_ANDROID_APP: std::cell::RefCell<Option<winit::platform::android::activity::AndroidApp>> =
        const { std::cell::RefCell::new(None) };
}

#[cfg(target_os = "android")]
pub fn set_android_app(app: winit::platform::android::activity::AndroidApp) {
    HORIZON_ANDROID_APP.with(|slot| *slot.borrow_mut() = Some(app));
}

#[cfg(target_os = "android")]
pub fn android_app() -> Result<winit::platform::android::activity::AndroidApp> {
    HORIZON_ANDROID_APP.with(|slot| {
        slot.borrow().as_ref().cloned().ok_or_else(|| {
            std::io::Error::new(std::io::ErrorKind::NotConnected, "Android NativeActivity is not initialized").into()
        })
    })
}'''
legacy_result_alias = 'pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;'
core_result_alias = 'pub use horizon_client_core::Result;'
lib_source = lib.read_text(encoding="utf-8")
if lib_source.count(legacy_result_alias) == 1:
    # Horizon <= v0.2.75 defined the result alias locally; insert Android bridge here.
    replace_once(lib, legacy_result_alias, android_runtime_api)
elif lib_source.count(core_result_alias) == 1:
    # v0.2.76 moved Result into horizon-client-core; preserve that public re-export.
    android_runtime_additions = android_runtime_api.split("\n\n", 1)[1]
    replace_once(lib, core_result_alias, core_result_alias + "\n\n" + android_runtime_additions)
else:
    raise SystemExit(
        f"{lib}: expected one supported Result export (legacy alias or horizon_client_core re-export); "
        f"found legacy={lib_source.count(legacy_result_alias)}, core={lib_source.count(core_result_alias)}"
    )

replace_once(hud,
    'pub static GUI_SCALE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);',
    '''pub static GUI_SCALE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
/// Latest viewport and logical GUI dimensions used to render Minecraft's HUD.
/// [actualScale, guiWidth, guiHeight, framebufferWidth, framebufferHeight].
pub static ANDROID_GUI_METRICS: [std::sync::atomic::AtomicU32; 5] = [
    std::sync::atomic::AtomicU32::new(1),
    std::sync::atomic::AtomicU32::new(0),
    std::sync::atomic::AtomicU32::new(0),
    std::sync::atomic::AtomicU32::new(0),
    std::sync::atomic::AtomicU32::new(0),
];
pub fn android_gui_metrics() -> [i32; 5] {
    [
        ANDROID_GUI_METRICS[0].load(std::sync::atomic::Ordering::Relaxed) as i32,
        ANDROID_GUI_METRICS[1].load(std::sync::atomic::Ordering::Relaxed) as i32,
        ANDROID_GUI_METRICS[2].load(std::sync::atomic::Ordering::Relaxed) as i32,
        ANDROID_GUI_METRICS[3].load(std::sync::atomic::Ordering::Relaxed) as i32,
        ANDROID_GUI_METRICS[4].load(std::sync::atomic::Ordering::Relaxed) as i32,
    ]
}''')

# Horizon v0.2.27 extends HudBatch with EndPortalFill and blur_at.
# Accept both the previous constructor layout and the current release layout.
hud_batch_initializers = [
    (
        '''        Self {
            vertices: Vec::new(),
            scale: scale as f32,
            width: (window[0] as f32 / scale as f32).ceil(),
            height: (window[1] as f32 / scale as f32).ceil(),
            patches: Vec::new(),
            blends: Vec::new(),
        }''',
        '''        Self {
            vertices: Vec::new(),
            scale: scale as f32,
            width: gui_width as f32,
            height: gui_height as f32,
            patches: Vec::new(),
            blends: Vec::new(),
        }''',
    ),
    (
        '''        Self {
            vertices: Vec::new(),
            scale: scale as f32,
            width: (window[0] as f32 / scale as f32).ceil(),
            height: (window[1] as f32 / scale as f32).ceil(),
            patches: Vec::new(),
            blends: Vec::new(),
            end_portal: EndPortalFill::default(),
            blur_at: 0,
        }''',
        '''        Self {
            vertices: Vec::new(),
            scale: scale as f32,
            width: gui_width as f32,
            height: gui_height as f32,
            patches: Vec::new(),
            blends: Vec::new(),
            end_portal: EndPortalFill::default(),
            blur_at: 0,
        }''',
    ),
    (
        '''        Self {
            vertices: Vec::new(),
            scale: scale as f32,
            width: (window[0] as f32 / scale as f32).ceil(),
            height: (window[1] as f32 / scale as f32).ceil(),
            patches: Vec::new(),
            blends: Vec::new(),
            end_portal: EndPortalFill::default(),
            glint_alpha: 0.75,
            blur_at: 0,
        }''',
        '''        Self {
            vertices: Vec::new(),
            scale: scale as f32,
            width: gui_width as f32,
            height: gui_height as f32,
            patches: Vec::new(),
            blends: Vec::new(),
            end_portal: EndPortalFill::default(),
            glint_alpha: 0.75,
            blur_at: 0,
        }''',
    ),
]
hud_content = hud.read_text(encoding="utf-8")
matched_initializers = [
    pair for pair in hud_batch_initializers
    if hud_content.count(pair[0]) == 1
]
if len(matched_initializers) != 1:
    raise SystemExit(
        f"{hud}: expected exactly one known HudBatch::new initializer layout, "
        f"found {len(matched_initializers)}"
    )
old_initializer, new_initializer = matched_initializers[0]
new_constructor_prefix = '''        let gui_width = (window[0] as f32 / scale as f32).ceil() as u32;
        let gui_height = (window[1] as f32 / scale as f32).ceil() as u32;
        for (index, value) in [scale, gui_width, gui_height, window[0], window[1]].into_iter().enumerate() {
            ANDROID_GUI_METRICS[index].store(value, std::sync::atomic::Ordering::Relaxed);
        }
'''
replace_once(hud, old_initializer, new_constructor_prefix + new_initializer)


# NativeGameActivity sets the private data root to filesDir/horizon, whereas the
# launcher imports/exports the actual prepared resource tree under filesDir/.local.
# Honor an absolute resource root passed by the Android Activity instead of resolving
# .local/horizon-client/26.3 relative to the game's save/log directory.
replace_once(game,
    '''            resources: if sibling.join("horizon-resources.json").exists() {
                sibling
            } else {
                std::path::Path::new(".local/horizon-client").join(horizon_core::VANILLA_VERSION)
            },''',
    '''            resources: std::env::var_os("HORIZON_RESOURCES")
                .map(PathBuf::from)
                .unwrap_or_else(|| {
                    if sibling.join("horizon-resources.json").exists() {
                        sibling
                    } else {
                        PathBuf::from(".local/horizon-client/26.3")
                    }
                }),''')

replace_once(game,
    'use horizon_client::{\n    Result,',
    'use horizon_client::{\n    Result,\n    chat::Commands,\n    screen_draw::Measure,')
replace_once(game,
    'use horizon_core::{\n    biome::{BiomeZoom, obfuscate_seed},',
    'use self::chat::chat_tree;\nuse horizon_core::{\n    biome::{BiomeZoom, obfuscate_seed},')
replace_once(game,
    'event::{DeviceEvent, DeviceId, ElementState, MouseButton, MouseScrollDelta, WindowEvent},',
    'event::{DeviceEvent, DeviceId, ElementState, MouseButton, MouseScrollDelta, TouchPhase, WindowEvent},')
replace_once(game,
    '    agent_pointer: bool,\n',
    '    agent_pointer: bool,\n    /// First active touchscreen pointer translated to mouse input.\n    touch_mouse: Option<u64>,\n    /// A simultaneous second pointer requests a right-click.\n    touch_auxiliary: Option<u64>,\n    touch_is_right: bool,\n')
replace_once(game, '            agent_pointer: false,\n',
             '            agent_pointer: false,\n            touch_mouse: None,\n            touch_auxiliary: None,\n            touch_is_right: false,\n')

# Keep the Android IME proxy synchronized with Horizon's real chat field.
replace_once(chat,
    'fn chat_tree<\'a>(',
    'pub(super) fn chat_tree<\'a>(')
replace_once(chat,
    """        self.ui.chat_screen = Some(screen);
        self.ui.chat_opened = Some(std::time::Instant::now());""",
    """        self.ui.chat_screen = Some(screen);
        horizon_client::set_android_chat_open(true);
        horizon_client::set_android_chat_draft(initial);
        self.ui.chat_opened = Some(std::time::Instant::now());""")
# The upstream chat close path gained a wake-up guard for the Leave Bed screen.
# Insert the keyboard state transition at the same point the chat screen is cleared,
# while retaining support for older Horizon releases that clear it immediately.
close_chat_anchors = [
    (
        """    pub(super) fn close_chat(&mut self) {
        self.ui.chat_screen = None;""",
        """    pub(super) fn close_chat(&mut self) {
        horizon_client::set_android_chat_open(false);
        self.ui.chat_screen = None;""",
    ),
    (
        """        if self.ui.in_bed_chat && self.sleeping.is_some() {
            self.wake_up();
            return;
        }
        self.ui.chat_screen = None;""",
        """        if self.ui.in_bed_chat && self.sleeping.is_some() {
            self.wake_up();
            return;
        }
        horizon_client::set_android_chat_open(false);
        self.ui.chat_screen = None;""",
    ),
]
chat_content = chat.read_text(encoding="utf-8")
matched_close_chat = [pair for pair in close_chat_anchors if chat_content.count(pair[0]) == 1]
if len(matched_close_chat) != 1:
    raise SystemExit(
        f"{chat}: expected one known close_chat layout, found {len(matched_close_chat)}"
    )
replace_once(chat, *matched_close_chat[0])
replace_once(chat,
    """                if let Some(text) = typed_text
                    && !ctrl
                {
                    screen.type_text(&m, &c, text);
                }
                self.ui.chat_screen = Some(screen);""",
    """                if let Some(text) = typed_text
                    && !ctrl
                {
                    screen.type_text(&m, &c, text);
                }
                horizon_client::set_android_chat_draft(screen.value());
                self.ui.chat_screen = Some(screen);""")
replace_once(chat,
    """        if let Some(screen) = self.ui.chat_screen.as_mut() {
            screen.type_text(&m, &c, text);
        }
    }
    /// ChatScreen.handleChatInput:""",
    """        if let Some(screen) = self.ui.chat_screen.as_mut() {
            screen.type_text(&m, &c, text);
            horizon_client::set_android_chat_draft(screen.value());
        }
    }
    /// ChatScreen.handleChatInput:""")

impl_start = "impl ApplicationHandler for Game<'_> {"
idx = game.read_text(encoding="utf-8").find(impl_start)
if idx < 0:
    raise SystemExit("Could not find Game ApplicationHandler implementation")
injected_impl = """impl ApplicationHandler<horizon_client::AndroidInjectedInput> for Game<'_> {
    fn user_event(&mut self, event_loop: &ActiveEventLoop, event: horizon_client::AndroidInjectedInput) {
        let Some(id) = self.window.as_ref().map(|window| window.id()) else { return; };
        let device_id = DeviceId::dummy();
        match event {
            horizon_client::AndroidInjectedInput::CursorMoved { x, y } => {
                self.window_event(event_loop, id, WindowEvent::CursorMoved {
                    device_id,
                    position: winit::dpi::PhysicalPosition::new(x, y),
                });
            }
            horizon_client::AndroidInjectedInput::MouseButton { button, pressed, x, y } => {
                // A virtual pointer substitutes for the physical cursor on touch-only Android.
                // Set this before handling the press, otherwise the desktop capture check can
                // consume each click because Android cannot lock a hardware cursor.
                if self.screen == Screen::Playing && self.player.is_some() {
                    self.agent_pointer = true;
                }
                self.window_event(event_loop, id, WindowEvent::CursorMoved {
                    device_id,
                    position: winit::dpi::PhysicalPosition::new(x, y),
                });
                let button = match button {
                    1 => Some(MouseButton::Left),
                    2 => Some(MouseButton::Right),
                    4 => Some(MouseButton::Middle),
                    _ => None,
                };
                if let Some(button) = button {
                    self.window_event(event_loop, id, WindowEvent::MouseInput {
                        device_id,
                        state: if pressed { ElementState::Pressed } else { ElementState::Released },
                        button,
                    });
                }
            }
            horizon_client::AndroidInjectedInput::MouseScroll { amount, x, y } => {
                self.window_event(event_loop, id, WindowEvent::CursorMoved {
                    device_id,
                    position: winit::dpi::PhysicalPosition::new(x, y),
                });
                self.window_event(event_loop, id, WindowEvent::MouseWheel {
                    device_id,
                    delta: MouseScrollDelta::LineDelta(0.0, amount),
                    phase: TouchPhase::Moved,
                });
            }
            horizon_client::AndroidInjectedInput::MouseMotion { dx, dy } => {
                // Android generally cannot lock/grab the cursor like a desktop window.
                // Treat a finger drag as a grabbed pointer while a world is active, so
                // Game::apply_mouse_look consumes MouseHandler's relative-delta buffer.
                if self.focused && self.screen == Screen::Playing && self.player.is_some() {
                    self.agent_pointer = true;
                    self.mouse.on_move(0.0, 0.0, dx, dy, true, true);
                    self.last_input = Instant::now();
                }
            }
            horizon_client::AndroidInjectedInput::KeyInput { key_code, pressed } => {
                // Activity.dispatchKeyEvent is not delivered to the NativeActivity's
                // Winit queue. Translate Android key codes at the native input boundary.
                let key = match key_code {
                    4 | 111 => Some(KeyCode::Escape), // BACK / ESCAPE
                    7 => Some(KeyCode::Digit0),
                    8 => Some(KeyCode::Digit1),
                    9 => Some(KeyCode::Digit2),
                    10 => Some(KeyCode::Digit3),
                    11 => Some(KeyCode::Digit4),
                    12 => Some(KeyCode::Digit5),
                    13 => Some(KeyCode::Digit6),
                    14 => Some(KeyCode::Digit7),
                    15 => Some(KeyCode::Digit8),
                    16 => Some(KeyCode::Digit9),
                    19 => Some(KeyCode::ArrowUp),
                    20 => Some(KeyCode::ArrowDown),
                    21 => Some(KeyCode::ArrowLeft),
                    22 => Some(KeyCode::ArrowRight),
                    29 => Some(KeyCode::KeyA), 30 => Some(KeyCode::KeyB),
                    31 => Some(KeyCode::KeyC), 32 => Some(KeyCode::KeyD),
                    33 => Some(KeyCode::KeyE), 34 => Some(KeyCode::KeyF),
                    35 => Some(KeyCode::KeyG), 36 => Some(KeyCode::KeyH),
                    37 => Some(KeyCode::KeyI), 38 => Some(KeyCode::KeyJ),
                    39 => Some(KeyCode::KeyK), 40 => Some(KeyCode::KeyL),
                    41 => Some(KeyCode::KeyM), 42 => Some(KeyCode::KeyN),
                    43 => Some(KeyCode::KeyO), 44 => Some(KeyCode::KeyP),
                    45 => Some(KeyCode::KeyQ), 46 => Some(KeyCode::KeyR),
                    47 => Some(KeyCode::KeyS), 48 => Some(KeyCode::KeyT),
                    49 => Some(KeyCode::KeyU), 50 => Some(KeyCode::KeyV),
                    51 => Some(KeyCode::KeyW), 52 => Some(KeyCode::KeyX),
                    53 => Some(KeyCode::KeyY), 54 => Some(KeyCode::KeyZ),
                    55 => Some(KeyCode::Comma), 56 => Some(KeyCode::Period),
                    57 => Some(KeyCode::AltLeft), 58 => Some(KeyCode::AltRight),
                    59 => Some(KeyCode::ShiftLeft), 60 => Some(KeyCode::ShiftRight),
                    61 => Some(KeyCode::Tab), 62 => Some(KeyCode::Space),
                    66 | 160 => Some(KeyCode::Enter), 67 => Some(KeyCode::Backspace),
                    68 => Some(KeyCode::Backquote), 69 => Some(KeyCode::Minus),
                    70 => Some(KeyCode::Equal), 71 => Some(KeyCode::BracketLeft),
                    72 => Some(KeyCode::BracketRight), 73 => Some(KeyCode::Backslash),
                    74 => Some(KeyCode::Semicolon), 75 => Some(KeyCode::Quote),
                    76 => Some(KeyCode::Slash), 81 => Some(KeyCode::NumpadAdd),
                    92 => Some(KeyCode::PageUp), 93 => Some(KeyCode::PageDown),
                    112 => Some(KeyCode::Delete), 113 => Some(KeyCode::ControlLeft),
                    114 => Some(KeyCode::ControlRight), 115 => Some(KeyCode::CapsLock),
                    122 => Some(KeyCode::Home), 123 => Some(KeyCode::End),
                    124 => Some(KeyCode::Insert),
                    131 => Some(KeyCode::F1), 132 => Some(KeyCode::F2),
                    133 => Some(KeyCode::F3), 134 => Some(KeyCode::F4),
                    135 => Some(KeyCode::F5), 136 => Some(KeyCode::F6),
                    137 => Some(KeyCode::F7), 138 => Some(KeyCode::F8),
                    139 => Some(KeyCode::F9), 140 => Some(KeyCode::F10),
                    141 => Some(KeyCode::F11), 142 => Some(KeyCode::F12),
                    _ => None,
                };
                if let Some(key) = key {
                    if pressed {
                        self.physical_keys.insert(key);
                    } else {
                        self.physical_keys.remove(&key);
                    }
                    self.last_input = Instant::now();
                    // Deliver unbound keys to Horizon's mod/extension hotkey hooks as
                    // well as translating vanilla key bindings (custom controls may use R, G, etc.).
                    self.extras_key(key, pressed, false);
                    if self.screen == Screen::Playing
                        && pressed
                        && horizon_client::keybinds::key_name(key)
                            .is_some_and(|name| self.settings.keys.get("key.smoothCamera") == name)
                    {
                        self.smooth_camera = !self.smooth_camera;
                    }
                    let result = match self.settings.keys.translate(key) {
                        Some(Ok(mapped_key)) => self.mapped_key(mapped_key, pressed, false),
                        Some(Err(button)) if self.screen == Screen::Playing => self.play_mouse(button, pressed),
                        _ => Ok(()),
                    };
                    if let Err(error) = result {
                        log::warn!("Horizon: Android touchscreen key input failed: {error}");
                    }
                }
            }
            horizon_client::AndroidInjectedInput::KeyboardText { text, submit } => {
                if self.title.is_some()
                    && !matches!(self.screen, Screen::Options | Screen::WorldOptions)
                {
                    let (Some(title), clipboard) =
                        (self.title.as_mut(), &mut self.clipboard)
                    else { return; };
                    if title.focused_field().is_some() {
                        if title.has_caret_field() {
                            title.field_shortcut(
                                horizon_client::clipboard::EditShortcut::SelectAll,
                                clipboard,
                            );
                            if text.is_empty() {
                                title.field_key(KeyCode::Backspace, false, false);
                            } else {
                                title.type_text(&text);
                            }
                        } else {
                            if let Some((_, value, _)) = title.focused_field() {
                                value.clear();
                            }
                            title.type_text(&text);
                        }
                        if submit {
                            title.confirm_edit();
                        }
                    }
                } else if self.screen == Screen::Playing
                    && self.player.is_some()
                    && self.sign_edit.is_none()
                    && self.book.is_none()
                {
                    if !text.trim().is_empty() {
                        self.open_chat("");
                        self.chat_type(&text);
                        if submit {
                            self.chat_key_code(KeyCode::Enter, None);
                        }
                    }
                } else {
                    self.pad_osk_output(horizon_client::osk::Output::Text(text.clone()));
                    if submit {
                        self.pad_osk_output(horizon_client::osk::Output::Enter);
                    }
                }
            }
            horizon_client::AndroidInjectedInput::BeginChatDraft => {
                if self.player.is_some()
                    && self.title.is_none()
                    && matches!(self.screen, Screen::Playing | Screen::Chat)
                    && self.sign_edit.is_none()
                    && self.book.is_none()
                {
                    if let Some(screen) = self.ui.chat_screen.as_ref() {
                        horizon_client::set_android_chat_open(true);
                        horizon_client::set_android_chat_draft(screen.value());
                    } else {
                        let draft = horizon_client::android_chat_draft();
                        self.open_chat(&draft);
                    }
                }
            }
            horizon_client::AndroidInjectedInput::ImeText { value, inserted, backspaces, submit } => {
                if self.title.is_some()
                    && !matches!(self.screen, Screen::Options | Screen::WorldOptions)
                {
                    let (Some(title), clipboard) =
                        (self.title.as_mut(), &mut self.clipboard)
                    else { return; };
                    if title.focused_field().is_some() {
                        if title.has_caret_field() {
                            title.field_shortcut(
                                horizon_client::clipboard::EditShortcut::SelectAll,
                                clipboard,
                            );
                            if value.is_empty() {
                                title.field_key(KeyCode::Backspace, false, false);
                            } else {
                                title.type_text(&value);
                            }
                        } else {
                            if let Some((_, current, _)) = title.focused_field() {
                                current.clear();
                            }
                            title.type_text(&value);
                        }
                        if submit {
                            title.confirm_edit();
                        }
                    }
                } else if self.player.is_some()
                    && matches!(self.screen, Screen::Playing | Screen::Chat)
                    && self.sign_edit.is_none()
                    && self.book.is_none()
                {
                    if self.ui.chat_screen.is_none() {
                        self.open_chat("");
                    }
                    let m = Measure(&self.gui.atlas.font);
                    let c = Commands {
                        dispatcher: chat_tree(&self.remote, &self.mods),
                        registries: &self.ui.registries,
                    };
                    if let Some(screen) = self.ui.chat_screen.as_mut() {
                        screen.suggest_command(&m, &c, &value);
                        horizon_client::set_android_chat_draft(screen.value());
                    }
                    if submit {
                        self.chat_key_code(KeyCode::Enter, None);
                    }
                } else {
                    for _ in 0..backspaces.min(4096) {
                        self.pad_osk_output(horizon_client::osk::Output::Backspace);
                    }
                    if !inserted.is_empty() {
                        self.pad_osk_output(horizon_client::osk::Output::Text(inserted.clone()));
                    }
                    if submit {
                        self.pad_osk_output(horizon_client::osk::Output::Enter);
                    }
                }
            }
        }
        horizon_client::set_android_world_active(self.player.is_some() && self.title.is_none());
        horizon_client::set_android_gameplay(
            self.player.is_some()
                && self.title.is_none()
                && self.screen == Screen::Playing
                && self.sign_edit.is_none()
                && self.book.is_none(),
        );
        horizon_client::set_android_chat_target(
            self.player.is_some()
                && self.title.is_none()
                && matches!(self.screen, Screen::Playing | Screen::Chat)
                && self.sign_edit.is_none()
                && self.book.is_none(),
        );
    }

"""
content = game.read_text(encoding="utf-8")
content = content[:idx] + content[idx:].replace(impl_start, injected_impl, 1)
idx = content.find("impl ApplicationHandler<horizon_client::AndroidInjectedInput> for Game<'_> {")
if idx < 0:
    raise SystemExit("Could not replace Game ApplicationHandler implementation")
guard = '''        if self.window.as_ref().is_none_or(|w| w.id() != id) {
            return;
        }
'''
gidx = content.find(guard, idx)
if gidx < 0:
    raise SystemExit("Could not find Game window event guard")
if content.find(guard, gidx + len(guard)) >= 0 and content.find(guard, gidx + len(guard)) < content.find("impl ApplicationHandler for App", idx):
    # Ensure we picked the first guard inside Game, not a later handler.
    pass
touch_handler = '''        if self.window.as_ref().is_none_or(|w| w.id() != id) {
            return;
        }

        // Android touch arrives as WindowEvent::Touch; Horizon screens consume
        // CursorMoved and MouseInput. Translate single-finger taps to left-clicks
        // and simultaneous two-finger taps to right-clicks.
        let touch_input = match &event {
            WindowEvent::Touch(touch) => Some((
                touch.id,
                touch.phase.clone(),
                touch.device_id.clone(),
                touch.location.clone(),
            )),
            _ => None,
        };
        if let Some((touch_id, phase, device_id, position)) = touch_input {
            let cursor_event = || WindowEvent::CursorMoved {
                device_id: device_id.clone(),
                position: position.clone(),
            };
            let click = |button: MouseButton, state: ElementState| WindowEvent::MouseInput {
                device_id: device_id.clone(),
                state,
                button,
            };
            match phase {
                TouchPhase::Started => {
                    if self.touch_mouse.is_none() {
                        self.touch_mouse = Some(touch_id);
                        self.touch_auxiliary = None;
                        self.touch_is_right = false;
                        self.window_event(event_loop, id, cursor_event());
                    } else if self.touch_mouse != Some(touch_id) && self.touch_auxiliary.is_none() {
                        self.touch_auxiliary = Some(touch_id);
                        self.touch_is_right = true;
                    }
                }
                TouchPhase::Moved if self.touch_mouse == Some(touch_id) => {
                    self.window_event(event_loop, id, cursor_event());
                }
                TouchPhase::Ended
                    if self.touch_mouse == Some(touch_id)
                        || self.touch_auxiliary == Some(touch_id) =>
                {
                    self.window_event(event_loop, id, cursor_event());
                    let button = if self.touch_is_right { MouseButton::Right } else { MouseButton::Left };
                    self.window_event(event_loop, id, click(button, ElementState::Pressed));
                    self.window_event(event_loop, id, click(button, ElementState::Released));
                    self.touch_mouse = None;
                    self.touch_auxiliary = None;
                    self.touch_is_right = false;
                }
                TouchPhase::Cancelled
                    if self.touch_mouse == Some(touch_id)
                        || self.touch_auxiliary == Some(touch_id) =>
                {
                    self.touch_mouse = None;
                    self.touch_auxiliary = None;
                    self.touch_is_right = false;
                }
                _ => {}
            }
            return;
        }
'''
content = content[:gidx] + content[gidx:].replace(guard, touch_handler, 1)
capture_condition = 'if pressed && !self.captured {'
if content.count(capture_condition) != 1:
    raise SystemExit(f"Expected one pointer-capture guard, found {content.count(capture_condition)}")
content = content.replace(
    capture_condition,
    'if pressed && !self.captured && !self.agent_pointer {',
    1,
)
game.write_text(content, encoding="utf-8");

# Use the game's cursor capture request as the virtual cursor visibility state.
replace_once(game,
    """        if capture && !self.focused {
            return;
        }""",
    """        if capture && !self.focused {
            return;
        }
        #[cfg(target_os = "android")]
        horizon_client::set_android_gameplay(
            self.player.is_some()
                && self.title.is_none()
                && self.screen == Screen::Playing
                && self.sign_edit.is_none()
                && self.book.is_none(),
        );""")

# Publish whether Horizon is in the active world or currently displaying a menu.
# Java polls this lightweight atomic to hide its virtual cursor during gameplay.
replace_once(game,
    """        if let Err(e) = result {
            self.error = Some(e);
            event_loop.exit();
        }
    }""",
    """        if let Err(e) = result {
            self.error = Some(e);
            event_loop.exit();
        }
        horizon_client::set_android_world_active(self.player.is_some() && self.title.is_none());
        horizon_client::set_android_gameplay(
            self.player.is_some()
                && self.title.is_none()
                && self.screen == Screen::Playing
                && self.sign_edit.is_none()
                && self.book.is_none(),
        );
        horizon_client::set_android_chat_target(
            self.player.is_some()
                && self.title.is_none()
                && matches!(self.screen, Screen::Playing | Screen::Chat)
                && self.sign_edit.is_none()
                && self.book.is_none(),
        );
    }""")

replace_once(game,
    "fn run_event_loop(mut game: Game<'_>) -> Result<()> {\n    EventLoop::new()?.run_app(&mut game)?;",
    """fn new_event_loop<T: 'static + Send>() -> Result<EventLoop<T>> {
    #[cfg(target_os = "android")]
    {
        use winit::platform::android::EventLoopBuilderExtAndroid;
        let mut builder = EventLoop::<T>::with_user_event();
        builder.with_android_app(crate::android_app()?);
        Ok(builder.build()?)
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(EventLoop::<T>::new()?)
    }
}

fn run_event_loop(mut game: Game<'_>) -> Result<()> {
    let event_loop = new_event_loop::<horizon_client::AndroidInjectedInput>()?;
    horizon_client::set_android_event_loop_proxy(event_loop.create_proxy());
    event_loop.run_app(&mut game)?;""")
replace_once(game,
    'impl ApplicationHandler for App {\n    fn resumed(&mut self, event_loop: &ActiveEventLoop) {',
    '''impl ApplicationHandler<horizon_client::AndroidInjectedInput> for App {
    fn user_event(&mut self, event_loop: &ActiveEventLoop, event: horizon_client::AndroidInjectedInput) {
        match (self, event) {
            (App::Loading(loading), horizon_client::AndroidInjectedInput::KeyboardText { text, submit }) => {
                loading.android_keyboard_text(&text, submit);
            }
            (App::Loading(loading), horizon_client::AndroidInjectedInput::ImeText { value, submit, .. }) => {
                loading.android_keyboard_text(&value, submit);
            }
            (App::Ready(game), event) => game.user_event(event_loop, event),
            (App::Loading(_), _) => {}
        }
    }
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {''')

replace_once(game,
    '    EventLoop::new()?.run_app(&mut app)?;',
    '''    let event_loop = new_event_loop::<horizon_client::AndroidInjectedInput>()?;
    horizon_client::set_android_event_loop_proxy(event_loop.create_proxy());
    event_loop.run_app(&mut app)?;''')

replace_once(workspace_cargo, '"crates/horizon-client",',
             '"crates/horizon-client", "crates/mobilehorizon-android",')

dst = root / "crates/mobilehorizon-android"
(dst / "src").mkdir(parents=True, exist_ok=True)
shutil.copyfile(repo_root / "android-native/Cargo.toml", dst / "Cargo.toml")
shutil.copyfile(repo_root / "android-native/src/lib.rs", dst / "src/lib.rs")

print("Patched Horizon Android touch input and created the NativeActivity cdylib target.")
