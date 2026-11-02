//! Minimal sandboxed component proving that a module can read game state
//! and request a HUD draw through Horizon's versioned WIT host interfaces.
use horizon_mod_sdk::{
    Mod, Registration,
    game,
    hud::{self, Corner, Line},
};

struct ApiProbe {
    last_line: Option<String>,
}

fn player_line(x: f64, y: f64, z: f64, dimension: &str) -> String {
    format!("WIT API OK  {x:.1} / {y:.1} / {z:.1}  [{dimension}]")
}

impl Mod for ApiProbe {
    fn init() -> Result<(Self, Registration), String> {
        Ok((
            Self { last_line: None },
            Registration::new().on_tick(),
        ))
    }

    fn tick(&mut self, _tick: u64) {
        let Some(player) = game::get_player() else {
            if self.last_line.take().is_some() {
                hud::clear();
            }
            return;
        };

        let line = player_line(
            player.x,
            player.y,
            player.z,
            &player.dimension,
        );
        if self.last_line.as_deref() == Some(line.as_str()) {
            return;
        }

        hud::set_panel(
            Corner::TopLeft,
            &[Line {
                text: line.clone(),
                color: 0x8FE8C5,
            }],
        );
        self.last_line = Some(line);
    }
}

horizon_mod_sdk::export_mod!(ApiProbe);

#[cfg(test)]
mod tests {
    use super::player_line;

    #[test]
    fn formats_a_player_snapshot_without_accessing_engine_types() {
        assert_eq!(
            player_line(12.34, 64.0, -3.56, "minecraft:overworld"),
            "WIT API OK  12.3 / 64.0 / -3.6  [minecraft:overworld]"
        );
    }
}
