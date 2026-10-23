"""Wire the isolated Fabric Bridge P1 world into Horizon's existing mod runtime."""
from pathlib import Path

runtime = root / "crates/horizon-mods/src/runtime.rs"
manifest = root / "crates/horizon-mods/src/manifest.rs"
for path in (runtime, manifest):
    if not path.is_file():
        raise SystemExit(f"Missing source required for P1 runtime integration: {path}")

replace_once(manifest,
    'pub const APIS: [&str; 2] = [API, "0.2"];',
    'pub const FABRIC_BRIDGE_API: &str = "p1";\npub const APIS: [&str; 3] = [API, "0.2", FABRIC_BRIDGE_API];')
replace_once(manifest,
'''    Registries,
}''',
'''    Registries,
    Events,
    Keys,
    Commands,
    Config,
}''')
replace_once(manifest,
'''        Capability::Registries,
    ];''',
'''        Capability::Registries,
        Capability::Events,
        Capability::Keys,
        Capability::Commands,
        Capability::Config,
    ];''')
replace_once(manifest,
'''            Capability::Registries => "registries",
        }''',
'''            Capability::Registries => "registries",
            Capability::Events => "events",
            Capability::Keys => "keys",
            Capability::Commands => "commands",
            Capability::Config => "config",
        }''')
replace_once(manifest,
'''            Capability::Registries => "add blocks and items to your worlds",
        }''',
'''            Capability::Registries => "add blocks and items to your worlds",
            Capability::Events => "receive registered client events",
            Capability::Keys => "register keyboard, mouse and touch bindings",
            Capability::Commands => "register typed chat commands",
            Capability::Config => "store its own typed settings",
        }''')
old_checker = '''    pub fn of_import(name: &str) -> Result<Option<Capability>, String> {
        let unknown = || format!("imports {name}, which Horizon does not offer");
        let rest = name.strip_prefix("horizon:mods/").ok_or_else(unknown)?;
        let (interface, version) = rest.split_once('@').ok_or_else(unknown)?;
        if !version.starts_with("0.1.") {
            return Err(format!("needs API {version}; this Horizon runs {API}"));
        }
        if interface == "log" {
            return Ok(None);
        }
        Capability::ALL
            .into_iter()
            .filter(|c| *c != Capability::Registries)
            .find(|c| c.interface() == interface)
            .map(Some)
            .ok_or_else(unknown)
    }'''
new_checker = '''    pub fn of_import(name: &str) -> Result<Option<Capability>, String> {
        let unknown = || format!("imports {name}, which Horizon does not offer");
        let (package, rest) = if let Some(rest) = name.strip_prefix("horizon:mods/") {
            ("horizon:mods", rest)
        } else if let Some(rest) = name.strip_prefix("horizon:fabric-bridge/") {
            ("horizon:fabric-bridge", rest)
        } else {
            return Err(unknown());
        };
        let (interface, version) = rest.split_once('@').ok_or_else(unknown)?;
        if !version.starts_with("0.1.") {
            return Err(format!("needs interface version {version}; supported imports are 0.1.x"));
        }
        if interface == "log" && package == "horizon:mods" {
            return Ok(None);
        }
        let capability = match (package, interface) {
            ("horizon:mods", "game") => Capability::GameRead,
            ("horizon:mods", "chat") => Capability::Chat,
            ("horizon:mods", "hud") => Capability::Hud,
            ("horizon:mods", "storage") => Capability::Storage,
            ("horizon:fabric-bridge", "events") => Capability::Events,
            ("horizon:fabric-bridge", "keys") => Capability::Keys,
            ("horizon:fabric-bridge", "commands") => Capability::Commands,
            ("horizon:fabric-bridge", "hud") => Capability::Hud,
            ("horizon:fabric-bridge", "config") => Capability::Config,
            _ => return Err(unknown()),
        };
        Ok(Some(capability))
    }'''
replace_once(manifest, old_checker, new_checker)
replace_once(manifest,
'''            Capability::Registries => "registries",
        };
        f.write_str(name)''',
'''            Capability::Registries => "registries",
            Capability::Events => "events",
            Capability::Keys => "keys",
            Capability::Commands => "commands",
            Capability::Config => "config",
        };
        f.write_str(name)''')

