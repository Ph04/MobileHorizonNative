package dev.mobilehorizon.compat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompatibilityCheckerTest {
    @Test
    fun acceptsPortableImporterOnSupportedAndroid() {
        val device = DeviceCapabilities(
            sdkInt = 35,
            supportedAbis = listOf("arm64-v8a"),
            canUseSaf = true,
            availableMemoryBytes = 1_000_000_000L,
        )
        val backend = BackendDescriptor(
            id = "android-saf-importer",
            version = "0.1.0",
            supportedAbis = emptySet(),
            requiredMinSdk = 26,
            capabilities = setOf(BackendCapability.IMPORT_OUTPUT, BackendCapability.REPORT_PROGRESS),
        )

        val report = CompatibilityChecker.check(device, backend)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertTrue(report.canRun)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun rejectsUnsupportedSdkAbiAndMissingSafWhenImporterNeedsIt() {
        val device = DeviceCapabilities(
            sdkInt = 24,
            supportedAbis = listOf("x86"),
            canUseSaf = false,
        )
        val backend = BackendDescriptor(
            id = "arm64-importer",
            version = "0.1.0",
            supportedAbis = setOf("arm64-v8a"),
            requiredMinSdk = 26,
            capabilities = setOf(BackendCapability.IMPORT_OUTPUT),
        )

        val report = CompatibilityChecker.check(device, backend)

        assertEquals(CompatibilityStatus.INCOMPATIBLE, report.status)
        assertFalse(report.canRun)
        assertEquals(
            setOf("ANDROID_VERSION_UNSUPPORTED", "ABI_MISMATCH", "STORAGE_ACCESS_UNAVAILABLE"),
            report.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun doesNotRequireSafForAHeadlessPreparationBackend() {
        val device = DeviceCapabilities(35, listOf("arm64-v8a"), canUseSaf = false)
        val backend = BackendDescriptor(
            id = "headless-preparer",
            version = "0.1.0",
            supportedAbis = emptySet(),
            requiredMinSdk = 26,
            capabilities = setOf(BackendCapability.PREPARE_FILES, BackendCapability.REPORT_PROGRESS),
        )

        assertEquals(CompatibilityStatus.COMPATIBLE, CompatibilityChecker.check(device, backend).status)
    }

    @Test
    fun refusesHorizonBackendWithoutPinnedNativeHash() {
        val device = DeviceCapabilities(35, listOf("arm64-v8a"), canUseSaf = true)
        val backend = BackendDescriptor(
            id = "horizon",
            version = "0.9.9",
            supportedAbis = setOf("arm64-v8a"),
            requiredMinSdk = 26,
            capabilities = setOf(BackendCapability.HORIZON_INTEGRATION),
        )

        val report = CompatibilityChecker.check(device, backend)

        assertEquals(CompatibilityStatus.INCOMPATIBLE, report.status)
        assertTrue(report.issues.any { it.code == "NATIVE_HASH_MISSING" && it.blocking })
    }

    @Test
    fun rejectsMalformedArtifactHash() {
        val backend = BackendDescriptor(
            id = "native-renderer",
            version = "dev",
            supportedAbis = setOf("arm64-v8a"),
            requiredMinSdk = 26,
            capabilities = setOf(BackendCapability.HORIZON_INTEGRATION),
            artifactSha256 = "not-a-sha256",
        )
        val report = CompatibilityChecker.check(
            DeviceCapabilities(35, listOf("arm64-v8a"), true),
            backend,
        )

        assertTrue(report.issues.any { it.code == "ARTIFACT_HASH_INVALID" && it.blocking })
    }

    @Test
    fun progressFractionIsBounded() {
        assertEquals(0.5f, PreparationProgress("copy", 5, 10, "copiando").fraction)
        assertEquals(1.0f, PreparationProgress("copy", 20, 10, "copiando").fraction)
        assertNotNull(PreparationProgress("copy", 0, 0, "copiando").fraction.let { it ?: 1.0f })
        assertEquals(null, PreparationProgress("scan", 1, null, "analisando").fraction)
    }
}
