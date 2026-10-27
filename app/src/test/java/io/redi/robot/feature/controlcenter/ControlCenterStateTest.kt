package io.redi.robot.feature.controlcenter

import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAuthorizationReference
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.service.api.ServiceAuthSessionReference
import io.redi.robot.service.api.ServiceAuthenticationSchemeReference
import io.redi.robot.service.api.ServiceAuthenticationSnapshot
import io.redi.robot.service.api.ServiceAuthenticationState
import io.redi.robot.service.api.ServiceAuthorizationScopeReference
import io.redi.robot.service.api.ServiceConnectorResult
import io.redi.robot.service.auth.CredentialBoundaryResult
import io.redi.robot.service.auth.CredentialDocumentReadResult
import io.redi.robot.service.auth.CredentialDocumentResetResult
import io.redi.robot.service.auth.CredentialDocumentWriteResult
import io.redi.robot.service.auth.CredentialEpochMillis
import io.redi.robot.service.auth.CredentialModelResult
import io.redi.robot.service.auth.CredentialStoreGeneration
import io.redi.robot.service.auth.ProtectedServiceCredentialStorage
import io.redi.robot.service.auth.ServiceCredentialBoundary
import io.redi.robot.service.auth.ServiceCredentialId
import io.redi.robot.service.auth.ServiceCredentialSecret
import io.redi.robot.service.auth.StoreServiceCredentialRequest
import io.redi.robot.trust.domain.ApprovalId
import io.redi.robot.trust.domain.ApprovalRecord
import io.redi.robot.trust.domain.ApprovalState
import io.redi.robot.trust.domain.AuthorityActor
import io.redi.robot.trust.domain.AuthorityActorId
import io.redi.robot.trust.domain.AuthorityProvenanceReference
import io.redi.robot.trust.domain.ExecutionLocality
import io.redi.robot.trust.domain.HugoHostRole
import io.redi.robot.trust.domain.PermissionGrant
import io.redi.robot.trust.domain.PermissionGrantLifetime
import io.redi.robot.trust.domain.PermissionRestrictions
import io.redi.robot.trust.domain.PermissionScope
import io.redi.robot.trust.domain.PermissionValidityWindow
import io.redi.robot.trust.domain.RevocationId
import io.redi.robot.trust.domain.RevocationRecord
import io.redi.robot.trust.domain.RevocationTarget
import io.redi.robot.trust.domain.TrustDomainResult
import io.redi.robot.trust.domain.TrustSchemaVersion
import io.redi.robot.capability.api.CapabilityAuditId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ControlCenterStateTest {
    @Test
    fun `empty and error states are explicit usable and non authoritative`() {
        val empty = ControlCenterSnapshot.empty()
        val error = ControlCenterSnapshot.error(ControlCenterSourceError.CORRUPT)

        assertEquals(ControlCenterLoadState.EMPTY, empty.loadState)
        assertEquals(ControlCenterLoadState.ERROR, error.loadState)
        assertEquals(ControlCenterSourceError.CORRUPT, error.sourceError)
        listOf(empty, error).forEach { snapshot ->
            assertFalse(snapshot.permitsSourceEditing)
            assertFalse(snapshot.grantsTrust)
            assertFalse(snapshot.grantsAuthorization)
            assertFalse(snapshot.grantsApproval)
            assertFalse(snapshot.establishesConnection)
            assertFalse(snapshot.executesCapability)
        }
    }

    @Test
    fun `ready snapshot is canonically ordered immutable and rejects unsafe or duplicate content`() {
        val provider = providerItem(ref("cc_ffffffffffffffff"), ControlCenterProviderStatus.AVAILABLE)
        val providerFirst = providerItem(ref("cc_0000000000000000"), ControlCenterProviderStatus.REVOKED)
        val capability = ControlCenterCapabilityItem(
            ref("cc_1111111111111111"),
            "Body provider",
            "mobility.stop@1.0",
            ControlCenterCapabilityStatus.NEGOTIATED,
        )
        val snapshot = success(ControlCenterSnapshot.create(
            ControlCenterLoadState.READY,
            providers = listOf(provider, providerFirst),
            capabilities = listOf(capability),
        ))

        assertEquals(listOf(providerFirst.reference, provider.reference), snapshot.providers.map { it.reference })
        assertTrue(snapshot.providers.javaClass.name.contains("Unmodifiable"))
        assertEquals(
            ControlCenterModelError.DUPLICATE_REFERENCE,
            failure(ControlCenterSnapshot.create(
                ControlCenterLoadState.READY,
                providers = listOf(provider),
                capabilities = listOf(capability.copy(reference = provider.reference)),
            )),
        )
        assertEquals(
            ControlCenterModelError.UNSAFE_TEXT,
            failure(ControlCenterSnapshot.create(
                ControlCenterLoadState.READY,
                diagnostics = listOf(
                    ControlCenterDiagnosticItem(
                        ref("cc_2222222222222222"),
                        "Provider evidence",
                        "bearer abcdefghijklmnopqrstuvwxyz",
                        ControlCenterDiagnosticSeverity.INFO,
                    ),
                ),
            )),
        )
    }

    @Test
    fun `reducer changes presentation selection and snapshot only`() {
        val initial = ControlCenterState(ControlCenterSection.PROVIDERS, ControlCenterSnapshot.empty())
        val selected = ControlCenterReducer.reduce(
            initial,
            ControlCenterEvent.SelectSection(ControlCenterSection.APPROVALS),
        )
        val replaced = ControlCenterReducer.reduce(
            selected,
            ControlCenterEvent.ReplaceSnapshot(ControlCenterSnapshot.error(ControlCenterSourceError.UNAVAILABLE)),
        )

        assertEquals(ControlCenterSection.APPROVALS, selected.selectedSection)
        assertEquals(ControlCenterSection.APPROVALS, replaced.selectedSection)
        assertEquals(ControlCenterLoadState.ERROR, replaced.snapshot.loadState)
        assertFalse(replaced.snapshot.grantsAuthorization)
    }

    @Test
    fun `H040 and H041 evidence projects auth and credential without exposing their references`() {
        val storage = MemoryCredentialStorage()
        val credentialBoundary = ServiceCredentialBoundary(storage)
        val secretBytes = ByteArray(24) { (it + 31).toByte() }
        val secret = credentialSecret(secretBytes)
        val credentialId = ServiceCredentialId.newReference()
        val request = success(StoreServiceCredentialRequest.create(
            credentialId,
            providerDefinition(),
            authScheme(),
            setOf(serviceScope()),
            epoch(1_000),
            epoch(5_000),
            secret,
        ))
        credentialBoundary.store(request, storeGeneration(0)).credentialSuccess()
        secret.close()
        val credentialSnapshot = credentialBoundary.snapshot(epoch(2_000)).credentialSuccess()
        val auth = serviceSuccess(ServiceAuthenticationSnapshot.create(
            providerGeneration(2),
            ServiceAuthenticationState.AUTHENTICATED,
            authSession(),
            setOf(serviceScope()),
            timestamp(10),
            timestamp(100),
            null,
        ))

        val projected = success(ControlCenterProjector.project(ControlCenterCanonicalSources(
            serviceAuthentication = listOf(ControlCenterServiceSource(providerDefinition(), auth)),
            credentialSnapshot = credentialSnapshot,
            now = timestamp(20),
            credentialNow = epoch(2_000),
        )))

        val provider = projected.providers.single()
        assertEquals(ControlCenterAuthStatus.AUTHENTICATED, provider.authentication)
        assertEquals(ControlCenterCredentialStatus.ACTIVE, provider.credential)
        assertEquals(1, projected.credentialRecordCount)
        val rendered = provider.toString() + projected.providers.toString()
        assertFalse(rendered.contains(credentialId.value))
        assertFalse(rendered.contains(authSession().value))
        assertTrue(storage.content!!.containsSequence(secretBytes))
    }

    @Test
    fun `revoked grants and approvals remain visibly revoked and never regain authority`() {
        val grant = grant()
        val approval = approval(grant)
        val revocations = listOf(
            revocation(RevocationTarget.Grant(grant.authorizationReference), "dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            revocation(RevocationTarget.Approval(approval.id), "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
        )
        val projected = success(ControlCenterProjector.project(ControlCenterCanonicalSources(
            grants = listOf(grant),
            approvals = listOf(approval),
            revocations = revocations,
            now = timestamp(20),
            credentialNow = epoch(2_000),
        )))

        assertEquals(ControlCenterAuthorityStatus.REVOKED, projected.grants.single().status)
        assertEquals(ControlCenterAuthorityStatus.REVOKED, projected.approvals.single().status)
        assertFalse(projected.grantsAuthorization)
        assertFalse(projected.grantsApproval)
        assertNotEquals(grant.authorizationReference.value, projected.grants.single().reference.value)
        assertNotEquals(approval.id.value, projected.approvals.single().reference.value)
    }

    @Test
    fun `source failure discards supplied records rather than showing stale partial truth`() {
        val projected = success(ControlCenterProjector.project(ControlCenterCanonicalSources(
            grants = listOf(grant()),
            now = timestamp(20),
            credentialNow = epoch(2_000),
            error = ControlCenterSourceError.STALE,
        )))

        assertEquals(ControlCenterLoadState.ERROR, projected.loadState)
        assertEquals(0, projected.grants.size)
        assertEquals(0, projected.providers.size)
        assertEquals(ControlCenterSourceError.STALE, projected.sourceError)
    }

    @Test
    fun `conflicting service snapshots are rejected rather than selected by ordering`() {
        val auth = serviceSuccess(ServiceAuthenticationSnapshot.create(
            providerGeneration(2),
            ServiceAuthenticationState.AUTHENTICATED,
            authSession(),
            setOf(serviceScope()),
            timestamp(10),
            timestamp(100),
            null,
        ))
        val sources = ControlCenterCanonicalSources(
            serviceAuthentication = listOf(
                ControlCenterServiceSource(providerDefinition(), auth),
                ControlCenterServiceSource(providerDefinition(), auth),
            ),
            now = timestamp(20),
            credentialNow = epoch(2_000),
        )

        assertEquals(
            ControlCenterModelError.CONFLICTING_SOURCE_EVIDENCE,
            failure(ControlCenterProjector.project(sources)),
        )
        assertEquals(
            failure(ControlCenterProjector.project(sources.copy(serviceAuthentication = sources.serviceAuthentication.reversed()))),
            ControlCenterModelError.CONFLICTING_SOURCE_EVIDENCE,
        )
    }

    @Test
    fun `control center boundary has no transport vendor execution or source editor coupling`() {
        val root = sequenceOf(
            File("src/main/java/io/redi/robot/feature/controlcenter"),
            File("app/src/main/java/io/redi/robot/feature/controlcenter"),
            File("../app/src/main/java/io/redi/robot/feature/controlcenter"),
        ).first { it.isDirectory }
        val sources = root.walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() }
        val all = sources.values.joinToString("\n")
        listOf(
            "io.redi.robot.body.looi",
            "android.bluetooth",
            "BluetoothGatt",
            "BluetoothDevice",
            "FED8",
            "FED9",
            "ServiceCredentialSecret",
            "withCredential(",
        ).forEach { forbidden -> assertFalse("Unexpected coupling: $forbidden", all.contains(forbidden)) }
        listOf("connect", "authorize", "execute", "grant", "revoke", "editSource").forEach { action ->
            assertFalse(
                "Snapshot must not expose mutation method $action",
                ControlCenterSnapshot::class.java.methods.any { it.name.equals(action, ignoreCase = true) },
            )
        }
        val modelAndProjector = listOfNotNull(sources["ControlCenterModel.kt"], sources["ControlCenterProjector.kt"])
            .joinToString("\n")
        assertFalse(modelAndProjector.contains("import android."))
        assertFalse(modelAndProjector.contains("import androidx."))
    }

    private fun providerItem(reference: ControlCenterItemReference, status: ControlCenterProviderStatus) =
        ControlCenterProviderItem(
            reference,
            "Body provider",
            "hugo.reference/body",
            "Body provider",
            status,
            ControlCenterAuthStatus.NOT_APPLICABLE,
            ControlCenterCredentialStatus.NONE,
            if (status == ControlCenterProviderStatus.REVOKED) "PERMISSION_REQUIRED" else null,
        )

    private fun grant(): PermissionGrant {
        val scope = trustSuccess(PermissionScope.create(
            actor(),
            providerInstance(),
            capability(),
            CapabilityPermissionIntent.OBSERVE,
        ))
        return trustSuccess(PermissionGrant.create(
            schema(),
            authorizationReference(),
            scope,
            PermissionGrantLifetime.PERSISTENT,
            null,
            null,
            CapabilityApprovalRequirement.WITHIN_GRANT,
            validity(0, 100),
            trustSuccess(PermissionRestrictions.create(
                setOf(HugoHostRole.MOBILE_HOST),
                ExecutionLocality.LOCAL_ONLY,
            )),
            provenance(),
            auditId(),
        ))
    }

    private fun approval(grant: PermissionGrant): ApprovalRecord = trustSuccess(ApprovalRecord.create(
        schema(),
        trustSuccess(ApprovalId.parse("cccccccc-cccc-4ccc-8ccc-cccccccccccc")),
        grant.authorizationReference,
        actor(),
        providerInstance(),
        capability(),
        CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
        requestId(),
        null,
        ApprovalState.APPROVED,
        validity(0, 100),
        provenance(),
        auditId(),
    ))

    private fun revocation(target: RevocationTarget, id: String) = RevocationRecord(
        schema(),
        trustSuccess(RevocationId.parse(id)),
        target,
        timestamp(10),
        provenance(),
        auditId(),
    )

    private fun actor() = AuthorityActor(
        trustSuccess(AuthorityActorId.parse("11111111-1111-4111-8111-111111111111")),
        CapabilityActorKind.LOCAL_USER,
    )

    private fun providerDefinition(): ProviderDefinitionId = providerSuccess(
        ProviderDefinitionId.parse("example.calendar/service"),
    )

    private fun providerInstance(): ProviderInstanceId = providerSuccess(
        ProviderInstanceId.parse("33333333-3333-4333-8333-333333333333"),
    )

    private fun capability(): CapabilityReference = when (
        val parsed = CapabilityReference.parse("calendar.events.read@1.0")
    ) {
        is CapabilityIdentityResult.Success -> parsed.value
        is CapabilityIdentityResult.Failure -> error("capability fixture")
    }

    private fun authScheme() = serviceSuccess(
        ServiceAuthenticationSchemeReference.parse("auth.standard/delegated@1.0"),
    )

    private fun serviceScope() = serviceSuccess(
        ServiceAuthorizationScopeReference.parse("scope.calendar/read@1.0"),
    )

    private fun authSession() = serviceSuccess(
        ServiceAuthSessionReference.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
    )

    private fun schema() = trustSuccess(TrustSchemaVersion.parse("1.0"))
    private fun validity(from: Long, until: Long) = trustSuccess(
        PermissionValidityWindow.create(timestamp(from), timestamp(until)),
    )
    private fun provenance() = trustSuccess(AuthorityProvenanceReference.parse("local.control.center.test"))
    private fun authorizationReference() = envelopeSuccess(
        CapabilityAuthorizationReference.parse("55555555-5555-4555-8555-555555555555"),
    )
    private fun requestId() = envelopeSuccess(
        CapabilityRequestId.parse("66666666-6666-4666-8666-666666666666"),
    )
    private fun auditId() = envelopeSuccess(
        CapabilityAuditId.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
    )
    private fun timestamp(value: Long) = envelopeSuccess(MonotonicTimestampMs.create(value))
    private fun providerGeneration(value: Long) = portSuccess(ProviderGeneration.create(value))
    private fun epoch(value: Long) = success(CredentialEpochMillis.create(value))
    private fun storeGeneration(value: Long) = success(CredentialStoreGeneration.create(value))
    private fun credentialSecret(value: ByteArray) = success(ServiceCredentialSecret.copyOf(value))
    private fun ref(value: String) = success(ControlCenterItemReference.parse(value))

    private fun <T> success(result: ControlCenterModelResult<T>): T =
        (result as ControlCenterModelResult.Success).value
    private fun <T> success(result: CredentialModelResult<T>): T =
        (result as CredentialModelResult.Success).value
    private fun <T> CredentialBoundaryResult<T>.credentialSuccess(): T =
        (this as CredentialBoundaryResult.Success).value
    private fun <T> serviceSuccess(result: ServiceConnectorResult<T>): T =
        (result as ServiceConnectorResult.Success).value
    private fun <T> trustSuccess(result: TrustDomainResult<T>): T =
        (result as TrustDomainResult.Success).value
    private fun <T> envelopeSuccess(result: CapabilityEnvelopeResult<T>): T =
        (result as CapabilityEnvelopeResult.Success).value
    private fun <T> providerSuccess(result: ProviderIdentityResult<T>): T =
        (result as ProviderIdentityResult.Success).value
    private fun <T> portSuccess(result: CapabilityProviderPortResult<T>): T =
        (result as CapabilityProviderPortResult.Success).value
    private fun failure(result: ControlCenterModelResult<*>): ControlCenterModelError =
        (result as ControlCenterModelResult.Failure).error

    private class MemoryCredentialStorage : ProtectedServiceCredentialStorage {
        var content: ByteArray? = null
        override fun read(): CredentialDocumentReadResult = content?.let {
            CredentialDocumentReadResult.Content(it.copyOf())
        } ?: CredentialDocumentReadResult.Missing
        override fun writeAtomically(plaintext: ByteArray): CredentialDocumentWriteResult {
            content = plaintext.copyOf()
            return CredentialDocumentWriteResult.COMMITTED
        }
        override fun reset(): CredentialDocumentResetResult {
            content = null
            return CredentialDocumentResetResult.RESET
        }
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
    }
}