runtime_text = [runtime.read_text(encoding="utf-8")]
def rr(old, new, label):
    count = runtime_text[0].count(old)
    if count != 1:
        raise SystemExit(f"runtime.rs: expected one {label}, found {count}")
    runtime_text[0] = runtime_text[0].replace(old, new, 1)

runtime_text[0] = runtime_text[0].replace(
    'use std::{\n    path::{Path, PathBuf},',
    'use std::{\n    collections::BTreeMap,\n    path::{Path, PathBuf},', 1)
use_anchor = '''    horizon::mods::{chat, game, hud, log as mod_log, storage},
};'''
use_replacement = '''    horizon::mods::{chat, game, hud, log as mod_log, storage},
};
use fabric_bridge_p1_bindings::{
    ModP1,
    horizon::fabric_bridge::{
        commands::{Arg as P1Arg, ArgType as P1ArgType, ArgValue as P1ArgValue},
        config::{Field as P1Field, Value as P1Value},
        events::Phase as P1Phase,
        hud::{Anchor as P1Anchor, Element as P1Element, Widget as P1Widget},
        keys::Trigger as P1Trigger,
        types::ChatMessage as P1ChatMessage,
    },
};'''
rr(use_anchor, use_replacement, "P1 WIT imports")

rr('''    run: Option<Running>,
    modified: Option<SystemTime>,
}''',
'''    run: Option<Running>,
    p1_run: Option<RunningP1>,
    modified: Option<SystemTime>,
}''', "entry P1 runtime field")
rr('''struct Running {
    store: Store<State>,
    exports: HorizonMod,
    hooks: Registration,
}''',
'''struct Running {
    store: Store<State>,
    exports: HorizonMod,
    hooks: Registration,
}

struct RunningP1 {
    store: Store<P1State>,
    exports: ModP1,
}''', "P1 running instance")
rr('''#[derive(Default)]
pub struct Mods {
    engine: Option<(Engine, Linker<State>)>,
    entries: Vec<Entry>,
    snapshot: Arc<Snapshot>,
    out: Vec<Output>,
}''',
'''#[derive(Default)]
pub struct Mods {
    engine: Option<(Engine, Linker<State>)>,
    p1_engine: Option<(Engine, Linker<P1State>)>,
    entries: Vec<Entry>,
    snapshot: Arc<Snapshot>,
    out: Vec<Output>,
}''', "P1 engine field")

