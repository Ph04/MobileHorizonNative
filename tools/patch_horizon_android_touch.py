#!/usr/bin/env python3
"""Patch the pinned Horizon Rust client so Android touch becomes mouse input, then add a NativeActivity cdylib entry point."""
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
repo_root = Path(__file__).resolve().parents[1]
game = root / "crates/horizon-client/src/game.rs"
lib = root / "crates/horizon-client/src/lib.rs"
client_cargo = root / "crates/horizon-client/Cargo.toml"
clipboard = root / "crates/horizon-client/src/clipboard.rs"
workspace_cargo = root / "Cargo.toml"

for path in (game, lib, client_cargo, clipboard, workspace_cargo):
    if not path.is_file():
        raise SystemExit(f"Missing pinned Horizon source file: {path}")

def replace_once(path: Path, old: str, new: str) -> None:
    content = path.read_text(encoding="utf-8")
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement for {old[:90]!r}, found {count}")
    path.write_text(content.replace(old, new, 1), encoding="utf-8")

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

replace_once(lib, 'pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;',
'''pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

#[derive(Clone, Debug)]
pub enum AndroidInjectedInput {
    CursorMoved { x: f64, y: f64 },
    MouseButton { button: u32, pressed: bool, x: f64, y: f64 },
    MouseScroll { amount: f32, x: f64, y: f64 },
    /// Android KeyEvent key code and edge state from the touchscreen control layer.
    /// Relative movement from a finger drag; consumed as DeviceEvent::MouseMotion when captured.
    MouseMotion { dx: f64, dy: f64 },
    KeyInput { key_code: i32, pressed: bool },
    /// Full line entered with Android's software keyboard.
    ChatText { text: String },
}

static HORIZON_ANDROID_GAMEPLAY: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

pub fn set_android_gameplay(active: bool) {
    HORIZON_ANDROID_GAMEPLAY.store(active, std::sync::atomic::Ordering::Relaxed);
}

pub fn android_is_gameplay() -> bool {
    HORIZON_ANDROID_GAMEPLAY.load(std::sync::atomic::Ordering::Relaxed)
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
}''')

# NativeGameActivity sets the private data root to filesDir/horizon, whereas the
# launcher imports/exports the actual prepared resource tree under filesDir/.local.
# Honor an absolute resource root passed by the Android Activity instead of resolving
# .local/horizon-client/26.3 relative to the game's save/log directory.
replace_once(game,
    '''            resources: if sibling.join("horizon-resources.json").exists() {
                sibling
            } else {
                PathBuf::from(".local/horizon-client/26.3")
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
    'event::{DeviceEvent, DeviceId, ElementState, MouseButton, MouseScrollDelta, WindowEvent},',
    'event::{DeviceEvent, DeviceId, ElementState, MouseButton, MouseScrollDelta, TouchPhase, WindowEvent},')
replace_once(game,
    '    agent_pointer: bool,\n',
    '    agent_pointer: bool,\n    /// First active touchscreen pointer translated to mouse input.\n    touch_mouse: Option<u64>,\n    /// A simultaneous second pointer requests a right-click.\n    touch_auxiliary: Option<u64>,\n    touch_is_right: bool,\n')
replace_once(game, '            agent_pointer: false,\n',
             '            agent_pointer: false,\n            touch_mouse: None,\n            touch_auxiliary: None,\n            touch_is_right: false,\n')

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
            horizon_client::AndroidInjectedInput::ChatText { text } => {
                if self.screen == Screen::Playing && self.player.is_some() {
                    self.open_chat("");
                }
                if self.screen == Screen::Chat && !text.is_empty() {
                    self.chat_type(&text);
                    self.chat_key_code(KeyCode::Enter, None);
                }
            }
        }
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
        horizon_client::set_android_gameplay(
            self.screen == Screen::Playing && self.player.is_some() && self.title.is_none()
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
        if let App::Ready(game) = self {
            game.user_event(event_loop, event);
        }
        // Events during resource loading are intentionally ignored.
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
