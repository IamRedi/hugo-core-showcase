package io.redi.robot.platform.service.googlecalendar

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarConnectionEvidence
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadOnlyProfile
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadEvidence
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadOnlySandbox
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadRequest
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarSandboxResult
import io.redi.robot.platform.service.auth.AndroidProtectedServiceCredentialStorage
import io.redi.robot.service.auth.CredentialEpochMillis
import io.redi.robot.service.auth.CredentialModelResult
import io.redi.robot.service.auth.ServiceCredentialBoundary
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Explicit debug-only, user-driven H043 connect/read/revoke acceptance surface. */
class H043GoogleCalendarFeasibilityActivity : ComponentActivity() {
    private val authorizationClient by lazy { Identity.getAuthorizationClient(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private val transport = HttpGoogleCalendarReadOnlyTransport()
    private val sandbox by lazy {
        GoogleCalendarReadOnlySandbox(
            ServiceCredentialBoundary(
                AndroidProtectedServiceCredentialStorage(
                    File(noBackupFilesDir, "h043-google-calendar-sandbox"),
                    "hugo.h043.google.calendar.sandbox.v1",
                ),
            ),
            transport,
        )
    }
    private var screenState by mutableStateOf(H043ScreenState())
    private var connection: GoogleCalendarConnectionEvidence? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { activityResult ->
                val data = activityResult.data
                if (data == null) return@rememberLauncherForActivityResult fail("authorization_cancelled")
                runCatching {
                    authorizationClient.getAuthorizationResultFromIntent(data)
                }.onSuccess(::acceptAuthorization).onFailure(::failAuthorization)
            }
            H043Screen(
                state = screenState,
                onConnect = {
                    screenState = screenState.copy(status = "Connecting", busy = true)
                    val request = AuthorizationRequest.builder()
                        .setRequestedScopes(listOf(Scope(GoogleCalendarReadOnlyProfile.OAUTH_SCOPE)))
                        .setOptOutIncludingGrantedScopes(true)
                        .build()
                    authorizationClient.authorize(request)
                        .addOnSuccessListener { result ->
                            if (result.hasResolution()) {
                                val pending = result.pendingIntent
                                    ?: return@addOnSuccessListener fail("authorization_resolution_missing")
                                launcher.launch(
                                    IntentSenderRequest.Builder(pending.intentSender).build(),
                                )
                            } else {
                                acceptAuthorization(result)
                            }
                        }
                        .addOnFailureListener(::failAuthorization)
                },
                onRead = ::readCalendar,
                onRevoke = ::revoke,
            )
        }
    }

