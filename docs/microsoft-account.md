# Microsoft sign-in configuration

The launcher implements Device Code sign-in and performs the complete Microsoft → Xbox Live → XSTS → Minecraft authentication chain. It then checks the Minecraft Store entitlements and profile. It does not accept a typed/offline account as proof of ownership.

## Required project setup

This repository intentionally has no hard-coded client ID. The maintainer must use an application registration they own.

1. Create an application under Microsoft Entra ID / App registrations with **personal Microsoft accounts** supported.
2. Enable public-client/device-code flows. This public native client must not embed a client secret.
3. Copy its Application (client) ID (GUID).
4. Submit that app ID for Minecraft Java API review at https://aka.ms/mce-reviewappid. Minecraft's API may reject an unapproved registration even when Microsoft OAuth succeeds.
5. Add the public ID as a GitHub repository **variable** named \`HORIZON_MSA_CLIENT_ID\`. It is an identifier, not a client secret. The Actions workflow reads this variable when it builds the APK.
6. For local builds, pass \`-PHORIZON_MSA_CLIENT_ID=<your-app-id>\` to Gradle.

Never copy the client ID from the official Minecraft Launcher or another third-party launcher. If the variable is unset, the build remains possible but the sign-in UI explains that authentication is not configured.

## Flow implemented in this app

- Microsoft Device Code flow uses the consumer OAuth endpoint and the \`XboxLive.signin offline_access\` scopes.
- Microsoft access token → Xbox Live user token → XSTS token → Minecraft access token.
- \`/entitlements/mcstore\` must contain a recognized Java entitlement (\`game_minecraft\` or \`product_minecraft\`); the app also requires a valid Minecraft profile and UUID.
- The refresh token is encrypted using AES-GCM and an Android Keystore key. No access token or refresh token is written to the regular account preferences or logs.
- Before launch, the app refreshes the token chain and checks entitlement/profile again. It writes a short-lived session JSON under its private files directory with mode 0600, then hands its path to the native client through \`HORIZON_SESSION_FILE\`. The bundled engine consumes and deletes that file.

The native session bridge does not make the game runnable without a complete Horizon resource tree. The exporter that creates \`horizon-resources.json\`, gameplay tables and worldgen tables is tracked separately in the roadmap. Do not claim gameplay is working merely because Microsoft login or the Vulkan smoke test succeeds.
