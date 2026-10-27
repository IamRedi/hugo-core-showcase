package io.redi.robot.feature.controlcenter

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlCenterScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyShellNavigatesAllReadOnlySectionsAndShowsRevokedState() {
        composeRule.setContent { MaterialTheme { ControlCenterScreen(readySnapshot()) } }

        composeRule.onNodeWithText("HUGO Control Center").assertExists()
        composeRule.onNodeWithText("Read-only evidence · no authority implied").assertExists()
        composeRule.onNodeWithText("Calendar service").assertExists()
        composeRule.onNodeWithText("REVOKED").assertExists()

        composeRule.onNodeWithTag("control-center-tab-CAPABILITIES").performClick()
        composeRule.onNodeWithText("calendar.events.read@1.0").assertExists()
        composeRule.onNodeWithTag("control-center-tab-GRANTS").performClick()
        composeRule.onNodeWithText("EXPIRED").assertExists()
        composeRule.onNodeWithTag("control-center-tab-APPROVALS").performScrollTo().performClick()
        composeRule.onNodeWithText("CONSUMED").assertExists()
        composeRule.onNodeWithTag("control-center-tab-DIAGNOSTICS").performClick()
        composeRule.onNodeWithText("Provider blocked").assertExists()
        composeRule.onNodeWithText("PERMISSION_REQUIRED · ADMIN_OR_POLICY_REQUIRED").assertExists()

        listOf("Connect", "Authorize", "Execute", "Edit source").forEach { forbidden ->
            composeRule.onNodeWithText(forbidden).assertDoesNotExist()
        }
    }

    @Test
    fun emptyShellExplainsEveryEmptySectionWithoutInventingProviderState() {
        composeRule.setContent { MaterialTheme { ControlCenterScreen(ControlCenterSnapshot.empty()) } }
        composeRule.onNodeWithText("No providers yet").assertExists()
        composeRule.onNodeWithText("HUGO can run without a connected or configured provider.").assertExists()

        composeRule.onNodeWithTag("control-center-tab-APPROVALS").performScrollTo().performClick()
        composeRule.onNodeWithText("No approvals yet").assertExists()
        composeRule.onNodeWithText("No approval record is currently represented.").assertExists()
    }

    @Test
    fun errorShellFailsClosedAndOffersOnlyBoundedReadRetry() {
        var retries = 0
        composeRule.setContent {
            MaterialTheme {
                ControlCenterScreen(
                    ControlCenterSnapshot.error(ControlCenterSourceError.CORRUPT),
                    onRetry = { retries += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Control Center unavailable").assertExists()
        composeRule.onNodeWithText(
            "Evidence failed validation. Nothing was changed and no authority was granted.",
        ).assertExists()
        composeRule.onNodeWithTag("control-center-retry").performClick()
        assertEquals(1, retries)
        composeRule.onNodeWithText("Connect").assertDoesNotExist()
    }

    @Test
    fun samsungScreenEvidenceIsRenderedWithoutPrivateIdentifiers() {
        val snapshot = readySnapshot()
        composeRule.setContent { MaterialTheme { ControlCenterScreen(snapshot) } }
        composeRule.waitForIdle()

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(targetContext.getExternalFilesDir(null), "h042-control-center.png")
        FileOutputStream(output, false).use { stream ->
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(
                android.graphics.Bitmap.CompressFormat.PNG,
                100,
                stream,
            )
        }
        assertFalse(output.readBytes().isEmpty())
        println("H042_SCREEN=rendered_read_only_control_center;path=${output.name};private_ids=none")
    }

    private fun readySnapshot(): ControlCenterSnapshot = success(ControlCenterSnapshot.create(
        loadState = ControlCenterLoadState.READY,
        providers = listOf(
            ControlCenterProviderItem(
                ref("cc_0000000000000001"),
                "Calendar service",
                "example.calendar/service",
                "Service provider",
                ControlCenterProviderStatus.REVOKED,
                ControlCenterAuthStatus.REVOKED,
                ControlCenterCredentialStatus.REVOKED,
                "PERMISSION_REQUIRED",
            ),
        ),
        capabilities = listOf(
            ControlCenterCapabilityItem(
                ref("cc_0000000000000002"),
                "Calendar service",
                "calendar.events.read@1.0",
                ControlCenterCapabilityStatus.NEGOTIATED,
            ),
        ),
        grants = listOf(
            ControlCenterGrantItem(
                ref("cc_0000000000000003"),
                "calendar.events.read@1.0",
                "OBSERVE",
                "SESSION",
                ControlCenterAuthorityStatus.EXPIRED,
            ),
        ),
        approvals = listOf(
            ControlCenterApprovalItem(
                ref("cc_0000000000000004"),
                "calendar.events.read@1.0",
                "ONE_TIME_CONFIRMATION",
                ControlCenterAuthorityStatus.CONSUMED,
            ),
        ),
        diagnostics = listOf(
            ControlCenterDiagnosticItem(
                ref("cc_0000000000000005"),
                "Provider blocked",
                "PERMISSION_REQUIRED · ADMIN_OR_POLICY_REQUIRED",
                ControlCenterDiagnosticSeverity.BLOCKED,
            ),
        ),
        credentialRecordCount = 1,
    ))

    private fun ref(value: String): ControlCenterItemReference = success(ControlCenterItemReference.parse(value))

    private fun <T> success(result: ControlCenterModelResult<T>): T =
        (result as ControlCenterModelResult.Success).value
}
