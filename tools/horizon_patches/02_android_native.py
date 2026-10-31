# Ordered patch step. See README.md in this directory for scope and order.
audio_source = audio.read_text(encoding="utf-8")
audio_start_marker = '#[cfg(feature = "audio")]' + chr(10) + 'fn open_device('
audio_end_markers = (
    chr(10) + "/// The name of the system's default output device.",
    chr(10) + '#[cfg(feature = "audio")]' + chr(10) + 'fn build_stream<',
)
audio_start = audio_source.find(audio_start_marker)
audio_end_marker = next(
    (marker for marker in audio_end_markers
     if audio_source.find(marker, audio_start + len(audio_start_marker)) >= 0),
    None,
)
audio_end = (-1 if audio_end_marker is None else
             audio_source.find(audio_end_marker, audio_start + len(audio_start_marker)))
if (audio_start < 0 or audio_end < 0
        or audio_source.find(audio_start_marker, audio_start + 1) >= 0):
    raise SystemExit(f"{audio}: expected exactly one supported open_device function")
android_open_device = """#[cfg(feature = "audio")]
fn open_device(
    device: &cpal::Device,
    mixer: Arc<Mutex<Mixer>>,
) -> Result<(cpal::Stream, cpal::SupportedStreamConfig)> {
    use cpal::SampleFormat as F;

    // Android may reject the preferred config: try supported formats and sample rates.
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
                    if let Ok(mut state) = mixer.lock() {
                        state.device_name = device.name().unwrap_or_default();
                        state.device_lost = false;
                    }
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
}"""
if audio_end_marker == audio_end_markers[1]:
    # The external java-bridge fork's Mixer has no desktop device-name/lost fields.
    android_open_device = android_open_device.replace(
        """                    if let Ok(mut state) = mixer.lock() {
                        state.device_name = device.name().unwrap_or_default();
                        state.device_lost = false;
                    }
""",
        "",
    )
audio.write_text(audio_source[:audio_start] + android_open_device + audio_source[audio_end:], encoding="utf-8")

replace_once(client_cargo, 'winit = "0.30.13"',
             'winit = { version = "0.30.13", features = ["android-native-activity"] }')

# Keep the desktop clipboard backend optional so the Android build doesn't compile arboard.
# Horizon v0.2.92 moved the clipboard dependency and its feature into horizon-client-input.
clipboard_dependency = 'arboard = { version = "3.6.1", default-features = false, features = ["wayland-data-control"] }'
client_manifest = client_cargo.read_text(encoding="utf-8")
uses_input_crate = 'horizon-client-input = { path = "../horizon-client-input" }' in client_manifest
clipboard_manifest = client_input_cargo if uses_input_crate else client_cargo
replace_once(clipboard_manifest, clipboard_dependency,
             clipboard_dependency[:-1] + ', optional = true }')
replace_once(client_cargo,
    'default = ["audio", "controllers"]',
    'default = ["audio", "controllers", "system-clipboard"]')
if uses_input_crate:
    replace_once(client_input_cargo,
        'controllers = ["dep:horizon-dualsense"]',
        'controllers = ["dep:horizon-dualsense"]\nsystem-clipboard = ["dep:arboard"]')
    replace_once(client_cargo,
        'controllers = ["dep:gilrs", "dep:horizon-dualsense", "horizon-client-input/controllers"]',
        'controllers = ["dep:gilrs", "dep:horizon-dualsense", "horizon-client-input/controllers"]\nsystem-clipboard = ["horizon-client-input/system-clipboard"]')
else:
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

