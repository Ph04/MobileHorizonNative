# Ordered patch step. Keep only the offline/hybrid login behavior required by the Android launcher.
# The remaining server visual/resource compatibility patches are intentionally not applied
# to the default runtime while they are being developed separately.
replace_once(net,
    'pub const NOT_SIGNED_IN: &str = "This server is in online mode. Sign in with your Microsoft account in the Horizon launcher to join it.";',
    'pub const NOT_SIGNED_IN: &str = "This server requires official online authentication. Sign in with a verified account to join it; servers configured for offline mode still accept an offline nickname.";',
)

replace_once(net,
    """                if hello.should_authenticate {
                    let account = auth.ok_or_else(|| NOT_SIGNED_IN.to_owned())?;
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
                }""",
    """                if hello.should_authenticate {
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
                        log::warn!(
                            "Horizon: server requested online authentication but no Microsoft session is present; sending the normal encrypted login response and letting the server decide whether offline fallback is allowed"
                        );
                    }
                }""",
)
