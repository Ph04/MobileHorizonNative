# Ordered patch step. Keep only the offline/hybrid login behavior required by the Android launcher.
# A no-account profile sends the normal encrypted challenge response. The destination
# server then decides whether its own online-mode policy admits the profile.
# Signed-in accounts retain Horizon's normal session-service validation and reconnect policy.

net_source = net.read_text(encoding="utf-8")

helper_anchor = 'pub const NOT_SIGNED_IN: &str = '
helper_source = """/// Authenticate only when Horizon has a Microsoft session. A missing session
/// is not rejected locally: the encrypted challenge response is still sent, and
/// the destination server decides whether its offline-mode policy admits the name.
fn mobilehorizon_authenticate_if_present<T, E>(
    account: Option<T>,
    authenticate: impl FnOnce(T) -> std::result::Result<(), E>,
) -> std::result::Result<(), E> {
    match account {
        Some(account) => authenticate(account),
        None => Ok(()),
    }
}

#[cfg(test)]
mod mobilehorizon_offline_auth_tests {
    use super::mobilehorizon_authenticate_if_present;

    #[test]
    fn no_microsoft_session_does_not_preempt_encrypted_challenge() {
        let mut authentication_called = false;
        let result = mobilehorizon_authenticate_if_present(None::<u8>, |_account| {
            authentication_called = true;
            Err::<(), &'static str>("authentication must not run without an account")
        });
        assert!(result.is_ok());
        assert!(!authentication_called);
    }

    #[test]
    fn configured_microsoft_session_still_runs_join_authentication() {
        let result = mobilehorizon_authenticate_if_present(Some(7u8), |account| {
            if account == 7 { Ok(()) } else { Err("unexpected account") }
        });
        assert_eq!(result, Ok(()));
    }
}

"""
if "fn mobilehorizon_authenticate_if_present<" not in net_source:
    if net_source.count(helper_anchor) != 1:
        raise SystemExit(f"{net}: expected one NOT_SIGNED_IN declaration for offline-auth helper")
    net_source = net_source.replace(helper_anchor, helper_source + helper_anchor, 1)

auth_start_marker = "                if hello.should_authenticate {"
auth_end_marker = "\n                status(send, &tr(lang, \\"connect.encrypting\\"));"
auth_start = net_source.find(auth_start_marker)
auth_end = net_source.find(auth_end_marker, auth_start + len(auth_start_marker))
if auth_start < 0 or auth_end < 0:
    raise SystemExit(f"{net}: online authentication block was not found")
auth_block = net_source[auth_start:auth_end]

if "auth.ok_or_else(|| NOT_SIGNED_IN.to_owned())?" in auth_block:
    auth_replacement = """                if hello.should_authenticate {
                    let hash = server_hash(&hello.server_id, &secret, &hello.public_key);
                    if let Err(err) = mobilehorizon_authenticate_if_present(
                        auth,
                        |account| crate::auth::join_server(account, &hash),
                    ) {
                        let reason = match err.reason() {
                            Ok(key) => tr(lang, key),
                            Err(message) => message.to_owned(),
                        };
                        return Err(plain(
                            &translatable("disconnect.loginFailedInfo", &[&reason]),
                            lang,
                        ));
                    }
                }"""
    net_source = net_source[:auth_start] + auth_replacement + net_source[auth_end:]
elif ("let signed_in = auth.is_some();" in auth_block
      and 'Reason::keyed("horizon.multiplayer.not_signed_in", lang)' in auth_block):
    # Newer Horizon uses a Reason object so reconnect classification depends on the
    # original reason key. Preserve that exact path for signed-in authentication failures.
    auth_replacement = """                if hello.should_authenticate {
                    let signed_in = auth.is_some();
                    let authenticated = mobilehorizon_authenticate_if_present(auth, |account| {
                        let hash = server_hash(&hello.server_id, &secret, &hello.public_key);
                        crate::auth::join_server(account, &hash).map_err(|err| {
                            let (reason, key) = match err.reason() {
                                Ok(key) => (tr(lang, key), Some(key.to_owned())),
                                Err(message) => (message.to_owned(), None),
                            };
                            Reason {
                                text: plain(
                                    &translatable("disconnect.loginFailedInfo", &[&reason]),
                                    lang,
                                ),
                                key,
                            }
                        })
                    });
                    if let Err(reason) = authenticated {
                        if !continues_after_failed_join(lan, signed_in) {
                            return Err(reason);
                        }
                        log::warn!("Authentication failed: {}", reason.text);
                    }
                }"""
    net_source = net_source[:auth_start] + auth_replacement + net_source[auth_end:]
elif "mobilehorizon_authenticate_if_present(auth" in auth_block:
    pass
else:
    raise SystemExit(f"{net}: unsupported online authentication block layout")

# Update the upstream handshake integration test when this Horizon revision has one.
# Older stable releases do not include that test, so the dedicated helper tests above
# remain mandatory on every supported revision.
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
elif "fn lan_login_continues_after_authentication_failure()" in net_source:
    raise SystemExit(f"{net}: an upstream LAN-auth test exists but its verified assertions changed")
net.write_text(net_source, encoding="utf-8")