# winit's modifier_supplement extension exists only on desktop backends.
# Android uses KeyEvent.logical_key directly; desktop keeps the unmodified-layout API.
keybinds_old = """pub fn learn_layout(event: &winit::event::KeyEvent) {
    use winit::keyboard::{Key, PhysicalKey};
    use winit::platform::modifier_supplement::KeyEventExtModifierSupplement;
    let PhysicalKey::Code(code) = event.physical_key else {
        return;
    };
    let Some(name) = key_name(code) else {
        return;
    };
    let learn = horizon_client_core::key_display::learn_layout;
    match event.key_without_modifiers() {
        Key::Character(text) => learn(name, &text),
        Key::Dead(Some(c)) => learn(name, c.encode_utf8(&mut [0; 4])),
        _ => {}
    }
}"""
keybinds_new = """pub fn learn_layout(event: &winit::event::KeyEvent) {
    use winit::keyboard::{Key, PhysicalKey};
    let PhysicalKey::Code(code) = event.physical_key else {
        return;
    };
    let Some(name) = key_name(code) else {
        return;
    };
    let learn = horizon_client_core::key_display::learn_layout;
    #[cfg(target_os = "android")]
    let logical_key = event.logical_key.clone();
    #[cfg(not(target_os = "android"))]
    let logical_key = {
        use winit::platform::modifier_supplement::KeyEventExtModifierSupplement;
        event.key_without_modifiers()
    };
    match logical_key {
        Key::Character(text) => learn(name, &text),
        Key::Dead(Some(c)) => learn(name, c.encode_utf8(&mut [0; 4])),
        _ => {}
    }
}"""
# This compatibility edit is needed only on revisions that still track the
# physical-key -> unmodified-layout mapping in this file. The external java-bridge
# fork has no learn_layout function or desktop modifier-supplement import here.
keybinds_source = keybinds.read_text(encoding="utf-8")
if keybinds_old in keybinds_source:
    replace_once(keybinds, keybinds_old, keybinds_new)
elif "KeyEventExtModifierSupplement" in keybinds_source or "key_without_modifiers()" in keybinds_source:
    raise SystemExit(f"{keybinds}: desktop modifier-supplement code changed; review Android key layout handling")
else:
    print(f"{keybinds}: no desktop learn_layout hook to adapt; skipping that optional edit")

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
            inline_profiles: Vec::new(),
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
            inline_profiles: Vec::new(),
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
chat_source = chat.read_text(encoding="utf-8")
if chat_source.count("fn chat_tree<'a>(") == 1:
    replace_once(chat, "fn chat_tree<'a>(", "pub(super) fn chat_tree<'a>(")
elif chat_source.count("pub(super) fn chat_tree(") == 1 or chat_source.count("pub(super) fn chat_tree<'a>(") == 1:
    # Newer Nightlies already expose the helper to the parent game module.
    pass
else:
    raise SystemExit(f"{chat}: expected one supported chat_tree visibility declaration")
replace_once(chat,
    """        self.ui.chat_screen = Some(screen);
        self.ui.chat_opened = Some(std::time::Instant::now());""",
    """        self.ui.chat_screen = Some(screen);
        horizon_client::set_android_chat_open(true);
        horizon_client::set_android_chat_draft(initial);
        self.ui.chat_opened = Some(std::time::Instant::now());""")
# Mirror Android IME visibility at the actual chat close transition. Newer
# Nightlies keep drafts and pass a ChatExit reason, so insert at the clear point
# inside close_chat rather than replacing the method's implementation.
chat_content = chat.read_text(encoding="utf-8")
close_start = chat_content.find("pub(super) fn close_chat(")
if close_start < 0:
    close_start = chat_content.find("fn close_chat(")
close_end = chat_content.find("\n    /// ", close_start + 1)
if close_start < 0 or close_end < 0:
    raise SystemExit(f"{chat}: could not locate one supported close_chat method")
close_block = chat_content[close_start:close_end]
clear_chat_screen = "        self.ui.chat_screen = None;"
if close_block.count(clear_chat_screen) != 1:
    if "set_android_chat_open(false)" not in close_block:
        raise SystemExit(f"{chat}: expected one chat_screen clear inside close_chat")
elif "set_android_chat_open(false)" not in close_block:
    close_block = close_block.replace(
        clear_chat_screen,
        "        horizon_client::set_android_chat_open(false);\n" + clear_chat_screen,
        1,
    )
    chat_content = chat_content[:close_start] + close_block + chat_content[close_end:]
    chat.write_text(chat_content, encoding="utf-8")