p1_state = r"""
struct P1State {
    id: String,
    subscriptions: BTreeMap<String, P1Phase>,
    keybindings: BTreeMap<String, P1Trigger>,
    commands: BTreeMap<String, Vec<P1Arg>>,
    widgets: BTreeMap<String, P1Widget>,
    config_schema: BTreeMap<String, P1Field>,
    config_values: BTreeMap<String, P1Value>,
    config_dir: PathBuf,
    panel: Option<Panel>,
    limits: StoreLimits,
}

impl P1State {
    fn valid_key(value: &str, max: usize) -> bool {
        !value.is_empty() && value.len() <= max
            && value.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"_.-".contains(&b))
    }

    fn sync_panel(&mut self) {
        let mut corner = None;
        let mut lines = Vec::new();
        for widget in self.widgets.values().filter(|w| w.visible) {
            if corner.is_none() {
                corner = Some(match widget.anchor {
                    P1Anchor::TopLeft | P1Anchor::TopCenter => Corner::TopLeft,
                    P1Anchor::TopRight => Corner::TopRight,
                    P1Anchor::BottomLeft | P1Anchor::BottomCenter => Corner::BottomLeft,
                    P1Anchor::BottomRight => Corner::BottomRight,
                });
            }
            for row in &widget.rows {
                if lines.len() >= HUD_LINES { break; }
                match row {
                    P1Element::Text((text, rgba, _shadow)) => lines.push(Line {
                        text: clip(text.clone()), color: *rgba & 0xFF_FFFF,
                    }),
                    P1Element::Bar((value, max, rgba, width)) => {
                        if !max.is_finite() || *max <= 0.0 || !value.is_finite() { continue; }
                        let ratio = (*value / *max).clamp(0.0, 1.0);
                        let total = (*width as usize).clamp(1, 24);
                        let filled = (total as f32 * ratio).round() as usize;
                        let text = format!("[{}{}] {:>3.0}%",
                            "#".repeat(filled.min(total)),
                            "-".repeat(total.saturating_sub(filled.min(total))),
                            ratio * 100.0);
                        lines.push(Line { text, color: *rgba & 0xFF_FFFF });
                    }
                    P1Element::ItemIcon((item, count)) => {
                        let text = count.map_or_else(|| item.clone(), |n| format!("{item} x{n}"));
                        lines.push(Line { text: clip(text), color: 0xFF_FFFF });
                    }
                }
            }
            if lines.len() >= HUD_LINES { break; }
        }
        self.panel = if lines.is_empty() { None }
            else { Some(Panel { corner: corner.unwrap_or(Corner::TopLeft), lines }) };
    }
}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::events::Host for P1State {
    fn register(&mut self, event: String, phase: P1Phase) -> Result<(), String> {
        if !matches!(event.as_str(), "client_tick" | "world_join" | "world_leave" | "chat_received" | "chat_send") {
            return Err(format!("unsupported P1 event: {event}"));
        }
        if self.subscriptions.contains_key(&event) { return Err("event already registered".into()); }
        if self.subscriptions.len() >= 16 { return Err("maximum 16 event registrations per mod".into()); }
        self.subscriptions.insert(event, phase);
        Ok(())
    }
}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::keys::Host for P1State {
    fn register(&mut self, id: String, category: String, default: P1Trigger) -> Result<(), String> {
        let own_prefix = format!("{}:", self.id);
        let path = id.strip_prefix(&own_prefix).unwrap_or("");
        if path.is_empty() || !Self::valid_key(path, 64) {
            return Err("keybinding id must be namespaced by the mod".into());
        }
        if category.trim().is_empty() || category.len() > 96 {
            return Err("keybinding category is empty or too long".into());
        }
        if let P1Trigger::Touch(name) = &default {
            if name.trim().is_empty() || name.len() > 96 { return Err("invalid touch binding id".into()); }
        }
        if self.keybindings.len() >= 128 || self.keybindings.contains_key(&id) {
            return Err("keybinding limit reached or id duplicated".into());
        }
        self.keybindings.insert(id, default);
        Ok(())
    }
}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::commands::Host for P1State {
    fn register(&mut self, name: String, args: Vec<P1Arg>) -> Result<(), String> {
        if name.is_empty() || name.len() > 64
            || !name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') {
            return Err("command name must use [a-z0-9_] and be at most 64 characters".into());
        }
        if self.commands.len() >= 32 || self.commands.contains_key(&name) {
            return Err("command limit reached or command duplicated".into());
        }
        if args.len() > 16 { return Err("maximum 16 arguments per command".into()); }
        let mut optional_seen = false;
        for (index, arg) in args.iter().enumerate() {
            if arg.name.is_empty() || arg.name.len() > 64
                || !arg.name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') {
                return Err(format!("invalid argument name: {}", arg.name));
            }
            if optional_seen && !arg.optional { return Err("required argument follows optional argument".into()); }
            optional_seen |= arg.optional;
            match &arg.ty {
                P1ArgType::Int((min, max)) if min > max => return Err("invalid integer argument range".into()),
                P1ArgType::Choice(options) if options.is_empty() || options.len() > 64
                    || options.iter().any(|v| v.is_empty() || v.chars().any(char::is_whitespace)) =>
                    return Err("invalid choice options".into()),
                P1ArgType::Greedy if index + 1 != args.len() => return Err("greedy argument must be last".into()),
                _ => {}
            }
        }
        self.commands.insert(name, args);
        Ok(())
    }
}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::hud::Host for P1State {
    fn set_widget(&mut self, key: String, mut widget: P1Widget) -> Result<(), String> {
        if !Self::valid_key(&key, 64) { return Err("invalid HUD widget key".into()); }
        if widget.rows.len() > HUD_LINES { return Err(format!("maximum {HUD_LINES} HUD rows")); }
        for row in &mut widget.rows {
            match row {
                P1Element::Text((text, _, _)) => {
                    if text.len() > LINE_CHARS {
                        let mut end = LINE_CHARS;
                        while !text.is_char_boundary(end) { end -= 1; }
                        text.truncate(end);
                    }
                }
                P1Element::Bar((value, max, _, _)) => {
                    if !value.is_finite() || !max.is_finite() || *max <= 0.0 {
                        return Err("HUD bar requires finite value and positive maximum".into());
                    }
                    *value = value.clamp(0.0, *max);
                }
                P1Element::ItemIcon((id, _)) if id.is_empty() || id.len() > 128 => {
                    return Err("invalid HUD item resource id".into());
                }
                _ => {}
            }
        }
        if !self.widgets.contains_key(&key) && self.widgets.len() >= 8 {
            return Err("maximum 8 HUD widgets per mod".into());
        }
        self.widgets.insert(key, widget);
        self.sync_panel();
        Ok(())
    }

    fn remove_widget(&mut self, key: String) {
        self.widgets.remove(&key);
        self.sync_panel();
    }
}

impl fabric_bridge_p1_bindings::horizon::fabric_bridge::config::Host for P1State {
    fn declare(&mut self, key: String, field: P1Field) -> Result<(), String> {
        if !Self::valid_key(&key, 64) || self.config_schema.len() >= 128 || self.config_schema.contains_key(&key) {
            return Err("invalid or duplicate config key; at most 128 keys".into());
        }
        validate_p1_config(&field, &field.default)?;
        let value = read_p1_config(&self.config_dir, &key)
            .filter(|v| validate_p1_config(&field, v).is_ok())
            .unwrap_or_else(|| field.default.clone());
        self.config_values.insert(key.clone(), value);
        self.config_schema.insert(key, field);
        Ok(())
    }

    fn get(&mut self, key: String) -> Option<P1Value> {
        self.config_values.get(&key).cloned()
            .or_else(|| self.config_schema.get(&key).map(|field| field.default.clone()))
    }

    fn set(&mut self, key: String, value: P1Value) -> Result<(), String> {
        let field = self.config_schema.get(&key).ok_or_else(|| "config key was not declared".to_owned())?;
        validate_p1_config(field, &value)?;
        write_p1_config(&self.config_dir, &key, &value)?;
        self.config_values.insert(key, value);
        Ok(())
    }
}

fn validate_p1_config(field: &P1Field, value: &P1Value) -> Result<(), String> {
    if std::mem::discriminant(&field.default) != std::mem::discriminant(value) {
        return Err("config type differs from declaration".into());
    }
    if field.min.is_some_and(|v| !v.is_finite()) || field.max.is_some_and(|v| !v.is_finite())
        || matches!((field.min, field.max), (Some(a), Some(b)) if a > b) {
        return Err("invalid config range".into());
    }
    let number = match value {
        P1Value::Int(v) => Some(*v as f64),
        P1Value::Float(v) if v.is_finite() => Some(*v),
        P1Value::Float(_) => return Err("config number must be finite".into()),
        _ => None,
    };
    if let Some(v) = number {
        if field.min.is_some_and(|min| v < min) || field.max.is_some_and(|max| v > max) {
            return Err("config value outside declared range".into());
        }
    }
    if let P1Value::Text(text) = value {
        if text.len() > 1024 { return Err("config text too long".into()); }
    }
    Ok(())
}

fn read_p1_config(dir: &Path, key: &str) -> Option<P1Value> {
    let raw: serde_json::Value = serde_json::from_slice(&std::fs::read(dir.join(format!("{key}.json"))).ok()?).ok()?;
    match raw.get("kind")?.as_str()? {
        "bool" => Some(P1Value::Bool(raw.get("value")?.as_bool()?)),
        "int" => Some(P1Value::Int(raw.get("value")?.as_i64()?)),
        "float" => Some(P1Value::Float(raw.get("value")?.as_f64()?)),
        "text" => Some(P1Value::Text(raw.get("value")?.as_str()?.to_owned())),
        _ => None,
    }
}

fn write_p1_config(dir: &Path, key: &str, value: &P1Value) -> Result<(), String> {
    let raw = match value {
        P1Value::Bool(v) => serde_json::json!({"kind": "bool", "value": v}),
        P1Value::Int(v) => serde_json::json!({"kind": "int", "value": v}),
        P1Value::Float(v) => serde_json::json!({"kind": "float", "value": v}),
        P1Value::Text(v) => serde_json::json!({"kind": "text", "value": v}),
    };
    std::fs::create_dir_all(dir).map_err(|e| format!("create config directory: {e}"))?;
    std::fs::write(dir.join(format!("{key}.json")), serde_json::to_vec(&raw).map_err(|e| e.to_string())?)
        .map_err(|e| format!("write config: {e}"))
}

fn parse_p1_command(args: &[P1Arg], input: &str) -> Result<Vec<(String, P1ArgValue)>, String> {
    let mut rest = input.trim();
    let mut output = Vec::new();
    for arg in args {
        rest = rest.trim_start();
        if rest.is_empty() {
            if arg.optional { continue; }
            return Err(format!("missing argument {}", arg.name));
        }
        let (token, tail) = if matches!(&arg.ty, P1ArgType::Greedy) {
            (rest, "")
        } else {
            rest.split_once(char::is_whitespace).unwrap_or((rest, ""))
        };
        let fail = |message: &str| format!("invalid {}: {message}", arg.name);
        let value = match &arg.ty {
            P1ArgType::Bool => P1ArgValue::Bool(token.parse().map_err(|_| fail("expected true/false"))?),
            P1ArgType::Int((min, max)) => {
                let n: i64 = token.parse().map_err(|_| fail("expected integer"))?;
                if n < *min || n > *max { return Err(fail("outside allowed range")); }
                P1ArgValue::Int(n)
            }
            P1ArgType::Float => {
                let n: f64 = token.parse().map_err(|_| fail("expected number"))?;
                if !n.is_finite() { return Err(fail("number must be finite")); }
                P1ArgValue::Float(n)
            }
            P1ArgType::Word | P1ArgType::Greedy => P1ArgValue::Text(token.to_owned()),
            P1ArgType::Choice(options) => {
                if !options.iter().any(|option| option == token) { return Err(fail("not an allowed choice")); }
                P1ArgValue::Text(token.to_owned())
            }
        };
        output.push((arg.name.clone(), value));
        rest = tail;
    }
    if !rest.trim().is_empty() { return Err("extra command arguments".into()); }
    Ok(output)
}

"""
rr('#[derive(Default)]\npub struct Mods {', p1_state + "\n#[derive(Default)]\npub struct Mods {", "P1 host implementation anchor")
p1_runtime_methods = r"""
    fn p1_engine(&mut self) -> Result<&(Engine, Linker<P1State>), String> {
        if self.p1_engine.is_none() {
            let mut config = Config::new();
            config.wasm_component_model(true).consume_fuel(true);
            let engine = Engine::new(&config).map_err(|e| e.to_string())?;
            let mut linker = Linker::new(&engine);
            ModP1::add_to_linker::<P1State, HasSelf<P1State>>(&mut linker, |state| state)
                .map_err(|e| e.to_string())?;
            self.p1_engine = Some((engine, linker));
        }
        Ok(self.p1_engine.as_ref().expect("P1 engine initialized"))
    }

    fn start_any(
        &mut self,
        path: &Path,
        package: &Package,
        reserved: &dyn Fn(&str) -> bool,
    ) -> Result<(Option<Running>, Option<RunningP1>), String> {
        if package.manifest.api == crate::manifest::FABRIC_BRIDGE_API {
            self.start_p1(path, package, reserved).map(|run| (None, run))
        } else {
            self.start(path, package, reserved).map(|run| (run, None))
        }
    }

    fn start_p1(
        &mut self,
        path: &Path,
        package: &Package,
        reserved: &dyn Fn(&str) -> bool,
    ) -> Result<Option<RunningP1>, String> {
        let manifest = &package.manifest;
        let Some(wasm) = &package.wasm else { return Ok(None); };
        if manifest.side != Side::Client {
            return Err("Fabric Bridge P1 currently supports client-side components only".into());
        }
        if self.entries.iter().any(|entry| {
            (entry.run.is_some() || entry.p1_run.is_some())
                && entry.manifest.as_ref().is_some_and(|other| other.id == manifest.id)
        }) {
            return Err(format!("another running mod already uses id {}", manifest.id));
        }
        let taken: Vec<String> = self.commands().map(str::to_owned).collect();
        let (engine, linker) = self.p1_engine()?;
        let component = Component::new(engine, wasm).map_err(|e| format!("not a P1 component: {e}"))?;
        let imports: Vec<String> = component.component_type().imports(engine)
            .map(|(name, _)| name.to_string()).collect();
        manifest.check_imports(imports.iter().map(String::as_str))?;
        let config_dir = path.parent().unwrap_or(Path::new("."))
            .join(STORAGE_DIR).join(&manifest.id).join("fabric-p1-config");
        let state = P1State {
            id: manifest.id.clone(),
            subscriptions: BTreeMap::new(),
            keybindings: BTreeMap::new(),
            commands: BTreeMap::new(),
            widgets: BTreeMap::new(),
            config_schema: BTreeMap::new(),
            config_values: BTreeMap::new(),
            config_dir,
            panel: None,
            limits: StoreLimitsBuilder::new().memory_size(MEMORY).instances(16).build(),
        };
        let mut store = Store::new(engine, state);
        store.limiter(|s| &mut s.limits);
        store.set_fuel(FUEL_INIT).map_err(|e| e.to_string())?;
        let exports = ModP1::instantiate(&mut store, &component, linker).map_err(|e| reason(&e))?;
        exports.horizon_fabric_bridge_guest().call_init(&mut store).map_err(|e| reason(&e))??;
        let conflicts: Vec<String> = store.data().commands.keys()
            .filter(|name| reserved(name) || taken.iter().any(|old| old == *name))
            .cloned().collect();
        for name in conflicts {
            log::warn!("Horizon: P1 mod {} cannot take /{}: already a command", manifest.id, name);
            store.data_mut().commands.remove(&name);
        }
        store.data_mut().sync_panel();
        Ok(Some(RunningP1 { store, exports }))
    }

    fn each_p1(
        &mut self,
        event: &str,
        fuel: u64,
        mut call: impl FnMut(&ModP1, &mut Store<P1State>) -> wasmtime::Result<()>,
    ) {
        let mut order: Vec<(u8, usize)> = self.entries.iter().enumerate().filter_map(|(index, entry)| {
            let phase = entry.p1_run.as_ref()?.store.data().subscriptions.get(event)?;
            let rank = match phase { P1Phase::Early => 0, P1Phase::Normal => 1, P1Phase::Late => 2 };
            Some((rank, index))
        }).collect();
        order.sort_by_key(|(rank, index)| (*rank, *index));
        for (_, index) in order { self.call_p1(index, fuel, &mut call); }
    }

    fn call_p1<R>(
        &mut self,
        index: usize,
        fuel: u64,
        call: impl FnOnce(&ModP1, &mut Store<P1State>) -> wasmtime::Result<R>,
    ) -> Option<R> {
        let entry = &mut self.entries[index];
        let running = entry.p1_run.as_mut()?;
        let started = Instant::now();
        let result = running.store.set_fuel(fuel)
            .and_then(|()| call(&running.exports, &mut running.store));
        entry.cost = (entry.cost * 63 + started.elapsed()) / 64;
        match result {
            Ok(value) => Some(value),
            Err(error) => {
                let why = reason(&error);
                log::warn!("Horizon: Fabric Bridge P1 mod {} stopped: {why}", running.store.data().id);
                entry.status = Status::Failed(why);
                entry.p1_run = None;
                None
            }
        }
    }
"""
rr('    fn start(', p1_runtime_methods + "\n    fn start(", "legacy start method anchor")

