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
elif "let signed_in = auth.is_some();" in auth_block and "Reason::keyed(\"horizon.multiplayer.not_signed_in\", lang)" in auth_block:
    # Current upstream keeps a Reason object for failed Microsoft authentication. Retain
    # that exact failure/reconnect policy for signed-in accounts, but do not turn the
    # absence of a Microsoft session into an unconditional failure before the server
    # receives the encrypted response.
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
                            if !continues_after_failed_join(lan, true) {
                                return Err(reason);
                            }
                            log::warn!("Authentication failed: {}", reason.text);
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

# The upstream assertion expects no-account rejection. MobileHorizonNative instead
# sends the encrypted response so offline-mode servers can decide whether to admit it.
net_source = net.read_text(encoding="utf-8")
old_doc = """    /// No account never joins; the signed-in rule is compared with the real
    /// client login listener by test_lan_auth_parity.py.
    #[test]
    fn lan_login_continues_after_authentication_failure() {"""
new_doc = """    /// Without a Microsoft session the encrypted response reaches the server,
    /// allowing offline-mode servers to decide whether to admit the player.
    /// Signed-in authentication failures retain the LAN parity check below.
    #[test]
    fn lan_login_continues_after_authentication_failure() {"""
old_assert = """            // Without an account nothing joins, LAN or not.
            let sent_key = worker.join().unwrap();
            assert!(!sent_key, "lan {lan}: joined without an account");
            assert_eq!(reason, NOT_SIGNED_IN);"""
new_assert = """            // Without a Microsoft session, send the encrypted response so an
            // offline-mode server can decide whether to admit the player.
            let sent_key = worker.join().unwrap();
            assert!(sent_key, "lan {lan}: offline-mode fallback did not reach the server");
            assert_ne!(
                reason,
                NOT_SIGNED_IN,
                "lan {lan}: the client rejected the session before the server could decide"
            );"""
if old_doc in net_source and old_assert in net_source:
    net_source = net_source.replace(old_doc, new_doc, 1).replace(old_assert, new_assert, 1)
elif new_doc in net_source and new_assert in net_source:
    pass
else:
    raise SystemExit(f"{net}: upstream LAN authentication test anchors changed")
net.write_text(net_source, encoding="utf-8")
