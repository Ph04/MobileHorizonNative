package dev.mobilehorizon.compat

/** Conservative, capability-driven checks; passing does not replace tests on a real device. */
object CompatibilityChecker {
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    fun check(device: DeviceCapabilities, backend: BackendDescriptor): CompatibilityReport {
        val issues = mutableListOf<CompatibilityIssue>()

        if (backend.id.isBlank() || backend.version.isBlank()) {
            issues += CompatibilityIssue(
                code = "INVALID_BACKEND_DESCRIPTOR",
                message = "The backend must declare a non-empty id and version.",
                blocking = true,
            )
        }

        if (device.sdkInt < backend.requiredMinSdk) {
            issues += CompatibilityIssue(
                code = "ANDROID_VERSION_UNSUPPORTED",
                message = "Backend requires Android API ${backend.requiredMinSdk}; device reports API ${device.sdkInt}.",
                blocking = true,
            )
        }

        if (backend.supportedAbis.isNotEmpty() &&
            device.supportedAbis.none { it in backend.supportedAbis }) {
            issues += CompatibilityIssue(
                code = "ABI_MISMATCH",
                message = "No device ABI matches the backend's supported ABIs.",
                blocking = true,
            )
        }

        if (BackendCapability.IMPORT_OUTPUT in backend.capabilities && !device.canUseSaf) {
            issues += CompatibilityIssue(
                code = "STORAGE_ACCESS_UNAVAILABLE",
                message = "Android document access is unavailable; output import cannot run.",
                blocking = true,
            )
        }

        val digest = backend.artifactSha256
        if (digest != null && !sha256Pattern.matches(digest)) {
            issues += CompatibilityIssue(
                code = "ARTIFACT_HASH_INVALID",
                message = "The backend artifact SHA-256 must contain exactly 64 hexadecimal characters.",
                blocking = true,
            )
        }

        if (BackendCapability.HORIZON_INTEGRATION in backend.capabilities && digest == null) {
            issues += CompatibilityIssue(
                code = "NATIVE_HASH_MISSING",
                message = "The Horizon backend has no pinned SHA-256; refusing to mark the native runtime compatible.",
                blocking = true,
            )
        }

        if (BackendCapability.PREPARE_FILES in backend.capabilities &&
            BackendCapability.REPORT_PROGRESS !in backend.capabilities) {
            issues += CompatibilityIssue(
                code = "PROGRESS_UNAVAILABLE",
                message = "This backend does not declare progress reporting.",
                blocking = false,
            )
        }

        val status = when {
            issues.any { it.blocking } -> CompatibilityStatus.INCOMPATIBLE
            issues.isNotEmpty() -> CompatibilityStatus.COMPATIBLE_WITH_WARNINGS
            else -> CompatibilityStatus.COMPATIBLE
        }
        return CompatibilityReport(status, backend.id, issues)
    }
}
