//! First WIT-native port slice of the legacy mod_menu mixin package.
//! The game owns layout and drawing; this module supplies the current module list
//! and requests enable/disable operations through the capability-scoped host API.
use horizon_mod_sdk::{
    Mod, Registration, mod_manager,
    ui::{self, ListWidget, Widget},
};

const MODULE_LIST_WIDGET: u32 = 1;
const MAX_ROWS: usize = 512;

struct ModMenu {
    last_rows: Vec<String>,
}

fn rows() -> Vec<String> {
    mod_manager::list()
        .into_iter()
        .take(MAX_ROWS)
        .map(|entry| {
            let state = if entry.enabled { "ON" } else { "OFF" };
            let mut row = format!("{} {}  [{}]", entry.name, entry.version, state);
            if entry.development {
                row.push_str("  [DEV]");
            }
            if let Some(problem) = entry.problem {
                row.push_str("  — ");
                row.push_str(&problem);
            }
            row
        })
        .collect()
}

impl ModMenu {
    fn refresh(&mut self) {
        let current = rows();
        if current == self.last_rows {
            return;
        }
        ui::set_settings(&[Widget::List(ListWidget {
            id: MODULE_LIST_WIDGET,
            rows: current.clone(),
            selected: None,
        })]);
        self.last_rows = current;
    }
}

impl Mod for ModMenu {
    fn init() -> Result<(Self, Registration), String> {
        Ok((
            Self { last_rows: Vec::new() },
            Registration::new().on_tick(),
        ))
    }

    fn tick(&mut self, _tick: u64) {
        self.refresh();
    }

    fn ui_select(&mut self, id: u32, row: u32) {
        if id != MODULE_LIST_WIDGET {
            return;
        }
        let Some(entry) = mod_manager::list().into_iter().nth(row as usize) else {
            return;
        };
        if entry.development {
            return;
        }
        // The host queues this request and reloads the module set after the
        // current hook returns. No Rust code or native library is loaded here.
        let _ = mod_manager::set_enabled(&entry.id, !entry.enabled);
        self.last_rows.clear();
    }

    fn ui_settings_opened(&mut self) {
        self.last_rows.clear();
        self.refresh();
    }
}

horizon_mod_sdk::export_mod!(ModMenu);
