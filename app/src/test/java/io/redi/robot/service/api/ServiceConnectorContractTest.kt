package io.redi.robot.service.api

import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityCompatibility
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityExecutionContext
import io.redi.robot.capability.api.CapabilityFailure
import io.redi.robot.capability.api.CapabilityFailureCode
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityOutcomeEvidence
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityResultState
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.CapabilityCancellationRequest
import io.redi.robot.capability.provider.CapabilityNegotiationEntry
import io.redi.robot.capability.provider.CapabilityNegotiationRequest
import io.redi.robot.capability.provider.CapabilityNegotiationResult
import io.redi.robot.capability.provider.CapabilityNegotiationState
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.CapabilityRegistration
import io.redi.robot.capability.provider.CapabilityRegistrationRequest
import io.redi.robot.capability.provider.CapabilityRegistrationResult
import io.redi.robot.capability.provider.ProviderAvailabilityReason
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionAvailability
import io.redi.robot.connection.api.ConnectionAvailabilityReason
import io.redi.robot.connection.api.ConnectionAvailabilitySnapshot
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServiceConnectorContractTest {
    @Test
    fun `fake service extends H008 and registers exactly declared capabilities`() {
        val fixture = fixture()
        val connector: ServiceConnector = fixture.connector()

        val registrations = connector.registerCapabilities(
            CapabilityRegistrationRequest.create(fixture.generation),
        )

        assertEquals(ProviderFamily.SERVICE_CONNECTOR, connector.identity.family)
        assertEquals(connector.identity.instanceId, registrations.providerInstanceId)
        assertEquals(fixture.generation, registrations.generation)
        assertEquals(
            fixture.contract.operations.map { it.capability },
            registrations.registrations.map { it.descriptor.reference },
        )
        assertEquals(fixture.snapshot, connector.serviceSnapshot())
    }

    @Test
    fun `authenticated scope connection and rate evidence satisfy technical preconditions only`() {
        val fixture = fixture()

        val assessment = fixture.assess()

        assertEquals(ServiceOperationAssessmentStatus.PRECONDITIONS_SATISFIED, assessment.status)
        assertTrue(assessment.findings.isEmpty())
        assertEquals(setOf(fixture.scope), assessment.matchedScopes)
        assertTrue(assessment.missingScopes.isEmpty())
        assertEquals(99, assessment.remainingRateUnits)
        assertNull(assessment.retryAt)
        assertFalse(assessment.grantsTrust)
        assertFalse(assessment.grantsAuthorization)
        assertFalse(assessment.establishesConnection)
        assertFalse(assessment.executesOperation)
    }

    @Test
    fun `service authentication is external-session evidence not H014 authority`() {
        val fixture = fixture(auth = unauthenticated(generation(7), observedAt = 100))
        val assessment = fixture.assess()

        assertEquals(ServiceOperationAssessmentStatus.BLOCKED, assessment.status)
        assertTrue(ServiceOperationFinding.AUTHENTICATION_REQUIRED in assessment.findings)
        assertTrue(ServiceOperationFinding.MISSING_SCOPE in assessment.findings)
        assertFalse(assessment.grantsAuthorization)

        val expired = fixture(auth = authenticated(generation(7), expiresAt = 120)).assess(now = 150)
        val revoked = fixture(auth = revoked(generation(7))).assess()
        val failed = fixture(auth = failedAuth(generation(7))).assess()
        assertTrue(ServiceOperationFinding.AUTHENTICATION_EXPIRED in expired.findings)
        assertTrue(ServiceOperationFinding.AUTHENTICATION_REVOKED in revoked.findings)
        assertTrue(ServiceOperationFinding.AUTHENTICATION_FAILED in failed.findings)

        val undeclaredScopeAuth = success(
            ServiceAuthenticationSnapshot.create(
                generation(7),
                ServiceAuthenticationState.AUTHENTICATED,
                session(),
                setOf(scope("scope.calendar/write@1.0")),
                time(100),
                time(500),
                null,
            ),
        )
        val undeclaredScope = fixture(auth = undeclaredScopeAuth).assess()
        assertTrue(ServiceOperationFinding.AUTHENTICATION_SCOPE_MISMATCH in undeclaredScope.findings)
    }

    @Test
    fun `rate limited unknown and stale evidence remain distinct`() {
        val limitedFixture = fixture(rateRemaining = 0, rateReset = 500)
        val limited = limitedFixture.assess(now = 150)
        assertEquals(ServiceOperationAssessmentStatus.BLOCKED, limited.status)
        assertTrue(ServiceOperationFinding.RATE_LIMITED in limited.findings)
        assertEquals(500L, limited.retryAt?.value)

        val unknownFixture = fixture(rateState = ServiceRateEvidenceState.UNKNOWN)
        val unknown = unknownFixture.assess()
        assertEquals(ServiceOperationAssessmentStatus.UNKNOWN, unknown.status)
        assertTrue(ServiceOperationFinding.RATE_STATE_UNKNOWN in unknown.findings)

        val stale = fixture(rateReset = 140).assess(now = 150)
        assertEquals(ServiceOperationAssessmentStatus.UNKNOWN, stale.status)
        assertTrue(ServiceOperationFinding.RATE_EVIDENCE_STALE in stale.findings)
    }

    @Test
    fun `connection unavailable missing and stale evidence fail without pretending to connect`() {
        val unavailable = fixture(connectionAvailability = ConnectionAvailability.UNAVAILABLE).assess()
        assertEquals(ServiceOperationAssessmentStatus.BLOCKED, unavailable.status)
        assertTrue(ServiceOperationFinding.CONNECTION_UNAVAILABLE in unavailable.findings)

        val missing = fixture(includeConnection = false).assess()
        assertEquals(ServiceOperationAssessmentStatus.UNKNOWN, missing.status)
        assertTrue(ServiceOperationFinding.CONNECTION_EVIDENCE_MISSING in missing.findings)

        val stale = fixture(connectionExpiry = 150).assess(now = 150)
        assertEquals(ServiceOperationAssessmentStatus.UNKNOWN, stale.status)
        assertTrue(ServiceOperationFinding.CONNECTION_EVIDENCE_STALE in stale.findings)
        assertFalse(stale.establishesConnection)
    }

    @Test
    fun `provider registration identity generation and operation mismatches are explicit`() {
        val fixture = fixture()
        val wrongFamily = fixture.assess(identity = identity(ProviderFamily.DEVICE_ADAPTER))
        val wrongDefinition = fixture.assess(identity = identity(definition = "test/other-service"))
        val wrongGeneration = fixture.assess(
            runtime = runtime(generation(8), ProviderRuntimeAvailability.AVAILABLE),
        )
        val wrongInstance = fixture.assess(
            registrations = registration(
                identity(instance = "99999999-9999-4999-8999-999999999999"),
                fixture.generation,
                listOf(fixture.descriptor),
            ),
        )
        val wrongRegistration = fixture.assess(
            registrations = registration(
                fixture.identity,
                fixture.generation,
                listOf(descriptor("calendar.events.create@1.0")),
            ),
        )
        val unsupported = fixture.assess(capability = reference("calendar.events.create@1.0"))

        assertTrue(ServiceOperationFinding.WRONG_PROVIDER_FAMILY in wrongFamily.findings)
        assertTrue(ServiceOperationFinding.PROVIDER_DEFINITION_MISMATCH in wrongDefinition.findings)
        assertTrue(ServiceOperationFinding.GENERATION_MISMATCH in wrongGeneration.findings)
        assertTrue(ServiceOperationFinding.PROVIDER_INSTANCE_MISMATCH in wrongInstance.findings)
        assertTrue(ServiceOperationFinding.CAPABILITY_REGISTRATION_MISMATCH in wrongRegistration.findings)
        assertTrue(ServiceOperationFinding.UNSUPPORTED_OPERATION in unsupported.findings)
        listOf(
            wrongFamily,
            wrongDefinition,
            wrongGeneration,
            wrongInstance,
            wrongRegistration,
            unsupported,
        ).forEach {
            assertEquals(ServiceOperationAssessmentStatus.BLOCKED, it.status)
        }
    }

    @Test
    fun `operation error model has bounded retry semantics and no vendor payload`() {
        val rateLimited = success(
            ServiceOperationFailure.create(
                ServiceOperationFailureCode.RATE_LIMITED,
                time(100),
                time(200),
            ),
        )
        val timeout = success(
            ServiceOperationFailure.create(ServiceOperationFailureCode.TIMEOUT, time(100), null),
        )

        assertEquals(200L, rateLimited.retryAt?.value)
        assertNull(timeout.retryAt)
        assertFailure(
            ServiceOperationFailure.create(
                ServiceOperationFailureCode.RATE_LIMITED,
                time(100),
                null,
            ),
            ServiceConnectorError.InvalidOperationFailure,
        )
        assertFailure(
            ServiceOperationFailure.create(
                ServiceOperationFailureCode.PERMANENT_FAILURE,
                time(100),
                time(200),
            ),
            ServiceConnectorError.InvalidOperationFailure,
        )
        assertFalse(ServiceOperationFailure::class.java.declaredFields.any {
            it.name.contains("message", true) || it.name.contains("payload", true) || it.name.contains("vendor", true)
        })
    }

    @Test
    fun `degraded and unavailable provider states remain explicit`() {
        val degraded = fixture(availability = ProviderRuntimeAvailability.DEGRADED).assess()
        val unavailable = fixture(availability = ProviderRuntimeAvailability.UNAVAILABLE).assess()

        assertEquals(ServiceOperationAssessmentStatus.UNKNOWN, degraded.status)
        assertTrue(ServiceOperationFinding.PROVIDER_DEGRADED in degraded.findings)
        assertEquals(ServiceOperationAssessmentStatus.BLOCKED, unavailable.status)
        assertTrue(ServiceOperationFinding.PROVIDER_UNAVAILABLE in unavailable.findings)
    }

    @Test
    fun `contract rejects unsupported versions duplicate or undeclared metadata`() {
        assertFailure(
            ServiceConnectorSchemaVersion.parse("2.0"),
            ServiceConnectorError.UnsupportedSchemaVersion("2.0"),
        )
        assertFailure(
            ServiceAuthenticationSchemeReference.parse("scope.calendar/read@1.0"),
            ServiceConnectorError.MalformedAuthenticationScheme,
        )
        assertFailure(
            ServiceAuthorizationScopeReference.parse("auth.standard/oauth@1.0"),
            ServiceConnectorError.MalformedAuthorizationScope,
        )

        val fixture = fixture()
        assertFailure(
            ServiceConnectorContract.create(
                fixture.schema,
                fixture.identity.definitionId,
                fixture.authScheme,
                setOf(fixture.scope),
                listOf(fixture.operation, fixture.operation),
                listOf(fixture.ratePolicy),
            ),
            ServiceConnectorError.DuplicateOperation,
        )
        val undeclaredScope = scope("scope.calendar/write@1.0")
        val operation = operation(requiredScopes = setOf(undeclaredScope))
        assertFailure(
            ServiceConnectorContract.create(
                fixture.schema,
                fixture.identity.definitionId,
                fixture.authScheme,
                setOf(fixture.scope),
                listOf(operation),
                listOf(fixture.ratePolicy),
            ),
            ServiceConnectorError.UndeclaredOperationScope,
        )
    }

    @Test
    fun `auth rate and aggregate snapshots reject contradictory evidence`() {
        assertFailure(
            ServiceAuthenticationSnapshot.create(
                generation(1),
                ServiceAuthenticationState.UNAUTHENTICATED,
                session(),
                emptySet(),
                time(100),
                null,
                null,
            ),
            ServiceConnectorError.AuthSnapshotConflict,
        )
        assertFailure(
            ServiceRateEvidence.create(
                generation(1),
                rateId(),
                ServiceRateEvidenceState.KNOWN,
                null,
                time(100),
                time(200),
            ),
            ServiceConnectorError.InvalidRateSnapshot,
        )

        val fixture = fixture()
        assertFailure(
            ServiceConnectorSnapshot.create(
                generation(8),
                fixture.snapshot.authentication,
                fixture.snapshot.connectionEvidence,
                fixture.snapshot.rateEvidence,
                time(100),
            ),
            ServiceConnectorError.SnapshotGenerationMismatch,
        )
    }

    @Test
    fun `contracts and snapshots defensively copy and deterministically order collections`() {
        val first = scope("scope.calendar/read@1.0")
        val second = scope("scope.calendar/write@1.0")
        val mutableScopes = linkedSetOf(second, first)
        val op = operation(requiredScopes = setOf(first))
        val contract = success(
            ServiceConnectorContract.create(
                schema(),
                identity().definitionId,
                authScheme(),
                mutableScopes,
                listOf(op),
                listOf(ratePolicy()),
            ),
        )
        mutableScopes.clear()

        assertEquals(listOf(first, second), contract.supportedScopes.toList())
        assertEquals(setOf(first), contract.operations.single().requiredScopes)
        assertTrue(contract.supportedScopes.isNotEmpty())
    }

    @Test
    fun `redacted fake trace is deterministic and contains no private auth or account data`() {
        val fixture = fixture()
        val assessment = fixture.assess()
        val encoded = ServiceConnectorTraceCodec.encode(
            ServiceConnectorTrace.from(fixture.identity, assessment),
        )
        val expected = checkNotNull(javaClass.getResource("/fixtures/h040-service-connector-trace-v1.json"))
            .readText()
            .trim()

        assertEquals(expected, encoded)
        listOf(
            fixture.identity.instanceId.value,
            requireNotNull(fixture.snapshot.authentication.sessionReference).value,
            fixture.scope.wireValue,
            "token",
            "account",
            "cookie",
        ).forEach { privateValue -> assertFalse(encoded.contains(privateValue, ignoreCase = true)) }
    }

    @Test
    fun `service API exposes no Android vendor SDK credential or transport implementation types`() {
        val source = sourceRoot("service/api").walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        listOf(
            "import android.",
            "import androidx.",
            "import okhttp3.",
            "import retrofit2.",
            "BluetoothGatt",
            "accessToken:",
            "refreshToken:",
            "accountId:",
            "httpMethod:",
        ).forEach { prohibited ->
            assertFalse("Unexpected service API coupling: $prohibited", source.contains(prohibited))
        }
        val connectorFields = ServiceConnector::class.java.methods.flatMap {
            it.parameterTypes.asList() + it.returnType
        }.map { it.name }
        assertFalse(connectorFields.any { it.startsWith("android.") || it.contains("okhttp", true) })
    }

    private fun fixture(
        auth: ServiceAuthenticationSnapshot? = null,
        availability: ProviderRuntimeAvailability = ProviderRuntimeAvailability.AVAILABLE,
        connectionAvailability: ConnectionAvailability = ConnectionAvailability.AVAILABLE,
        includeConnection: Boolean = true,
        connectionExpiry: Long = 500,
        rateState: ServiceRateEvidenceState = ServiceRateEvidenceState.KNOWN,
        rateRemaining: Int = 99,
        rateReset: Long = 500,
    ): Fixture {
        val identity = identity()
        val generation = generation(7)
        val scope = scope()
        val descriptor = descriptor()
        val operation = operation(requiredScopes = setOf(scope))
        val ratePolicy = ratePolicy()
        val contract = success(
            ServiceConnectorContract.create(
                schema(),
                identity.definitionId,
                authScheme(),
                setOf(scope),
                listOf(operation),
                listOf(ratePolicy),
            ),
        )
        val connection = if (includeConnection) {
            listOf(connectionEvidence(connectionAvailability, connectionExpiry))
        } else {
            emptyList()
        }
        val rate = success(
            ServiceRateEvidence.create(
                generation,
                ratePolicy.id,
                rateState,
                if (rateState == ServiceRateEvidenceState.KNOWN) rateRemaining else null,
                time(100),
                if (rateState == ServiceRateEvidenceState.KNOWN) time(rateReset) else null,
            ),
        )
        val snapshot = success(
            ServiceConnectorSnapshot.create(
                generation,
                auth ?: authenticated(generation),
                connection,
                listOf(rate),
                time(100),
            ),
        )
        return Fixture(
            identity,
            generation,
            scope,
            authScheme(),
            schema(),
            descriptor,
            operation,
            ratePolicy,
            contract,
            snapshot,
            availability,
        )
    }

    private inner class Fixture(
        val identity: ProviderIdentity,
        val generation: ProviderGeneration,
        val scope: ServiceAuthorizationScopeReference,
        val authScheme: ServiceAuthenticationSchemeReference,
        val schema: ServiceConnectorSchemaVersion,
        val descriptor: CapabilityDescriptor,
        val operation: ServiceOperationDefinition,
        val ratePolicy: ServiceRatePolicy,
        val contract: ServiceConnectorContract,
        val snapshot: ServiceConnectorSnapshot,
        val availability: ProviderRuntimeAvailability,
    ) {
        fun connector(): FakeServiceConnector = FakeServiceConnector(this)

        fun assess(
            identity: ProviderIdentity = this.identity,
            runtime: ProviderRuntimeSnapshot = runtime(generation, availability),
            registrations: CapabilityRegistrationResult = registration(
                this.identity,
                generation,
                listOf(descriptor),
            ),
            capability: CapabilityReference = descriptor.reference,
            now: Long = 150,
        ): ServiceOperationAssessment = ServiceOperationAssessor.assess(
            ServiceOperationAssessmentInput(
                identity,
                contract,
                runtime,
                registrations,
                snapshot,
                capability,
                time(now),
            ),
        )
    }

    private inner class FakeServiceConnector(private val fixture: Fixture) : ServiceConnector {
        override val identity: ProviderIdentity = fixture.identity
        override val serviceContract: ServiceConnectorContract = fixture.contract

        override fun serviceSnapshot(): ServiceConnectorSnapshot = fixture.snapshot

        override fun runtimeSnapshot(): ProviderRuntimeSnapshot = runtime(fixture.generation, fixture.availability)

        override fun negotiate(request: CapabilityNegotiationRequest): CapabilityNegotiationResult = portSuccess(
            CapabilityNegotiationResult.create(
                identity.instanceId,
                request,
                request.requestedCapabilities.map { requested ->
                    when (fixture.descriptor.reference.compatibilityWith(requested)) {
                        is CapabilityCompatibility.Exact,
                        is CapabilityCompatibility.BackwardCompatibleMinor,
                        -> CapabilityNegotiationEntry(
                            requested,
                            CapabilityNegotiationState.SUPPORTED,
                            fixture.descriptor.reference,
                        )
                        is CapabilityCompatibility.Incompatible -> CapabilityNegotiationEntry(
                            requested,
                            CapabilityNegotiationState.INCOMPATIBLE,
                        )
                    }
                },
            ),
        )

        override fun registerCapabilities(request: CapabilityRegistrationRequest): CapabilityRegistrationResult =
            registration(identity, request.generation, listOf(fixture.descriptor))

        override fun execute(request: CapabilityRequest): CapabilityResult = envelopeSuccess(
            CapabilityResult.create(
                request.requestId,
                request.capability,
                identity.instanceId,
                request.executionContext,
                time(200),
                CapabilityResultState.ACCEPTED,
                null,
                CapabilityOutcomeEvidence.REQUEST_ACCEPTED,
                null,
                request.auditId,
            ),
        )

        override fun cancel(request: CapabilityCancellationRequest): CapabilityResult = envelopeSuccess(
            CapabilityResult.create(
                request.requestId,
                request.capability,
                identity.instanceId,
                request.executionContext,
                request.requestedAt,
                CapabilityResultState.CANCELLED,
                CapabilityFailure(CapabilityFailureCode.CANCELLED),
                CapabilityOutcomeEvidence.LOCALLY_OBSERVED,
                null,
                request.auditId,
            ),
        )
    }

    private fun authenticated(
        generation: ProviderGeneration,
        expiresAt: Long = 500,
    ): ServiceAuthenticationSnapshot = success(
        ServiceAuthenticationSnapshot.create(
            generation,
            ServiceAuthenticationState.AUTHENTICATED,
            session(),
            setOf(scope()),
            time(100),
            time(expiresAt),
            null,
        ),
    )

    private fun unauthenticated(
        generation: ProviderGeneration,
        observedAt: Long,
    ): ServiceAuthenticationSnapshot = success(
        ServiceAuthenticationSnapshot.create(
            generation,
            ServiceAuthenticationState.UNAUTHENTICATED,
            null,
            emptySet(),
            time(observedAt),
            null,
            null,
        ),
    )

    private fun revoked(generation: ProviderGeneration): ServiceAuthenticationSnapshot = success(
        ServiceAuthenticationSnapshot.create(
            generation,
            ServiceAuthenticationState.REVOKED,
            session(),
            emptySet(),
            time(100),
            null,
            null,
        ),
    )

    private fun failedAuth(generation: ProviderGeneration): ServiceAuthenticationSnapshot = success(
        ServiceAuthenticationSnapshot.create(
            generation,
            ServiceAuthenticationState.FAILED,
            null,
            emptySet(),
            time(100),
            null,
            ServiceAuthenticationFailureCode.TEMPORARY_FAILURE,
        ),
    )

    private fun connectionEvidence(
        availability: ConnectionAvailability,
        expiresAt: Long,
    ): ServiceConnectionEvidence = success(
        ServiceConnectionEvidence.create(
            connectionSuccess(
                ConnectionAvailabilitySnapshot.create(
                    method(),
                    availability,
                    time(100),
                    if (availability == ConnectionAvailability.AVAILABLE) {
                        null
                    } else {
                        ConnectionAvailabilityReason.TEMPORARILY_UNAVAILABLE
                    },
                ),
            ),
            time(expiresAt),
        ),
    )

    private fun operation(
        requiredScopes: Set<ServiceAuthorizationScopeReference> = setOf(scope()),
    ): ServiceOperationDefinition = success(
        ServiceOperationDefinition.create(
            reference("calendar.events.list@1.0"),
            requiredScopes,
            method(),
            rateId(),
            1,
        ),
    )

    private fun ratePolicy(): ServiceRatePolicy = success(
        ServiceRatePolicy.create(rateId(), 100, 1_000),
    )

    private fun rateId(): ServiceRatePolicyId = success(ServiceRatePolicyId.parse("calendar.read"))

    private fun schema(): ServiceConnectorSchemaVersion = success(ServiceConnectorSchemaVersion.parse("1.0"))

    private fun authScheme(): ServiceAuthenticationSchemeReference = success(
        ServiceAuthenticationSchemeReference.parse("auth.standard/oauth@1.0"),
    )

    private fun scope(raw: String = "scope.calendar/read@1.0"): ServiceAuthorizationScopeReference = success(
        ServiceAuthorizationScopeReference.parse(raw),
    )

    private fun session(): ServiceAuthSessionReference = success(
        ServiceAuthSessionReference.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
    )

    private fun method(): ConnectionMethodReference = ConnectionMethodReference(
        connectionSuccess(ConnectionMethodId.parse("network.http/request-response")),
        connectionSuccess(ConnectionMethodVersion.parse("1.0")),
    )

    private fun runtime(
        generation: ProviderGeneration,
        availability: ProviderRuntimeAvailability,
    ): ProviderRuntimeSnapshot = portSuccess(
        ProviderRuntimeSnapshot.create(
            generation,
            availability,
            time(100),
            if (availability == ProviderRuntimeAvailability.AVAILABLE) {
                null
            } else {
                ProviderAvailabilityReason.TEMPORARILY_UNAVAILABLE
            },
        ),
    )

    private fun registration(
        identity: ProviderIdentity,
        generation: ProviderGeneration,
        descriptors: List<CapabilityDescriptor>,
    ): CapabilityRegistrationResult = portSuccess(
        CapabilityRegistrationResult.create(
            identity.instanceId,
            CapabilityRegistrationRequest.create(generation),
            descriptors.map(::CapabilityRegistration),
        ),
    )

    private fun identity(
        family: ProviderFamily = ProviderFamily.SERVICE_CONNECTOR,
        definition: String = "test/calendar-service",
        instance: String = "11111111-1111-4111-8111-111111111111",
    ): ProviderIdentity = ProviderIdentity(
        providerSuccess(ProviderDefinitionId.parse(definition)),
        providerSuccess(ProviderInstanceId.parse(instance)),
        family,
        providerSuccess(RedactedProviderAlias.parse("Calendar service")),
    )

    private fun descriptor(rawReference: String = "calendar.events.list@1.0"): CapabilityDescriptor = descriptorSuccess(
        CapabilityDescriptor.create(
            reference(rawReference),
            descriptorSuccess(CapabilityLabel.parse("List events")),
            descriptorSuccess(CapabilityDescription.parse("Read-only calendar event listing.")),
            null,
            null,
            CapabilityEffect.OBSERVE,
            CapabilityRiskClass.INFORMATIONAL,
            CapabilitySafetyClass.NON_PHYSICAL,
            CapabilityPermissionIntent.OBSERVE,
            CapabilityApprovalRequirement.WITHIN_GRANT,
            CapabilityCancellationSemantics.SUPPORTED,
            CapabilityAvailabilitySemantics.WHILE_REGISTERED,
            CapabilityEvidenceClass.DECLARED,
        ),
    )

    @Suppress("unused")
    private fun request(identity: ProviderIdentity): CapabilityRequest = envelopeSuccess(
        CapabilityRequest.create(
            envelopeSuccess(CapabilityRequestId.parse("22222222-2222-4222-8222-222222222222")),
            reference("calendar.events.list@1.0"),
            CapabilityProviderTarget.Exact(identity.instanceId),
            CapabilityActorKind.LOCAL_USER,
            envelopeSuccess(CapabilityExecutionContext.create(1, 1)),
            time(100),
            time(500),
            null,
            envelopeSuccess(CapabilityAuditId.parse("33333333-3333-4333-8333-333333333333")),
            null,
        ),
    )

    private fun reference(raw: String): CapabilityReference = identitySuccess(CapabilityReference.parse(raw))
    private fun generation(value: Long): ProviderGeneration = portSuccess(ProviderGeneration.create(value))
    private fun time(value: Long): MonotonicTimestampMs = envelopeSuccess(MonotonicTimestampMs.create(value))

    private fun <T> success(result: ServiceConnectorResult<T>): T = when (result) {
        is ServiceConnectorResult.Success -> result.value
        is ServiceConnectorResult.Failure -> throw AssertionError(result.error)
    }

    private fun assertFailure(result: ServiceConnectorResult<*>, expected: ServiceConnectorError) {
        assertTrue(result is ServiceConnectorResult.Failure)
        assertEquals(expected, (result as ServiceConnectorResult.Failure).error)
    }

    private fun <T> portSuccess(result: CapabilityProviderPortResult<T>): T =
        (result as CapabilityProviderPortResult.Success).value

    private fun <T> providerSuccess(result: ProviderIdentityResult<T>): T =
        (result as ProviderIdentityResult.Success).value

    private fun <T> identitySuccess(result: CapabilityIdentityResult<T>): T =
        (result as CapabilityIdentityResult.Success).value

    private fun <T> descriptorSuccess(result: CapabilityDescriptorResult<T>): T =
        (result as CapabilityDescriptorResult.Success).value

    private fun <T> envelopeSuccess(result: CapabilityEnvelopeResult<T>): T =
        (result as CapabilityEnvelopeResult.Success).value

    private fun <T> connectionSuccess(result: ConnectionResult<T>): T =
        (result as ConnectionResult.Success).value

    private fun sourceRoot(relative: String): File = listOf(
        File("src/main/java/io/redi/robot/$relative"),
        File("app/src/main/java/io/redi/robot/$relative"),
        File("../app/src/main/java/io/redi/robot/$relative"),
    ).first { it.isDirectory }
}