# Keep the Android chat draft synchronized after native keyboard input. The
# current Nightly uses types_text() rather than the older !ctrl condition.
chat_content = chat.read_text(encoding="utf-8")
old_typed_text = """                if let Some(text) = typed_text
                    && !ctrl
                {
                    screen.type_text(&m, &c, text);
                }
                self.ui.chat_screen = Some(screen);"""
new_typed_text = """                if let Some(text) = typed_text
                    && !ctrl
                {
                    screen.type_text(&m, &c, text);
                }
                horizon_client::set_android_chat_draft(screen.value());
                self.ui.chat_screen = Some(screen);"""
current_typed_text = """                if let Some(text) = typed_text
                    && self.types_text()
                {
                    screen.type_text(&m, &c, text);
                }
                self.ui.chat_screen = Some(screen);"""
current_typed_text_new = """                if let Some(text) = typed_text
                    && self.types_text()
                {
                    screen.type_text(&m, &c, text);
                }
                horizon_client::set_android_chat_draft(screen.value());
                self.ui.chat_screen = Some(screen);"""
if chat_content.count(old_typed_text) == 1:
    replace_once(chat, old_typed_text, new_typed_text)
elif chat_content.count(current_typed_text) == 1:
    replace_once(chat, current_typed_text, current_typed_text_new)
elif chat_content.count(current_typed_text_new) == 1:
    pass
else:
    already_patched = chat_content.count("horizon_client::set_android_chat_draft(screen.value());") >= 2
    if not already_patched:
        raise SystemExit(f"{chat}: unsupported typed chat text layout")

old_chat_type = """        if let Some(screen) = self.ui.chat_screen.as_mut() {
            screen.type_text(&m, &c, text);
        }
    }
    /// ChatScreen.handleChatInput:"""
new_chat_type = """        if let Some(screen) = self.ui.chat_screen.as_mut() {
            screen.type_text(&m, &c, text);
            horizon_client::set_android_chat_draft(screen.value());
        }
    }
    /// ChatScreen.handleChatInput:"""
if chat_content.count(old_chat_type) == 1:
    replace_once(chat, old_chat_type, new_chat_type)
elif chat_content.count("horizon_client::set_android_chat_draft(screen.value());") >= 3:
    pass
else:
    raise SystemExit(f"{chat}: unsupported chat_type input method layout")

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
# v0.2.92 added server-pack/dialog capture guards; preserve them and inject the Android
# gameplay visibility signal after the complete guard instead of replacing upstream logic.
capture_guard_old = """        if capture && !self.focused {
            return;
        }"""
capture_guard_current = """        if capture && (!self.focused || self.server_packs.prompt.is_some() || self.dialog_open()) {
            return;
        }"""
capture_android_hook = """        #[cfg(target_os = "android")]
        horizon_client::set_android_gameplay(
            self.player.is_some()
                && self.title.is_none()
                && self.screen == Screen::Playing
                && self.sign_edit.is_none()
                && self.book.is_none(),
        );"""
game_content = game.read_text(encoding="utf-8")
if game_content.count(capture_guard_old) == 1:
    replace_once(game, capture_guard_old, capture_guard_old + "\n" + capture_android_hook)
elif game_content.count(capture_guard_current) == 1:
    replace_once(game, capture_guard_current, capture_guard_current + "\n" + capture_android_hook)
else:
    raise SystemExit(
        f"{game}: expected one recognized cursor-capture guard, found "
        f"old={game_content.count(capture_guard_old)}, current={game_content.count(capture_guard_current)}"
    )

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

event_loop_factory = """fn new_event_loop<T: 'static + Send>() -> Result<EventLoop<T>> {
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

"""
legacy_event_loop = "fn run_event_loop(mut game: Game<'_>) -> Result<()> {\n    EventLoop::new()?.run_app(&mut game)?;"
current_event_loop = """fn run_event_loop(mut game: Game<'_>) -> Result<()> {
    let result = EventLoop::new()?.run_app(&mut game);
    leave_fullscreen_after(&result, &mut game.settings);
    result?;"""