rr('''                run: None,
                modified,''',
'''                run: None,
                p1_run: None,
                modified,''', "entry loader initialization")
old_load = '''                        let content = crate::Content::read(path, &package.manifest);
                        entry.status =
                            match content.and_then(|_| mods.start(path, &package, &reserved)) {
                                Ok(run) => {
                                    entry.run = run;
                                    Status::Running
                                }
                                Err(e) => Status::Failed(e),
                            };'''
new_load = '''                        let content = crate::Content::read(path, &package.manifest);
                        entry.status = match content.and_then(|_| mods.start_any(path, &package, &reserved)) {
                            Ok((legacy, p1)) => {
                                entry.run = legacy;
                                entry.p1_run = p1;
                                Status::Running
                            }
                            Err(e) => Status::Failed(e),
                        };'''
rr(old_load, new_load, "entry start dispatch")

rr('''            .any(|e| e.run.is_some() && e.manifest.as_ref().is_some_and(|other| other.id == m.id))''',
'''            .any(|e| (e.run.is_some() || e.p1_run.is_some())
                && e.manifest.as_ref().is_some_and(|other| other.id == m.id))''', "duplicate mod id check")
rr('''        let taken: Vec<String> = self
            .entries
            .iter()
            .filter_map(|e| e.run.as_ref())
            .flat_map(|r| r.hooks.commands.iter().cloned())
            .collect();''',
'''        let taken: Vec<String> = self.commands().map(str::to_owned).collect();''', "shared command conflict detection")

