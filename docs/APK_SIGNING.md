# Signing APKs so they can update each other

Android accepts an update only when the package name and signing certificate match. Earlier MobileHorizonNative builds used generated GitHub-runner debug keys; two recent artifacts had different certificate fingerprints. A new build signed with a different key cannot update an older install in place.

Future update-safe artifacts are published only when all four repository secrets are configured:

- `MOBILEHORIZON_KEYSTORE_BASE64`: base64 text of a private JKS/PKCS12 keystore.
- `MOBILEHORIZON_KEYSTORE_PASSWORD`: keystore password.
- `MOBILEHORIZON_KEY_ALIAS`: alias in that keystore.
- `MOBILEHORIZON_KEY_PASSWORD`: password for that alias.

Keep the keystore and passwords private. Do not commit them to this public repository and do not put them in Actions caches or logs. Every future APK must use the same key and package ID. The first APK signed with a new stable key still cannot replace older APKs signed by a different key; Android will reject the installation rather than merge their private data. Do not uninstall the old app until its resources/data have been backed up or a migration path is available.

The Android build workflow will continue to run tests and validate compilation without the secrets, but it will not publish a one-off-signed APK as an updateable artifact.
