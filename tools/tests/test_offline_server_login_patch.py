#!/usr/bin/env python3
"""Regression tests for the Horizon offline/hybrid login source patch."""

from pathlib import Path
import runpy
import tempfile
import unittest

PATCH = Path(__file__).resolve().parents[1] / "horizon_patches" / "01_offline_server_login.py"

ENCRYPTING = '\n                status(send, &tr(lang, "connect.encrypting"));'

LEGACY_SOURCE = """                if hello.should_authenticate {
                    let hash = server_hash(&hello.server_id, &secret, &hello.public_key);
                    crate::auth::join_server(
                        auth.ok_or_else(|| NOT_SIGNED_IN.to_owned())?,
                        &hash,
                    )?;
                }""" + ENCRYPTING

CURRENT_SOURCE = """                if hello.should_authenticate {
                    let signed_in = auth.is_some();
                    let authenticated = auth
                        .ok_or_else(|| Reason::keyed("horizon.multiplayer.not_signed_in", lang))
                        .and_then(|account| {
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
                }""" + ENCRYPTING


class OfflineServerLoginPatchTest(unittest.TestCase):
    def apply_patch(self, source):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "net.rs"
            target.write_text(source, encoding="utf-8")
            namespace = runpy.run_path(str(PATCH), init_globals={"net": target})
            return target.read_text(encoding="utf-8"), namespace["patch_offline_login"], target

    def assert_hybrid_behavior(self, patched):
        self.assertIn("if let Some(account) = auth", patched)
        self.assertIn("crate::auth::join_server(account, &hash)", patched)
        self.assertIn("if let Err(err) =", patched)
        self.assertIn("online-mode challenge received without a Microsoft session", patched)
        self.assertIn(ENCRYPTING.strip(), patched)
        self.assertNotIn("auth.ok_or_else(|| NOT_SIGNED_IN.to_owned())?", patched)

    def test_current_nightly_authentication_layout_is_patched(self):
        patched, _, _ = self.apply_patch(CURRENT_SOURCE)
        self.assert_hybrid_behavior(patched)
        self.assertIn("return Err(Reason {", patched)
        self.assertIn("key,", patched)

    def test_legacy_authentication_layout_is_still_supported(self):
        patched, _, _ = self.apply_patch(LEGACY_SOURCE)
        self.assert_hybrid_behavior(patched)
        self.assertIn("return Err(plain(", patched)

    def test_patch_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "net.rs"
            target.write_text(CURRENT_SOURCE, encoding="utf-8")
            namespace = runpy.run_path(str(PATCH), init_globals={"net": target})
            patched_once = target.read_text(encoding="utf-8")
            self.assertFalse(namespace["patch_offline_login"](target))
            self.assertFalse(namespace["patch_offline_login"](target))
            self.assertEqual(target.read_text(encoding="utf-8"), patched_once)

    def test_unknown_authentication_layout_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "net.rs"
            target.write_text("                if hello.should_authenticate {\n                    changed();\n                }" + ENCRYPTING,
                              encoding="utf-8")
            with self.assertRaises(SystemExit):
                runpy.run_path(str(PATCH), init_globals={"net": target})


if __name__ == "__main__":
    unittest.main(verbosity=2)