rr('''    pub fn is_running(&self) -> bool {
        self.entries.iter().any(|e| e.run.is_some())
    }''',
'''    pub fn is_running(&self) -> bool {
        self.entries.iter().any(|e| e.run.is_some() || e.p1_run.is_some())
    }''', "running status")
rr('''        self.each(
            FUEL_TICK,
            |h| h.tick,
            |m, s| m.horizon_mods_events().call_tick(s, tick),
        );
    }''',
'''        self.each(
            FUEL_TICK,
            |h| h.tick,
            |m, s| m.horizon_mods_events().call_tick(s, tick),
        );
        self.each_p1("client_tick", FUEL_TICK, |m, s| {
            m.horizon_fabric_bridge_guest().call_on_tick(s, tick).map(|_| ())
        });
    }''', "tick dispatch")
rr('''    pub fn chat(&mut self, text: &str) {
        self.each(
            FUEL_EVENT,
            |h| h.chat,
            |m, s| m.horizon_mods_events().call_chat(s, text),
        );
    }''',
'''    pub fn chat(&mut self, text: &str) {
        self.each(
            FUEL_EVENT,
            |h| h.chat,
            |m, s| m.horizon_mods_events().call_chat(s, text),
        );
        let message = P1ChatMessage { text: text.to_owned(), sender: None, system: false };
        self.each_p1("chat_received", FUEL_EVENT, |m, s| {
            m.horizon_fabric_bridge_guest().call_on_chat_received(s, message.clone()).map(|_| ())
        });
    }''', "chat dispatch")
