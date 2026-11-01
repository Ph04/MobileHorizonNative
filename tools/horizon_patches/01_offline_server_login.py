# Ordered patch step. Keep only the offline/hybrid login behavior required by the Android launcher.
# The remaining server visual/resource compatibility patches are intentionally not applied
# to the default runtime while they are being developed separately.
# Preserve the upstream NOT_SIGNED_IN text: reconnect classification relies on its exact wording.

def patch_offline_login(net):
    net_source = net.read_text(encoding="utf-8")
    auth_start_marker = "                if hello.should_authenticate {"
    auth_end_marker = "\n                status(send, &tr(lang, \"connect.encrypting\"));"
    auth_start = net_source.find(auth_start_marker)
    auth_end = net_source.find(auth_end_marker, auth_start + len(auth_start_marker))
    if auth_start < 0 or auth_end < 0:
        raise SystemExit(f"{net}: online authentication block was not found")

    auth_block = net_source[auth_start:auth_end]
    # A previous successful application of this patch is a no-op.
    if ("if let Some(account) = auth" in auth_block
            and (
                "Horizon Android: no Microsoft session" in auth_block
                or "online-mode challenge received without a Microsoft session" in auth_block
            )):
        return False

    if "auth.ok_or_else(|| NOT_SIGNED_IN.to_owned())?" in auth_block:
        # Older Horizon login implementation: join_server errors were propagated directly.
        auth_replacement = """                if hello.should_authenticate {
                    if let Some(account) = auth {
                        let hash = server_hash(&hello.server_id, &secret, &hello.public_key);
                        if let Err(err) = crate::auth::join_server(account, &hash) {
                            let reason = match err.reason() {
                                Ok(key) => tr(lang, key),
                                Err(message) => message.to_owned(),
                            };
                            return Err(plain(
                                &translatable("disconnect.loginFailedInfo", &[&reason]),
                                lang,
                            ));
                        }
                    } else {
                        #[cfg(target_os = "android")]
                        {
                            log::warn!("Horizon Android: no Microsoft session; sending the encrypted response and letting the server decide whether offline fallback is configured");
                        }
                        #[cfg(not(target_os = "android"))]
                        {
                            return Err(NOT_SIGNED_IN.to_owned());
                        }
                    }
                }"""
    elif ("let authenticated = auth" in auth_block
            and "continues_after_failed_join(lan, signed_in)" in auth_block
            and "if let Err(reason) = authenticated" in auth_block):
        # Current Horizon Nightly: preserve the account failure reason, but permit the
        # Android offline profile to send the encrypted response for offline-mode servers.
        auth_replacement = """                if hello.should_authenticate {
                    if let Some(account) = auth {
                        let hash = server_hash(&hello.server_id, &secret, &hello.public_key);
                        if let Err(err) = crate::auth::join_server(account, &hash) {
                            let (reason, key) = match err.reason() {
                                Ok(key) => (tr(lang, key), Some(key.to_owned())),
                                Err(message) => (message.to_owned(), None),
                            };
                            let reason = Reason {
                                text: plain(
                                    &translatable("disconnect.loginFailedInfo", &[&reason]),
                                    lang,
                                ),
                                key,
                            };
                            // Keep Horizon's existing LAN exception for signed-in accounts:
                            // an authentication refusal can continue on LAN, but not on remote servers.
                            if !continues_after_failed_join(lan, true) {
                                return Err(reason);
                            }
                            log::warn!("Authentication failed: {}", reason.text);
                        }
                    } else {
                        #[cfg(target_os = "android")]
                        {
                            log::warn!("Horizon Android: no Microsoft session; sending the encrypted response and letting the server decide whether offline fallback is configured");
                        }
                        #[cfg(not(target_os = "android"))]
                        {
                            return Err(Reason::keyed("horizon.multiplayer.not_signed_in", lang));
                        }
                    }
                }"""
    else:
        raise SystemExit(f"{net}: unsupported online authentication block layout")

    net.write_text(net_source[:auth_start] + auth_replacement + net_source[auth_end:], encoding="utf-8")
    return True


patch_offline_login(net)
