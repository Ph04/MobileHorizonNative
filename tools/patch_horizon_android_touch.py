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

clipboard_struct_old = """#[derive(Default)]
pub struct SystemClipboard {
    system: Option<arboard::Clipboard>,
    /// The text Horizon last copied (horizon-ctl status).
    copied: Option<String>,
}"""
clipboard_struct_new = """#[derive(Default)]
pub struct SystemClipboard {
    #[cfg(feature = "system-clipboard")]
    system: Option<arboard::Clipboard>,
    /// The text Horizon last copied (horizon-ctl status).
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
                    String::new()
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
content = game.read_text(encoding="utf-8")
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
game.write_text(content, encoding="utf-8");

replace_once(game,
    "fn run_event_loop(mut game: Game<'_>) -> Result<()> {\n    EventLoop::new()?.run_app(&mut game)?;",
    """fn new_event_loop() -> Result<EventLoop<()>> {
    #[cfg(target_os = "android")]
    {
        use winit::platform::android::EventLoopBuilderExtAndroid;
        let mut builder = EventLoop::<()>::builder();
        builder.with_android_app(crate::android_app()?);
        Ok(builder.build()?)
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(EventLoop::new()?)
    }
}

fn run_event_loop(mut game: Game<'_>) -> Result<()> {
    new_event_loop()?.run_app(&mut game)?;""")
replace_once(game, '    EventLoop::new()?.run_app(&mut app)?;', '    new_event_loop()?.run_app(&mut app)?;')

replace_once(workspace_cargo, '"crates/horizon-client",',
             '"crates/horizon-client", "crates/mobilehorizon-android",')

dst = root / "crates/mobilehorizon-android"
(dst / "src").mkdir(parents=True, exist_ok=True)
shutil.copyfile(repo_root / "android-native/Cargo.toml", dst / "Cargo.toml")
shutil.copyfile(repo_root / "android-native/src/lib.rs", dst / "src/lib.rs")

print("Patched Horizon Android touch input and created the NativeActivity cdylib target.")