rr('''    fn command_owner(&self, name: &str) -> Option<usize> {
        self.entries.iter().position(|e| {
            e.run
                .as_ref()
                .is_some_and(|r| r.hooks.commands.iter().any(|c| c == name))
        })
    }''',
'''    fn command_owner(&self, name: &str) -> Option<usize> {
        self.entries.iter().position(|e| {
            e.run.as_ref().is_some_and(|r| r.hooks.commands.iter().any(|c| c == name))
        }).or_else(|| self.entries.iter().position(|e| {
            e.p1_run.as_ref().is_some_and(|r| r.store.data().commands.contains_key(name))
        }))
    }''', "command owner lookup")
old_command = '''        let result = self.call(i, FUEL_EVENT, |m, s| {
            m.horizon_mods_events().call_command(s, name, args)
        });
        match result {
            Some(Ok(Some(text))) => self.out.push(Output::Feedback { text, error: false }),
            Some(Err(text)) => self.out.push(Output::Feedback { text, error: true }),
            Some(Ok(None)) | None => {}
        }
        true
    }'''
new_command = '''        if self.entries[i].p1_run.is_some() {
            let spec = self.entries[i].p1_run.as_ref()
                .and_then(|running| running.store.data().commands.get(name)).cloned().unwrap_or_default();
            let parsed = match parse_p1_command(&spec, args) {
                Ok(parsed) => parsed,
                Err(error) => {
                    self.out.push(Output::Feedback { text: error, error: true });
                    return true;
                }
            };
            match self.call_p1(i, FUEL_EVENT, |m, s| {
                m.horizon_fabric_bridge_guest().call_on_command(s, name, parsed)
            }) {
                Some(Ok(Ok(()))) => {}
                Some(Ok(Err(text))) => self.out.push(Output::Feedback { text, error: true }),
                None => {}
                Some(Err(error)) => self.out.push(Output::Feedback { text: error.to_string(), error: true }),
            }
            return true;
        }
        let result = self.call(i, FUEL_EVENT, |m, s| {
            m.horizon_mods_events().call_command(s, name, args)
        });
        match result {
            Some(Ok(Some(text))) => self.out.push(Output::Feedback { text, error: false }),
            Some(Err(text)) => self.out.push(Output::Feedback { text, error: true }),
            Some(Ok(None)) | None => {}
        }
        true
    }'''
