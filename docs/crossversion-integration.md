# CrossVersion built-in integration

MobileHorizonNative integrates the MIT-licensed `horizon-crossversion` v0.7.1
source directly into the Horizon client runtime. It is a built-in engine feature,
not an importable `.hnmod` or WIT `.hmod` package.

- Upstream: https://github.com/JustKarkat/horizon-crossversion
- Source revision: `83032a8b4a4d800a5c59dd63a2003519ce0f2f3b`
- Derived files: `mixins/crossversion/engine.rs` and `mixins/crossversion/decisions.rs`
- Local sources: `tools/crossversion/engine.rs` and `tools/crossversion/decisions.rs`
- Adapter: `tools/horizon_patches/11_crossversion_builtin.py`
- Compatibility check: `tools/test_android_crossversion.py`
- ViaProxy: `3.4.14`, downloaded only when needed, with its SHA-256 pinned in both
  the app download manager and the engine source. ViaProxy is GPL-3.0 and is not
  bundled in the APK or runtime.
- Android Java: the adapter launches the Java 25 runtime already installed by the
  launcher through `/system/bin/linker64`; it does not install a second JVM.

## Runtime behavior

The patched Horizon client routes normal and transferred server connections
through the CrossVersion decision layer. A 26.3 server stays direct. An older
protocol is sent to a ViaProxy process bound to loopback; the client still speaks
its own 26.3 protocol. Server-list pings can show the translated server version,
and manual per-address target-version overrides are read from the app-owned
configuration. The proxy is released when the game session ends. This translates
network protocol traffic only; it does not make old mods, worlds or resource packs
compatible.

## Offline accounts and older servers

When no Microsoft account is selected in the ViaProxy account manager, the engine
starts ViaProxy with `--auth-method NONE`. This is ViaProxy's offline backend
authentication mode. The Android Horizon login patch also allows a no-account
profile to complete the encrypted protocol challenge instead of rejecting it
locally; the destination server then decides whether to admit that player name.

This is intended to support older servers that permit offline players
(`online-mode=false`) when their protocol is supported by ViaProxy. It does not
bypass premium authentication: servers that require Microsoft/Mojang online-mode
authentication still require a valid account. Server plugins, whitelists, UUID
policies, proxy networks or name restrictions may also reject an offline profile.
Offline UUIDs and premium UUIDs are different, so permissions, inventories or
skins tied to a premium UUID are not guaranteed to match. If automatic version
detection cannot identify a legacy server, the per-address target-version
override can be used.

The Rust tests cover the no-account handshake and the `NONE` ViaProxy launch
argument independently. A real Android connection to an offline-mode server is
still required for end-to-end confirmation.

The Android app owns `filesDir/crossversion/config.json` and the private
ViaProxy directory. It supplies those paths and the existing Java runtime to the
engine as process environment variables. Microsoft device-code sign-in is handled
by the foreground Android service and the ViaProxy-owned account store.

## Update isolation

Only the adapter's small, checked anchors modify Horizon's `net.rs`; the Rust
implementation is copied into separate `crossversion_engine.rs` and
`crossversion_decisions.rs` files and included as a client module. The preflight
checks both connection paths, disconnect cleanup, protocol badge, app/engine
configuration agreement, Java paths, ViaProxy checksum, and Android UI/service
registration. Upstream anchor or API drift therefore fails the runtime build
instead of publishing a partially integrated engine.

The runtime publisher fingerprints all files under `tools/crossversion/` as
build inputs. Any CrossVersion source change creates a new immutable Android
runtime ID, so the app's existing verified-runtime updater downloads it without
requiring an APK update. The upstream MIT notice is retained in the derived
source and in `tools/crossversion/LICENSE`.

The upstream v0.7.1 `compatibility.json` pins Horizon
`07b7ea30d02973e7bc7579ea1e914dac2a9eca01` and horizon-mixins
`d9c83d7096b7ea5b83678da2efdfd6161f74997e` (mixin schema 6). The Android port
does not load the original native-mod registry or WIT package. It ports the
engine/decision logic, replaces desktop mixin UI with Android launcher controls,
and applies small checked hooks to the current Horizon client. Runtime preflight
and Cargo client tests gate each new upstream Nightly.

## Supported Horizon networking API revisions

The Android overlay's CrossVersion adapter recognizes two verified
`Session::connect` API shapes: the older `view_distance` signature used by the
stable `v0.2.81` validation pin, and the newer `ClientInformation/resources/lan`
signature used by Nightly `c9d474d`. It requires exactly one supported shape for
both normal and transferred connections. Unknown upstream changes stop the
runtime build before publication.
