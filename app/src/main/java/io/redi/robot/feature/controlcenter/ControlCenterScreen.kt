package io.redi.robot.feature.controlcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag

@Composable
fun ControlCenterScreen(
    snapshot: ControlCenterSnapshot,
    modifier: Modifier = Modifier,
    initialSection: ControlCenterSection = ControlCenterSection.PROVIDERS,
    onRetry: (() -> Unit)? = null,
) {
    var selected by remember(snapshot, initialSection) { mutableStateOf(initialSection) }
    Surface(modifier = modifier.fillMaxSize(), color = Color(0xFF071015)) {
        Column(modifier = Modifier.fillMaxSize()) {
            ControlCenterHeader(snapshot)
            ScrollableTabRow(
                selectedTabIndex = selected.ordinal,
                containerColor = Color(0xFF0A171D),
                edgePadding = 8.dp,
            ) {
                ControlCenterSection.entries.forEach { section ->
                    Tab(
                        selected = selected == section,
                        onClick = { selected = section },
                        modifier = Modifier.testTag("control-center-tab-${section.name}"),
                        text = { Text("${section.label()} ${snapshot.count(section)}") },
                    )
                }
            }
            when (snapshot.loadState) {
                ControlCenterLoadState.EMPTY -> EmptyState(selected)
                ControlCenterLoadState.ERROR -> ErrorState(snapshot.sourceError, onRetry)
                ControlCenterLoadState.READY -> SectionContent(selected, snapshot)
            }
        }
    }
}

@Composable
private fun ControlCenterHeader(snapshot: ControlCenterSnapshot) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            "HUGO Control Center",
            color = Color(0xFFE8FAFF),
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() }.testTag("control-center-title"),
        )
        Text(
            "Read-only evidence · no authority implied",
            color = Color(0xFF7FC9D8),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (snapshot.credentialRecordCount > 0) {
            Text(
                "Protected credential records: ${snapshot.credentialRecordCount}",
                color = Color(0xFF8FAAB2),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun EmptyState(section: ControlCenterSection) {
    MessageState(
        title = "No ${section.label().lowercase()} yet",
        body = when (section) {
            ControlCenterSection.PROVIDERS -> "HUGO can run without a connected or configured provider."
            ControlCenterSection.CAPABILITIES -> "Capabilities appear only after validated negotiation evidence exists."
            ControlCenterSection.GRANTS -> "No permission grant is currently represented."
            ControlCenterSection.APPROVALS -> "No approval record is currently represented."
            ControlCenterSection.DIAGNOSTICS -> "No provider diagnostic evidence is available."
        },
        tag = "control-center-empty",
    )
}

@Composable
private fun ErrorState(error: ControlCenterSourceError?, onRetry: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("control-center-error"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Control Center unavailable", color = Color(0xFFFFB4AB), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "${error?.label() ?: "Source error"}. Nothing was changed and no authority was granted.",
            color = Color(0xFFE4C7C4),
        )
        if (onRetry != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onRetry, modifier = Modifier.testTag("control-center-retry")) {
                Text("Retry read-only refresh")
            }
        }
    }
}

@Composable
private fun MessageState(title: String, body: String, tag: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = Color(0xFFE8FAFF), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Color(0xFF9DB5BC), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionContent(section: ControlCenterSection, snapshot: ControlCenterSnapshot) {
    val empty = snapshot.count(section) == 0
    if (empty) {
        EmptyState(section)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("control-center-list"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(6.dp)) }
        when (section) {
            ControlCenterSection.PROVIDERS -> items(snapshot.providers, key = { it.reference.value }) {
                EvidenceCard(
                    title = it.label,
                    status = it.status.name,
                    lines = listOfNotNull(it.definition, it.family, "Auth: ${it.authentication}",
                        "Credential: ${it.credential}", it.blockedReason),
                    emphasized = it.status in setOf(ControlCenterProviderStatus.BLOCKED, ControlCenterProviderStatus.REVOKED),
                    tag = "control-center-provider-${it.reference.value}",
                )
            }
            ControlCenterSection.CAPABILITIES -> items(snapshot.capabilities, key = { it.reference.value }) {
                EvidenceCard(
                    it.capability,
                    it.status.name,
                    listOf("Provider: ${it.providerLabel}"),
                    it.status == ControlCenterCapabilityStatus.BLOCKED,
                    "control-center-capability-${it.reference.value}",
                )
            }
            ControlCenterSection.GRANTS -> items(snapshot.grants, key = { it.reference.value }) {
                EvidenceCard(
                    it.capability,
                    it.status.name,
                    listOf("Intent: ${it.permissionIntent}", "Lifetime: ${it.lifetime}"),
                    it.status in setOf(ControlCenterAuthorityStatus.EXPIRED, ControlCenterAuthorityStatus.REVOKED),
                    "control-center-grant-${it.reference.value}",
                )
            }
            ControlCenterSection.APPROVALS -> items(snapshot.approvals, key = { it.reference.value }) {
                EvidenceCard(
                    it.capability,
                    it.status.name,
                    listOf("Requirement: ${it.requirement}"),
                    it.status != ControlCenterAuthorityStatus.ACTIVE,
                    "control-center-approval-${it.reference.value}",
                )
            }
            ControlCenterSection.DIAGNOSTICS -> items(snapshot.diagnostics, key = { it.reference.value }) {
                EvidenceCard(
                    it.title,
                    it.severity.name,
                    listOf(it.detail),
                    it.severity == ControlCenterDiagnosticSeverity.BLOCKED,
                    "control-center-diagnostic-${it.reference.value}",
                )
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun EvidenceCard(
    title: String,
    status: String,
    lines: List<String>,
    emphasized: Boolean,
    tag: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) Color(0xFF352022) else Color(0xFF10242C),
            contentColor = Color(0xFFE8FAFF),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    status,
                    color = if (emphasized) Color(0xFFFFB4AB) else Color(0xFF70D7C7),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            lines.forEach { line ->
                Text(line, color = Color(0xFFA9C1C8), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun ControlCenterSection.label(): String = when (this) {
    ControlCenterSection.PROVIDERS -> "Providers"
    ControlCenterSection.CAPABILITIES -> "Capabilities"
    ControlCenterSection.GRANTS -> "Grants"
    ControlCenterSection.APPROVALS -> "Approvals"
    ControlCenterSection.DIAGNOSTICS -> "Diagnostics"
}

private fun ControlCenterSourceError.label(): String = when (this) {
    ControlCenterSourceError.UNAVAILABLE -> "Evidence source unavailable"
    ControlCenterSourceError.STALE -> "Evidence is stale"
    ControlCenterSourceError.CORRUPT -> "Evidence failed validation"
    ControlCenterSourceError.UNSUPPORTED -> "Evidence schema is unsupported"
}