rr(old_command, new_command, "P1 command dispatch")
rr('''    pub fn commands(&self) -> impl Iterator<Item = &str> {
        self.entries
            .iter()
            .filter_map(|e| e.run.as_ref())
            .flat_map(|r| r.hooks.commands.iter().map(String::as_str))
    }''',
'''    pub fn commands(&self) -> impl Iterator<Item = &str> {
        self.entries.iter().filter_map(|e| e.run.as_ref())
            .flat_map(|r| r.hooks.commands.iter().map(String::as_str))
            .chain(self.entries.iter().filter_map(|e| e.p1_run.as_ref())
                .flat_map(|r| r.store.data().commands.keys().map(String::as_str)))
    }''', "P1 command listing")
rr('''    pub fn panels(&self) -> impl Iterator<Item = &Panel> {
        self.entries
            .iter()
            .filter_map(|e| e.run.as_ref()?.store.data().panel.as_ref())
    }''',
'''    pub fn panels(&self) -> impl Iterator<Item = &Panel> {
        self.entries.iter().filter_map(|entry| {
            if let Some(run) = &entry.run {
                run.store.data().panel.as_ref()
            } else {
                entry.p1_run.as_ref().and_then(|run| run.store.data().panel.as_ref())
            }
        })
    }''', "P1 HUD panel listing")
