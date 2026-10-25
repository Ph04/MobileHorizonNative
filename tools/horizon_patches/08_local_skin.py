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
        if !self.show_skin(location) {
            self.store_skin(location.clone(), rgba.clone());
        }
        self.show_skin(location).then(|| location.clone())
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