game_source = game.read_text(encoding="utf-8")
if game_source.count(current_event_loop) == 1:
    replace_once(game, current_event_loop,
                 event_loop_factory + """fn run_event_loop(mut game: Game<'_>) -> Result<()> {
    let event_loop = new_event_loop::<horizon_client::AndroidInjectedInput>()?;
    horizon_client::set_android_event_loop_proxy(event_loop.create_proxy());
    let result = event_loop.run_app(&mut game);
    leave_fullscreen_after(&result, &mut game.settings);
    result?;""")
elif game_source.count(legacy_event_loop) == 1:
    replace_once(game, legacy_event_loop,
                 event_loop_factory + """fn run_event_loop(mut game: Game<'_>) -> Result<()> {
    let event_loop = new_event_loop::<horizon_client::AndroidInjectedInput>()?;
    horizon_client::set_android_event_loop_proxy(event_loop.create_proxy());
    event_loop.run_app(&mut game)?;""")
else:
    raise SystemExit(
        f"{game}: expected exactly one supported run_event_loop anchor "
        f"(current={game_source.count(current_event_loop)}, legacy={game_source.count(legacy_event_loop)})"
    )
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

legacy_app_loop = '    EventLoop::new()?.run_app(&mut app)?;'
current_app_loop = '    let result = EventLoop::new()?.run_app(&mut app);'
app_source = game.read_text(encoding="utf-8")
if app_source.count(current_app_loop) == 1:
    replace_once(game, current_app_loop,
        """    let event_loop = new_event_loop::<horizon_client::AndroidInjectedInput>()?;
    horizon_client::set_android_event_loop_proxy(event_loop.create_proxy());
    let result = event_loop.run_app(&mut app);""")
elif app_source.count(legacy_app_loop) == 1:
    replace_once(game, legacy_app_loop,
        '''    let event_loop = new_event_loop::<horizon_client::AndroidInjectedInput>()?;
    horizon_client::set_android_event_loop_proxy(event_loop.create_proxy());
    event_loop.run_app(&mut app)?;''')
else:
    raise SystemExit(
        f"{game}: expected exactly one supported main App event-loop anchor "
        f"(current={app_source.count(current_app_loop)}, legacy={app_source.count(legacy_app_loop)})"
    )

replace_once(workspace_cargo, '"crates/horizon-client",',
             '"crates/horizon-client", "crates/mobilehorizon-android",')

# The chat completion dispatcher changed shape in newer Horizon versions.
# Match the Android IME call to the actual upstream helper signature.
chat_source = chat.read_text(encoding="utf-8")
chat_signature_start = chat_source.find("fn chat_tree(")
if chat_signature_start < 0:
    chat_signature_start = chat_source.find("fn chat_tree<'a>(")
chat_signature_end = chat_source.find(") ->", chat_signature_start)
if chat_signature_start < 0 or chat_signature_end < 0:
    raise SystemExit(f"{chat}: could not resolve chat_tree signature")
chat_signature = chat_source[chat_signature_start:chat_signature_end]
game_source = game.read_text(encoding="utf-8")
old_chat_dispatcher = "dispatcher: chat_tree(&self.remote, &self.mods),"
new_chat_dispatcher = "dispatcher: chat_tree(&self.remote),"
if "mods:" in chat_signature:
    if game_source.count(old_chat_dispatcher) != 1:
        if game_source.count(new_chat_dispatcher) != 1:
            raise SystemExit(f"{game}: expected the Android IME chat dispatcher call")
elif game_source.count(old_chat_dispatcher) == 1:
    replace_once(game, old_chat_dispatcher, new_chat_dispatcher)
elif game_source.count(new_chat_dispatcher) == 1:
    pass
else:
    raise SystemExit(f"{game}: unsupported chat_tree call shape for current upstream signature")

dst = root / "crates/mobilehorizon-android"
(dst / "src").mkdir(parents=True, exist_ok=True)
shutil.copyfile(repo_root / "android-native/Cargo.toml", dst / "Cargo.toml")
shutil.copyfile(repo_root / "android-native/src/lib.rs", dst / "src/lib.rs")

print("Patched Horizon Android touch input and created the NativeActivity cdylib target.")


