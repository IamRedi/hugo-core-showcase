package io.redi.robot.platform.gateway.auth

import io.redi.robot.gateway.auth.GatewayTokenDocumentReadResult
import io.redi.robot.gateway.auth.GatewayTokenDocumentResetResult
import io.redi.robot.gateway.auth.GatewayTokenDocumentWriteResult
import io.redi.robot.gateway.auth.ProtectedGatewayTokenStorage
import io.redi.robot.platform.service.auth.AndroidProtectedServiceCredentialStorage
import io.redi.robot.service.auth.CredentialDocumentReadResult
import io.redi.robot.service.auth.CredentialDocumentResetResult
import io.redi.robot.service.auth.CredentialDocumentWriteResult
import java.io.File

/**
 * H052 adapter over the already verified H041 Keystore/AES-GCM atomic blob implementation.
 * A dedicated subdirectory and key alias keep gateway tokens cryptographically isolated.
 */
class AndroidProtectedGatewayTokenStorage(
    rootDirectory: File,
    keyAlias: String = "hugo.gateway.auth.v1",
) : ProtectedGatewayTokenStorage {
    private val delegate = AndroidProtectedServiceCredentialStorage(
        File(rootDirectory.absoluteFile, STORAGE_DIRECTORY),
        keyAlias,
    )

    override fun read(): GatewayTokenDocumentReadResult = when (val result = delegate.read()) {
        is CredentialDocumentReadResult.Content -> GatewayTokenDocumentReadResult.Content(result.plaintext)
        CredentialDocumentReadResult.Missing -> GatewayTokenDocumentReadResult.Missing
        CredentialDocumentReadResult.Corrupt -> GatewayTokenDocumentReadResult.Corrupt
        is CredentialDocumentReadResult.UnsupportedVersion ->
            GatewayTokenDocumentReadResult.UnsupportedVersion(result.found)
        CredentialDocumentReadResult.Unavailable -> GatewayTokenDocumentReadResult.Unavailable
    }

    override fun writeAtomically(plaintext: ByteArray): GatewayTokenDocumentWriteResult = when (
        delegate.writeAtomically(plaintext)
    ) {
        CredentialDocumentWriteResult.COMMITTED -> GatewayTokenDocumentWriteResult.COMMITTED
        CredentialDocumentWriteResult.FAILED_PRESERVING_PREVIOUS ->
            GatewayTokenDocumentWriteResult.FAILED_PRESERVING_PREVIOUS
    }

    override fun reset(): GatewayTokenDocumentResetResult = when (delegate.reset()) {
        CredentialDocumentResetResult.RESET -> GatewayTokenDocumentResetResult.RESET
        CredentialDocumentResetResult.FAILED -> GatewayTokenDocumentResetResult.FAILED
    }

    companion object {
        const val STORAGE_DIRECTORY = "gateway-auth-v1"
        const val ENCRYPTED_FILE = "service-credentials.enc"
    }
}
