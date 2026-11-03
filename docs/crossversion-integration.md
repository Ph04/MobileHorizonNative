# CrossVersion built-in integration

This integration is based on the MIT-licensed horizon-crossversion project.

- Upstream: https://github.com/JustKarkat/horizon-crossversion
- Source revision: 83032a8b4a4d800a5c59dd63a2003519ce0f2f3b
- Original files: mixins/crossversion/engine.rs and mixins/crossversion/decisions.rs
- ViaProxy: 3.4.14, downloaded only when required, GPL-3.0, SHA-256 pinned in the source.
- The app's Android adaptation launches its already-installed Java 25 runtime through /system/bin/linker64.

The upstream MIT notice is retained verbatim at the top of each derived source file.
ViaProxy is not bundled or modified. Its upstream licence remains applicable to the downloaded JAR.
