# Ordered patch step. Keep only the offline/hybrid login behavior required by the Android launcher.
# The remaining server visual/resource compatibility patches are intentionally not applied
# to the default runtime while they are being developed separately.
# Preserve the upstream NOT_SIGNED_IN text: reconnect classification relies on its exact wording.

net_source = net.read_text(encoding="utf-8")
auth_start_marker = "                if hello.should_authenticate {"
auth_end_marker = "\n                status(send, &tr(lang, \"connect.encrypting\"));"
auth_start = net_source.find(auth_start_marker)
auth_end = net_source.find(auth_end_marker, auth_start + len(auth_start_marker))
if auth_start < 0 or auth_end < 0:
    raise SystemExit(f"{net}: online authentication block was not found")
auth_block = net_source[auth_start:auth_end]
if "auth.ok_or_else(|| NOT_SIGNED_IN.to_owned())?" in auth_block:
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
                        log::warn!("Horizon: online-mode challenge received without a Microsoft session; sending the normal encrypted response and allowing the server to decide whether offline fallback is configured");
                    }
                }"""
    net.write_text(net_source[:auth_start] + auth_replacement + net_source[auth_end:], encoding="utf-8")
elif "if let Some(account) = auth" in auth_block:
    pass
else:
    raise SystemExit(f"{net}: unsupported online authentication block layout")