    override fun onDestroy() {
        transport.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun acceptAuthorization(result: AuthorizationResult) {
        val token = result.accessToken
        val grantedScopes = result.grantedScopes.toSet()
        if (token.isNullOrBlank()) return fail("token_missing")
        val bytes = token.toByteArray(StandardCharsets.UTF_8)
        val now = System.currentTimeMillis()
        try {
            when (
                val connected = sandbox.connect(
                    bytes,
                    grantedScopes,
                    time(now),
                    time(now + CONSERVATIVE_TOKEN_LIFETIME_MS),
                )
            ) {
                is GoogleCalendarSandboxResult.Success -> {
                    connection = connected.value
                    screenState = H043ScreenState(
                        status = "Connected — read-only scope confirmed",
                        connected = true,
                    )
                    Log.i(TAG, "H043_ACCOUNT=connected;scopeCount=1;credential=redacted")
                }
                is GoogleCalendarSandboxResult.Failure -> fail(connected.reason.name.lowercase())
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun readCalendar() {
        val active = connection ?: return fail("credential_missing")
        screenState = screenState.copy(status = "Reading bounded event window", busy = true)
        executor.execute {
            val start = System.currentTimeMillis()
            val request = GoogleCalendarReadRequest.create(
                start,
                start + READ_WINDOW_MS,
                GoogleCalendarReadOnlyProfile.MAXIMUM_RESULTS,
            )
            val result: GoogleCalendarSandboxResult<GoogleCalendarReadEvidence> = when (request) {
                is GoogleCalendarSandboxResult.Success ->
                    sandbox.read(active, request.value, time(System.currentTimeMillis()))
                is GoogleCalendarSandboxResult.Failure -> request
            }
            runOnUiThread {
                when (result) {
                    is GoogleCalendarSandboxResult.Success -> {
                        screenState = screenState.copy(
                            status = "Read passed — ${result.value.eventCount} events observed; payload discarded",
                            readPassed = true,
                            busy = false,
                        )
                        Log.i(
                            TAG,
                            "H043_ACCOUNT=read;eventCount=${result.value.eventCount};payload=discarded",
                        )
                    }
                    is GoogleCalendarSandboxResult.Failure -> fail(result.reason.name.lowercase())
                }
            }
        }
    }

    private fun revoke() {
        val active = connection ?: return fail("credential_missing")
        screenState = screenState.copy(status = "Revoking access", busy = true)
        executor.execute {
            val result = sandbox.revoke(active, time(System.currentTimeMillis()))
            runOnUiThread {
                when (result) {
                    is GoogleCalendarSandboxResult.Success -> {
                        connection = null
                        screenState = H043ScreenState(
                            status = "Revoked — remote and local access cleared",
                            revoked = true,
                        )
                        Log.i(TAG, "H043_ACCOUNT=revoked;credential=redacted")
                    }
                    is GoogleCalendarSandboxResult.Failure -> fail(
                        result.reason.name.lowercase(),
                        result.remoteRevoked,
                    )
                }
            }
        }
    }

    private fun fail(reason: String, remoteRevoked: Boolean = false) {
        screenState = screenState.copy(
            status = "Failed closed — $reason${if (remoteRevoked) " (remote revoked)" else ""}",
            busy = false,
        )
        Log.w(TAG, "H043_ACCOUNT=failed;reason=$reason;secrets=redacted")
    }

    private fun failAuthorization(error: Throwable) {
        val reason = if (error is ApiException) {
            "authorization_status_${error.statusCode}"
        } else {
            "authorization_unavailable"
        }
        fail(reason)
    }

    private fun time(value: Long): CredentialEpochMillis = when (
        val result = CredentialEpochMillis.create(value)
    ) {
        is CredentialModelResult.Success -> result.value
        is CredentialModelResult.Failure -> error("system time unavailable")
    }

    private companion object {
        const val TAG: String = "H043Feasibility"
        const val CONSERVATIVE_TOKEN_LIFETIME_MS: Long = 50L * 60L * 1_000L
        const val READ_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1_000L
    }
}

private data class H043ScreenState(
    val status: String = "Ready for explicit read-only authorization",
    val connected: Boolean = false,
    val readPassed: Boolean = false,
    val revoked: Boolean = false,
    val busy: Boolean = false,
)

@Composable
private fun H043Screen(
    state: H043ScreenState,
    onConnect: () -> Unit,
    onRead: () -> Unit,
    onRevoke: () -> Unit,
) {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("H043 Google Calendar feasibility", style = MaterialTheme.typography.headlineSmall)
            Text("Read-only sandbox. No account identity or event payload is logged or retained.")
            Text(state.status, modifier = Modifier.testTag("h043_status"))
            Button(
                onClick = onConnect,
                enabled = !state.busy && !state.connected,
                modifier = Modifier.fillMaxWidth().testTag("h043_connect"),
            ) { Text("Connect read-only") }
            Button(
                onClick = onRead,
                enabled = !state.busy && state.connected,
                modifier = Modifier.fillMaxWidth().testTag("h043_read"),
            ) { Text("Read bounded events") }
            Button(
                onClick = onRevoke,
                enabled = !state.busy && state.connected,
                modifier = Modifier.fillMaxWidth().testTag("h043_revoke"),
            ) { Text("Revoke access") }
            if (state.readPassed) Text("READ_PASS", modifier = Modifier.testTag("h043_read_pass"))
            if (state.revoked) Text("REVOKE_PASS", modifier = Modifier.testTag("h043_revoke_pass"))
        }
    }
}