rr('''            self.entries[i].run = None;
            let path = self.entries[i].path.clone();
            let started = Package::read(&path).and_then(|p| {
                self.entries[i].manifest = Some(p.manifest.clone());
                self.start(&path, &p, &reserved)
            });
            let entry = &mut self.entries[i];
            let id = entry.manifest.as_ref().map_or("?", |m| m.id.as_str());
            match started {
                Ok(run) => {
                    log::info!("Horizon: mod {id} reloaded");
                    entry.run = run;
                    entry.status = Status::Running;
                }
                Err(e) => {
                    log::warn!("Horizon: mod {id} did not reload: {e}");
                    entry.status = Status::Failed(e);
                }
            }''',
'''            self.entries[i].run = None;
            self.entries[i].p1_run = None;
            let path = self.entries[i].path.clone();
            let started = Package::read(&path).and_then(|p| {
                self.entries[i].manifest = Some(p.manifest.clone());
                self.start_any(&path, &p, &reserved)
            });
            let entry = &mut self.entries[i];
            let id = entry.manifest.as_ref().map_or("?", |m| m.id.as_str());
            match started {
                Ok((legacy, p1)) => {
                    log::info!("Horizon: mod {id} reloaded");
                    entry.run = legacy;
                    entry.p1_run = p1;
                    entry.status = Status::Running;
                }
                Err(e) => {
                    log::warn!("Horizon: mod {id} did not reload: {e}");
                    entry.status = Status::Failed(e);
                }
            }''', "P1 hot reload")
runtime.write_text(runtime_text[0], encoding="utf-8")
print("Installed P1 component loader, import-capability checks, tick/chat hooks, commands, HUD adapter, and persisted config host.")
