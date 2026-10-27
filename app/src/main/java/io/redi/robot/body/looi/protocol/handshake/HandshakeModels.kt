package io.redi.robot.body.looi.protocol.handshake

private const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805F9B34FB"
private const val HANDSHAKE_SERVICE_UUID_0000FF = "000000FF-0000-1000-8000-00805F9B34FB"

internal const val HANDSHAKE_DEFAULT_STEP_TIMEOUT_MS = 2_000L

enum class HandshakeCandidateId {
    A,
    B,
}

enum class HandshakeTransportStatusReason {
    TIMEOUT,
    SUCCESS,
    MISSING_CHARACTERISTIC,
    MISSING_NOTIFICATION_SUBSCRIPTION,
    CANCELLED,
    ERROR,
}

data class HandshakeOperation(
    val kind: HandshakeOperationKind,
    val serviceUuid: String,
    val characteristicUuid: String,
    val payload: List<Int> = emptyList(),
    val withResponse: Boolean = true,
    val timeoutMs: Long = HANDSHAKE_DEFAULT_STEP_TIMEOUT_MS,
)

enum class HandshakeOperationKind {
    WRITE,
    READ,
}

data class HandshakeStep(
    val id: String,
    val operation: HandshakeOperation? = null,
    val requiredActiveSubscriptions: Set<String> = emptySet(),
    val timeoutMs: Long = HANDSHAKE_DEFAULT_STEP_TIMEOUT_MS,
) {
    init {
        require((operation != null) || requiredActiveSubscriptions.isNotEmpty()) {
            "Step must include either an operation or subscription preconditions."
        }
        if (operation != null) {
            require(timeoutMs > 0) { "Step timeout must be positive when an operation is configured." }
        }
    }
}

data class HandshakeCandidate(
    val id: HandshakeCandidateId,
    val name: String,
    val steps: List<HandshakeStep>,
)

data class HandshakeStepResult(
    val stepIndex: Int,
    val stepId: String,
    val success: Boolean,
    val failureReason: String?,
    val startedAtMs: Long,
    val completedAtMs: Long? = null,
)

data class HandshakeAttemptSummary(
    val generation: Long,
    val candidate: HandshakeCandidateId,
    val running: Boolean,
    val completed: Boolean,
    val cancelled: Boolean,
    val featureEnabled: Boolean,
    val stepResults: List<HandshakeStepResult>,
    val protocolReady: Boolean = false,
    val failureReason: String? = null,
)

interface HandshakeTransportResult

data class HandshakeTransportSuccess(
    val payload: List<Int> = emptyList(),
) : HandshakeTransportResult

data class HandshakeTransportFailure(
    val reason: HandshakeTransportStatusReason,
    val detail: String,
) : HandshakeTransportResult

interface HandshakeTransport {
    fun execute(operation: HandshakeOperation, completion: (HandshakeTransportResult) -> Unit): Long
    fun cancel(operationHandle: Long)
}

fun interface HandshakeNotificationSubscriptionState {
    fun isSubscribed(generation: Long, characteristicShortUuid: String): Boolean
}

private fun normalizeShortUuid(short: String): String = short.trim().uppercase()

internal fun buildFullUuid(shortUuid: String): String =
    when (val normalized = normalizeShortUuid(shortUuid)) {
        "FF" -> HANDSHAKE_SERVICE_UUID_0000FF
        else -> {
            val normalizedShort = when (normalized.length) {
                4 -> "0000$normalized"
                8 -> normalized
                else -> throw IllegalArgumentException("Unsupported UUID short form: $shortUuid")
            }
            "$normalizedShort$BLUETOOTH_BASE_SUFFIX"
        }
    }

private fun asciiBytes(value: String): List<Int> = value.toByteArray(Charsets.UTF_8).map { it.toInt() }

private val HANDSHAKE_TIMEFRAME_PAYLOAD: List<Int> = asciiBytes("20260824010101010101")

