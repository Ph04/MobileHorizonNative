package dev.mobilehorizon.compat

/** Capabilities detected at runtime; do not infer support from device model alone. */
data class DeviceCapabilities(
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val canUseSaf: Boolean,
    val availableMemoryBytes: Long? = null,
)

data class BackendDescriptor(
    val id: String,
    val version: String,
    val supportedAbis: Set<String>,
    val requiredMinSdk: Int,
    val capabilities: Set<BackendCapability>,
    val artifactSha256: String? = null,
)

enum class BackendCapability {
    PREPARE_FILES,
    HORIZON_INTEGRATION,
    IMPORT_OUTPUT,
    REPORT_PROGRESS,
}

enum class CompatibilityStatus { COMPATIBLE, COMPATIBLE_WITH_WARNINGS, INCOMPATIBLE }

data class CompatibilityIssue(
    val code: String,
    val message: String,
    val blocking: Boolean,
)

data class CompatibilityReport(
    val status: CompatibilityStatus,
    val backendId: String,
    val issues: List<CompatibilityIssue>,
) {
    val canRun: Boolean get() = status != CompatibilityStatus.INCOMPATIBLE
}

/** A backend must explicitly declare capabilities and provide truthful progress. */
interface PreparationBackend {
    val descriptor: BackendDescriptor
    suspend fun prepare(request: PreparationRequest, progress: (PreparationProgress) -> Unit): PreparationResult
}

data class PreparationRequest(
    /** App-managed URI or SAF URI; avoid hard-coded shared-storage paths. */
    val inputUri: String,
    val outputUri: String,
    val verifyOutput: Boolean = true,
)

data class PreparationProgress(
    val stage: String,
    val completedUnits: Long,
    val totalUnits: Long?,
    val message: String,
) {
    val fraction: Float?
        get() = totalUnits?.takeIf { it > 0 }?.let { (completedUnits.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

data class PreparationResult(
    val success: Boolean,
    val outputUri: String?,
    val issues: List<CompatibilityIssue> = emptyList(),
)
