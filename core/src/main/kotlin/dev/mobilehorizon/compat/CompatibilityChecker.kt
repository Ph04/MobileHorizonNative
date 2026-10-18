package dev.mobilehorizon.compat

/** Conservative checks only: a passing report does not replace device-level runtime tests. */
object CompatibilityChecker {
    fun check(device: DeviceCapabilities, backend: BackendDescriptor): CompatibilityReport {
        val issues = mutableListOf<CompatibilityIssue>()

        if (device.sdkInt < backend.requiredMinSdk) {
            issues += CompatibilityIssue(
                code = "ANDROID_VERSION_UNSUPPORTED",
                message = "Backend requires Android API ${backend.requiredMinSdk}; device reports API ${device.sdkInt}.",
                blocking = true,
            )
        }

        if (backend.supportedAbis.isNotEmpty() && device.supportedAbis.none { it in backend.supportedAbis }) {
            issues += CompatibilityIssue(
                code = "ABI_MISMATCH",
                message = "No device ABI matches the backend's supported ABIs.",
                blocking = true,
            )
        }

        if (!device.canUseSaf) {
            issues += CompatibilityIssue(
                code = "STORAGE_ACCESS_UNAVAILABLE",
                message = "Android document access is unavailable; choose another supported input method.",
                blocking = true,
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