val HANDSHAKE_CANDIDATE_A: HandshakeCandidate = HandshakeCandidate(
    id = HandshakeCandidateId.A,
    name = "candidate-a",
    steps = listOf(
        HandshakeStep(
            id = "a-01-initiate-handshake",
            operation = HandshakeOperation(
                kind = HandshakeOperationKind.WRITE,
                serviceUuid = buildFullUuid("FF"),
                characteristicUuid = buildFullUuid("FEDA"),
                payload = listOf(0x01),
                withResponse = true,
                timeoutMs = 2_000L,
            ),
        ),
        HandshakeStep(
            id = "a-02-required-notification-preconditions",
            requiredActiveSubscriptions = setOf("FED5", "FED9"),
            timeoutMs = 2_000L,
        ),
        HandshakeStep(
            id = "a-03-activate-handshake",
            operation = HandshakeOperation(
                kind = HandshakeOperationKind.WRITE,
                serviceUuid = buildFullUuid("FF"),
                characteristicUuid = buildFullUuid("FEDA"),
                payload = listOf(0x03),
                withResponse = true,
                timeoutMs = 2_000L,
            ),
            requiredActiveSubscriptions = setOf("FED5", "FED9"),
            timeoutMs = 2_000L,
        ),
        HandshakeStep(
            id = "a-04-read-power",
            operation = HandshakeOperation(
                kind = HandshakeOperationKind.READ,
                serviceUuid = buildFullUuid("FF"),
                characteristicUuid = buildFullUuid("FED8"),
                payload = emptyList(),
                withResponse = true,
                timeoutMs = 2_000L,
            ),
            timeoutMs = 2_000L,
        ),
    ),
)

val HANDSHAKE_CANDIDATE_B: HandshakeCandidate = HandshakeCandidate(
    id = HandshakeCandidateId.B,
    name = "candidate-b",
    steps = listOf(
        HandshakeStep(
            id = "b-01-required-notification-preconditions",
            requiredActiveSubscriptions = setOf("FED9", "FEF0"),
            timeoutMs = 2_000L,
        ),
        HandshakeStep(
                id = "b-02-activate-handshake",
            operation = HandshakeOperation(
                kind = HandshakeOperationKind.WRITE,
                serviceUuid = buildFullUuid("FF"),
                characteristicUuid = buildFullUuid("FEDA"),
                payload = listOf(0x03),
                withResponse = true,
                timeoutMs = 2_000L,
            ),
            requiredActiveSubscriptions = setOf("FED9", "FEF0"),
            timeoutMs = 2_000L,
        ),
        HandshakeStep(
            id = "b-03-write-timeframe",
            operation = HandshakeOperation(
                kind = HandshakeOperationKind.WRITE,
                serviceUuid = buildFullUuid("FF"),
                characteristicUuid = buildFullUuid("FEF0"),
                payload = HANDSHAKE_TIMEFRAME_PAYLOAD,
                withResponse = true,
                timeoutMs = 2_000L,
            ),
            requiredActiveSubscriptions = setOf("FED9", "FEF0"),
            timeoutMs = 2_000L,
        ),
        HandshakeStep(
            id = "b-04-finalize-handshake",
            operation = HandshakeOperation(
                kind = HandshakeOperationKind.WRITE,
                serviceUuid = buildFullUuid("FF"),
                characteristicUuid = buildFullUuid("FEDA"),
                payload = listOf(0x81, 0x01),
                withResponse = true,
                timeoutMs = 2_000L,
            ),
            requiredActiveSubscriptions = setOf("FED9", "FEF0"),
            timeoutMs = 2_000L,
        ),
    ),
)

val HANDSHAKE_CANDIDATE_BY_ID: Map<HandshakeCandidateId, HandshakeCandidate> = mapOf(
    HandshakeCandidateId.A to HANDSHAKE_CANDIDATE_A,
    HandshakeCandidateId.B to HANDSHAKE_CANDIDATE_B,
)
