# Ordered patch step. The selected local skin is loaded by Horizon's normal skin atlas,
# then assigned only to this client's local-player model. It is not uploaded to the
# Microsoft account or advertised to other clients on the server.
replace_once(remote,
    """    fn update_remote_skins(&mut self) {
        let Some(r) = self.remote.as_ref() else {""",
    """    fn mobilehorizon_local_skin(&mut self) -> Option<String> {
        // A selected skin changes only when the game process restarts. Cache the
        // decoded RGBA image to avoid filesystem reads and PNG decoding per frame.
        static LOCAL_SKIN: std::sync::OnceLock<Option<(String, Vec<u8>)>> =
            std::sync::OnceLock::new();
        let selected = LOCAL_SKIN.get_or_init(|| {
            let path = std::env::var_os("HORIZON_LOCAL_SKIN")?;
            let bytes = std::fs::read(path).ok()?;
            let (width, height, rgba) = horizon_client::hud::decode_bytes(&bytes).ok()?;
            let rgba = horizon_client::skins::process_legacy_skin(width, height, &rgba)?;
            let digest = horizon_client::skins::sha1(&bytes);
            let hash = digest.iter().map(|byte| format!("{byte:02x}")).collect::<String>();
            Some((format!("mobilehorizon/local-skin/{hash}"), rgba))
        });
        let (location, rgba) = selected.as_ref()?;
        if self.show_skin(location) {
            return Some(location.clone());
        }
        self.store_skin(location.clone(), rgba.clone());
        self.show_skin(location).then(|| location.clone())
    }

    /// Applies the selected skin on every world tick, even when the tab list is closed.
    /// It also covers the inventory's local player preview when no remote profile exists.
    pub(super) fn apply_mobilehorizon_local_skin(&mut self) {
        let Some(skin) = self.mobilehorizon_local_skin() else {
            return;
        };
        if let Some(model) = self.player_model.as_mut() {
            model.skin = Some(skin.clone());
        }
        let Some(local) = self.remote.as_ref().map(|remote| remote.profile) else {
            return;
        };
        let local_ids = self.remote.as_ref().map(|remote| {
            remote.level_players.iter()
                .filter_map(|(id, uuid)| (*uuid == local).then_some(*id as u32))
                .collect::<Vec<_>>()
        }).unwrap_or_default();
        if let Some(remote) = self.remote.as_mut() {
            remote.skins.insert(local, skin.clone());
        }
        for id in local_ids {
            if let Some(mob) = self.mobs.iter_mut()
                .find(|mob| mob.id == id && &*mob.kind.id == "minecraft:player")
            {
                mob.skin = Some(skin.clone());
            }
        }
    }

    fn update_remote_skins(&mut self) {
        let Some(r) = self.remote.as_ref() else {""",
)
replace_once(remote,
    """        resolved.retain(|(_, skin)| !skin.starts_with("skins/") || self.show_skin(skin));""",
    """        // Override only this client's local player. Other players and their skins
        // continue to come from the signed/received server profile data.
        if let Some(local_skin) = self.mobilehorizon_local_skin() {
            resolved.retain(|(uuid, _)| *uuid != local);
            resolved.push((local, local_skin));
        }
        resolved.retain(|(_, skin)| !skin.starts_with("skins/") || self.show_skin(skin));""",
)

replace_once(game,
    """        self.tick_advancements();
        self.tick_player_model();
        Ok(())""",
    """        self.tick_advancements();
        self.tick_player_model();
        self.apply_mobilehorizon_local_skin();
        Ok(())""",
)