# The regular Horizon repository has no Java Bridge sources. Apply these
# additional edits only in the workflow that explicitly checks out iaxivers/horizon.
if os.environ.get("HORIZON_ANDROID_PATCH_APPLY_JAVA_BRIDGE") == "1":
    # The upstream bridge previously logged a Fabric preparation error and then started a
    # bare JVM (fabric=None). That made the game open while all Fabric mods were absent.
    # Make Fabric startup fail-closed and persist a stage-by-stage diagnostic trail.
    java_mods_source_path = root / "crates/horizon-client/src/game/java_mods.rs"
    jvm_source_path = root / "crates/horizon-jvm/src/lib.rs"
    fabric_boot_path = root / "crates/horizon-jvm/java/dev/horizonlab/bridge/fabric/FabricBoot.java"
    fabric_test_path = root / "crates/horizon-jvm/tests/fabric.rs"

    for required_bridge_source in (java_mods_source_path, jvm_source_path, fabric_boot_path, fabric_test_path):
        if not required_bridge_source.is_file():
            raise SystemExit(f"Missing Java Bridge initialization source: {required_bridge_source}")

    java_mods_source = java_mods_source_path.read_text(encoding="utf-8")
    java_mods_import = "use std::path::{Path, PathBuf};"
    java_mods_import_new = """use std::{
        fs::OpenOptions,
        io::Write,
        path::{Path, PathBuf},
        time::{SystemTime, UNIX_EPOCH},
    };"""
    if java_mods_source.count(java_mods_import) != 1:
        raise SystemExit(f"{java_mods_source_path}: expected one standard path import")
    java_mods_source = java_mods_source.replace(java_mods_import, java_mods_import_new, 1)

    probe_anchor = """const PROBE_CLASSES: &[&str] = &[
        "net/minecraft/world/level/block/Blocks",
        "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents",
    ];"""
    probe_with_logger = probe_anchor + """

    /// Logs to Android Logcat and, when configured by NativeGameActivity, to a persistent file.
    fn bridge_log(level: &str, message: &str) {
        match level {
            "ERROR" => log::error!("[Java Bridge] {message}"),
            "WARN" => log::warn!("[Java Bridge] {message}"),
            _ => log::info!("[Java Bridge] {message}"),
        }
        let Some(path) = std::env::var_os("HORIZON_JAVA_BRIDGE_LOG_FILE") else {
            return;
        };
        let path = PathBuf::from(path);
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_secs())
            .unwrap_or_default();
        if let Ok(mut output) = OpenOptions::new().create(true).append(true).open(&path) {
            let _ = writeln!(output, "[{timestamp}][{level}][JavaBridge] {message}");
        }
    }"""
    if java_mods_source.count(probe_anchor) != 1:
        raise SystemExit(f"{java_mods_source_path}: PROBE_CLASSES anchor changed")
    java_mods_source = java_mods_source.replace(probe_anchor, probe_with_logger, 1)

    empty_jars_block = """        if jars.is_empty() {
                return;
            }"""
    logged_empty_jars_block = """        if jars.is_empty() {
                bridge_log("INFO", "Nenhum JAR ativo em mods-java; o JVM/Fabric não será iniciado para esta instância.");
                return;
            }
            bridge_log(
                "INFO",
                &format!("Descobertos {} JAR(s) em mods-java: {}", jars.len(), dir.display()),
            );"""
    if java_mods_source.count(empty_jars_block) != 1:
        raise SystemExit(f"{java_mods_source_path}: active-JAR guard changed")
    java_mods_source = java_mods_source.replace(empty_jars_block, logged_empty_jars_block, 1)

    start_marker = "fn start_once(root: &Path, dir: &Path, jars: Vec<PathBuf>, side: horizon_jvm::Side) -> bool {"
    end_marker = "/// Fabric's part of the config: the prepared game jar and libraries, and Fabric API unless"
    start_at = java_mods_source.find(start_marker)
    end_at = java_mods_source.find(end_marker, start_at + len(start_marker))
    if start_at < 0 or end_at < 0 or java_mods_source.find(start_marker, start_at + 1) >= 0:
        raise SystemExit(f"{java_mods_source_path}: unable to isolate start_once() for fail-closed patch")
    new_start_once = """fn start_once(root: &Path, dir: &Path, jars: Vec<PathBuf>, side: horizon_jvm::Side) -> bool {
        if let Some(bridge) = horizon_jvm::get() {
            if !bridge.fabric() {
                bridge_log("ERROR", "Já existe uma JVM sem Fabric neste processo; não é seguro reiniciá-la.");
                return false;
            }
            bridge_log("INFO", "JVM/Fabric já inicializados neste processo; reutilizando a instância ativa.");
            return true;
        }

        // JNI permits one JVM per process: a failed initialization cannot be safely retried.
        static TRIED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
        if TRIED.swap(true, std::sync::atomic::Ordering::SeqCst) {
            bridge_log("ERROR", "A inicialização Java já foi tentada e falhou; uma segunda JVM não é permitida.");
            return false;
        }

        let started = std::time::Instant::now();
        let work = root.join(".local/bridge");
        bridge_log(
            "INFO",
            &format!(
                "Etapa 1/7: preparando Fabric Loader; lado={side:?}, mods={}, diretório={}",
                jars.len(),
                dir.display()
            ),
        );

        // Never turn a Fabric preparation error into a successful bare-JVM startup.
        let fabric_config = match fabric(root, dir, &work, &jars, side) {
            Ok(fabric) => fabric,
            Err(error) => {
                bridge_log("ERROR", &format!("Etapa 1/7 falhou ao preparar Fabric: {error}"));
                return false;
            }
        };
        if !horizon_jvm::platform_embedded() {
            bridge_log(
                "ERROR",
                "O runtime não contém horizon-platform.jar válido; os eventos e recursos de mods não podem ser integrados.",
            );
            return false;
        }
        bridge_log(
            "INFO",
            &format!(
                "Etapa 2/7: dados do Minecraft prontos; versão={}, cliente={}, bibliotecas={}, mods adicionais={}, plataforma embarcada=sim",
                fabric_config.version,
                fabric_config.game_jar.display(),
                fabric_config.libraries.len(),
                fabric_config.add_mods.len()
            ),
        );

        let home = match horizon_jvm::find_java_home() {
            Ok(home) => home,
            Err(error) => {
                bridge_log("ERROR", &format!("Etapa 3/7 falhou ao localizar Java 25: {error}"));
                return false;
            }
        };
        let libjvm = match horizon_jvm::libjvm(&home) {
            Ok(library) => library,
            Err(error) => {
                bridge_log("ERROR", &format!("Etapa 3/7 falhou ao localizar libjvm: {error}"));
                return false;
            }
        };
        bridge_log(
            "INFO",
            &format!(
                "Etapa 3/7: Java home={}, libjvm={}, carregando dependências do Fabric em {}",
                home.display(),
                libjvm.display(),
                work.display()
            ),
        );

        let config = horizon_jvm::Config {
            home,
            work: work.clone(),
            jars: jars.clone(),
            class_path: Vec::new(),
            fabric: Some(fabric_config),
        };
        bridge_log("INFO", "Etapa 4/7: criando a JVM por JNI e inicializando Knot/Fabric Loader.");
        let bridge = match horizon_jvm::start(&config) {
            Ok(bridge) => bridge,
            Err(error) => {
                bridge_log("ERROR", &format!("Etapa 4/7 falhou ao criar JVM/inicializar Fabric: {error}"));
                return false;
            }
        };
        if !bridge.fabric() {
            bridge_log(
                "ERROR",
                "A chamada de inicialização retornou uma JVM sem Fabric; abortando a integração de mods.",
            );
            return false;
        }

        let mods: Vec<&str> = bridge
            .mods()
            .iter()
            .map(String::as_str)
            .filter(|mod_id| !mod_id.starts_with("fabric-") || mod_id.starts_with("fabric-api "))
            .collect();
        bridge_log(
            "INFO",
            &format!(
                "Etapa 5/7: Fabric Loader ativo; JVM={}, Java={}, {} mod(s): {}",
                bridge.fabric(),
                bridge.java_version(),
                bridge.mods().len(),
                mods.join(", ")
            ),
        );
        if !bridge.mods().iter().any(|mod_id| mod_id.starts_with("fabric-api ")) {
            bridge_log(
                "ERROR",
                "Fabric API não aparece na lista de mods carregados; a inicialização será considerada incompleta.",
            );
            return false;
        }

        bridge_log("INFO", "Etapa 6/7: verificando classes essenciais do Minecraft e Fabric API.");
        for class in PROBE_CLASSES {
            match bridge.check_class(class) {
                Ok(()) => bridge_log("INFO", &format!("Classe essencial carregada: {class}")),
                Err(error) => {
                    bridge_log(
                        "ERROR",
                        &format!("Classe essencial ausente ({class}); Fabric não será considerado pronto: {error}"),
                    );
                    return false;
                }
            }
        }

        bridge_log(
            "INFO",
            &format!(
                "Etapa 7/7: Java Bridge inicializado com sucesso em {} ms; {} JAR(s) ativos em {}.",
                started.elapsed().as_millis(),
                jars.len(),
                dir.display()
            ),
        );
        true
    }

    """
    java_mods_source = (
        java_mods_source[:start_at]
        + new_start_once
        + java_mods_source[end_at:]
    )

    # Log the pinned Fabric API dependency and the exact game classpath before JVM startup.
    fabric_body_marker = """    let add_mods = if own_fabric_api {
            Vec::new()
        } else {
            vec![horizon_jvm::libs::ensure_fabric_api(
                &work.join("fabric-api"),
            )?]
        };
        Ok(horizon_jvm::Fabric {
            game_jar: horizon_jvm::facade::game_jar(root)?,
            libraries: horizon_jvm::facade::game_libraries(root)?,"""
    fabric_body_new = """    let add_mods = if own_fabric_api {
            bridge_log("WARN", "Um Fabric API fornecido pelo usuário foi detectado em mods-java; usando essa cópia.");
            Vec::new()
        } else {
            let api_dir = work.join("fabric-api");
            bridge_log("INFO", &format!("Baixando/verificando Fabric API fixado em {}", api_dir.display()));
            let api = horizon_jvm::libs::ensure_fabric_api(&api_dir)?;
            bridge_log("INFO", &format!("Fabric API verificado: {}", api.display()));
            vec![api]
        };
        let game_jar = horizon_jvm::facade::game_jar(root)?;
        let libraries = horizon_jvm::facade::game_libraries(root)?;
        let version = game_version(root)?;
        bridge_log(
            "INFO",
            &format!(
                "Classpath do jogo validado: versão={}, client.jar={}, {} biblioteca(s), side={side:?}",
                version,
                game_jar.display(),
                libraries.len()
            ),
        );
        Ok(horizon_jvm::Fabric {
            game_jar,
            libraries,"""
    if java_mods_source.count(fabric_body_marker) != 1:
        raise SystemExit(f"{java_mods_source_path}: Fabric configuration anchor changed")
    java_mods_source = java_mods_source.replace(fabric_body_marker, fabric_body_new, 1)
    java_mods_source = java_mods_source.replace(
        "        version: game_version(root)?,",
        "        version,",
        1,
    )
    java_mods_source_path.write_text(java_mods_source, encoding="utf-8")

    # Persist JVM/Fabric's Java-side messages too (Knot, Mixin, game bootstrap, entrypoints).
    jvm_source = jvm_source_path.read_text(encoding="utf-8")
    emit_old = """fn emit(line: &str) {
        match *SINK.lock().unwrap_or_else(|e| e.into_inner()) {
            Some(sink) => sink(line),
            None => log::info!("[java] {line}"),
        }
    }"""
    emit_new = """fn emit(line: &str) {
        use std::io::Write as _;
        if let Some(path) = std::env::var_os("HORIZON_JAVA_BRIDGE_LOG_FILE") {
            let path = PathBuf::from(path);
            if let Some(parent) = path.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            if let Ok(mut output) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
                let _ = writeln!(output, "[JVM/Fabric] {line}");
            }
        }
        match *SINK.lock().unwrap_or_else(|e| e.into_inner()) {
            Some(sink) => sink(line),
            None => log::info!("[java] {line}"),
        }
    }"""
    if jvm_source.count(emit_old) != 1:
        raise SystemExit(f"{jvm_source_path}: Java log emitter anchor changed")
    jvm_source = jvm_source.replace(emit_old, emit_new, 1)

    platform_anchor = """const PLATFORM_JAR: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/horizon-platform.jar"));"""
    platform_export = platform_anchor + """

    /// Whether this runtime embeds the game-specific platform mod required for game events/content.
    pub fn platform_embedded() -> bool {
        PLATFORM_JAR.len() > 4
    }"""
    if jvm_source.count(platform_anchor) != 1:
        raise SystemExit(f"{jvm_source_path}: embedded platform JAR anchor changed")
    jvm_source = jvm_source.replace(platform_anchor, platform_export, 1)
    jvm_source_path.write_text(jvm_source, encoding="utf-8")

    # Make the actual FabricBoot lifecycle visible in the same persistent log file.
    fabric_boot_source = fabric_boot_path.read_text(encoding="utf-8")
    fabric_boot_replacements = [
        (
            """        Knot knot = new Knot(env);
            gameLoader = knot.init(new String[0]);
            FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
            bootstrapGame();""",
            """        Natives.log("Java Bridge: initializing Knot, Fabric Loader and the Mixin transformer");
            Knot knot = new Knot(env);
            gameLoader = knot.init(new String[0]);
            Natives.log("Java Bridge: Knot initialized; Fabric Loader/Mixin class transformation is active");
            FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
            Natives.log("Java Bridge: bootstrapping Minecraft classes and registries");
            bootstrapGame();""",
        ),
        (
            """        try {
                loader.invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);""",
            """        Natives.log("Java Bridge: running Fabric main entrypoints");
            try {
                loader.invokeEntrypoints("main", ModInitializer.class, ModInitializer::onInitialize);""",
        ),
        (
            """        if (env == EnvType.CLIENT) {
                // Every client entrypoint runs, then Fabric throws what failed:""",
            """        if (env == EnvType.CLIENT) {
                Natives.log("Java Bridge: running Fabric client entrypoints");
                // Every client entrypoint runs, then Fabric throws what failed:""",
        ),
        (
            """        freezeRegistries();
            List<String> mods = new ArrayList<>();""",
            """        Natives.log("Java Bridge: finalizing registries after mod entrypoints");
            freezeRegistries();
            List<String> mods = new ArrayList<>();""",
        ),
    ]
    for old, new in fabric_boot_replacements:
        if fabric_boot_source.count(old) != 1:
            raise SystemExit(f"{fabric_boot_path}: FabricBoot lifecycle anchor changed: {old[:75]!r}")
        fabric_boot_source = fabric_boot_source.replace(old, new, 1)
    fabric_boot_path.write_text(fabric_boot_source, encoding="utf-8")

    # A host-side CI test must reject a runtime compiled without horizon-platform.jar.
    fabric_test_source = fabric_test_path.read_text(encoding="utf-8")
    test_anchor = """fn fabric_loads_a_mod_runs_its_entrypoints_and_applies_its_mixin() {
        let Some(home) = java_home() else {"""
    test_replacement = """fn fabric_loads_a_mod_runs_its_entrypoints_and_applies_its_mixin() {
        assert!(
            horizon_jvm::platform_embedded(),
            "horizon-platform.jar is empty or missing; Fabric content/events would be unavailable"
        );
        let Some(home) = java_home() else {"""
    if fabric_test_source.count(test_anchor) != 1:
        raise SystemExit(f"{fabric_test_path}: Fabric integration test anchor changed")
    fabric_test_source = fabric_test_source.replace(test_anchor, test_replacement, 1)
    fabric_test_path.write_text(fabric_test_source, encoding="utf-8")

    print("Applied fail-closed Java Bridge initialization, persistent startup logs, and platform-JAR validation.")

else:
    print("Skipping external Java Bridge source edits for the standard Horizon checkout.")
