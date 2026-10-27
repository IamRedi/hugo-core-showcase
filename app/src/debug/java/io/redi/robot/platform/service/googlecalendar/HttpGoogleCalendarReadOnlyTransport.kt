package io.redi.robot.platform.service.googlecalendar

import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadOnlyProfile
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadOnlyTransport
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarTransportResult
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarWireRequest
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarWireResponse
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only HTTP bridge for H043. No HTTP/vendor type crosses the adapter interface. */
class HttpGoogleCalendarReadOnlyTransport(
    private val cancelled: AtomicBoolean = AtomicBoolean(false),
) : GoogleCalendarReadOnlyTransport {
    override fun execute(
        request: GoogleCalendarWireRequest,
        accessToken: ByteArray,
    ): GoogleCalendarTransportResult<GoogleCalendarWireResponse> {
        if (cancelled.get()) return GoogleCalendarTransportResult.Cancelled
        val connection = open(request.uri)
            ?: return GoogleCalendarTransportResult.Unavailable
        return try {
            connection.requestMethod = request.method.name
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "Authorization",
                "Bearer ${accessToken.toString(StandardCharsets.UTF_8)}",
            )
            val status = connection.responseCode
            if (cancelled.get()) return GoogleCalendarTransportResult.Cancelled
            val body = readBounded(
                if (status in 200..299) connection.inputStream else connection.errorStream,
            ) ?: return GoogleCalendarTransportResult.Unavailable
            when (val response = GoogleCalendarWireResponse.copyOf(status, body)) {
                is io.redi.robot.adapter.service.googlecalendar.GoogleCalendarSandboxResult.Success ->
                    GoogleCalendarTransportResult.Success(response.value)
                is io.redi.robot.adapter.service.googlecalendar.GoogleCalendarSandboxResult.Failure ->
                    GoogleCalendarTransportResult.Unavailable
            }.also { body.fill(0) }
        } catch (_: java.net.SocketTimeoutException) {
            GoogleCalendarTransportResult.TimedOut
        } catch (_: Exception) {
            GoogleCalendarTransportResult.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    override fun revoke(accessToken: ByteArray): GoogleCalendarTransportResult<Int> {
        if (cancelled.get()) return GoogleCalendarTransportResult.Cancelled
        val connection = open(GoogleCalendarReadOnlyProfile.REVOCATION_ENDPOINT)
            ?: return GoogleCalendarTransportResult.Unavailable
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val tokenText = accessToken.toString(StandardCharsets.UTF_8)
            val body = "token=${URLEncoder.encode(tokenText, StandardCharsets.UTF_8.name())}"
                .toByteArray(StandardCharsets.UTF_8)
            try {
                connection.outputStream.use { it.write(body) }
            } finally {
                body.fill(0)
            }
            if (cancelled.get()) GoogleCalendarTransportResult.Cancelled
            else GoogleCalendarTransportResult.Success(connection.responseCode)
        } catch (_: java.net.SocketTimeoutException) {
            GoogleCalendarTransportResult.TimedOut
        } catch (_: Exception) {
            GoogleCalendarTransportResult.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    fun cancel() {
        cancelled.set(true)
    }

    private fun open(uri: String): HttpURLConnection? = runCatching {
        URI(uri).toURL().openConnection() as HttpURLConnection
    }.getOrNull()

    private fun readBounded(input: java.io.InputStream?): ByteArray? {
        if (input == null) return ByteArray(0)
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            try {
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > GoogleCalendarWireResponse.MAXIMUM_RESPONSE_BYTES) {
                        return null
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally {
                buffer.fill(0)
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS: Int = 10_000
    }
}
