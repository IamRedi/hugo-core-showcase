package io.redi.robot.showcase

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.redi.robot.HugoFirstLifeApplication
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyStage
import io.redi.robot.feature.hihugo.presentation.HiHugoBootstrapProjection
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationEvent
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationReducer
import io.redi.robot.feature.hihugo.ui.HiHugoFace
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.showcase.domain.DemoScenario
import io.redi.robot.showcase.domain.ShowcaseMemoryLab
import io.redi.robot.showcase.domain.ShowcaseSession
import kotlinx.coroutines.launch
import java.util.UUID

private val Ink = Color(0xFF09111E)
private val Panel = Color(0xFF111F30)
private val Cyan = Color(0xFF8BE7EF)
private val Soft = Color(0xFFA8BACE)
private val Amber = Color(0xFFFFCB87)

/** Separate evaluation launcher. The original live activities and services are not registered. */
class HugoShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val bootstrap = (application as HugoFirstLifeApplication).hugoIdentityBootstrapSnapshot
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Ink, surface = Panel, onSurface = Color.White)) {
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    val owner = bootstrap.identity?.id
                    if (owner == null) Text("Local identity unavailable. Evaluation fails closed.", Modifier.padding(24.dp))
                    else {
                        val session = remember { ShowcaseSession(SystemClock::elapsedRealtime) }
                        val memory = remember { ShowcaseMemoryLab(owner, { MemoryRecordId.fromUuid(UUID.randomUUID()) }, { 1_000L }) }
                        var page by remember { mutableIntStateOf(0) }
                        var revision by remember { mutableIntStateOf(0) }
                        val scope = rememberCoroutineScope()
                        val snapshot = remember(revision) { session.snapshot() }
                        val memoryResult = remember(revision) { memory.result }
                        val memoryEnvelope = remember(revision) { memory.envelope }
                        val face = HiHugoPresentationReducer.reduce(
                            HiHugoBootstrapProjection.project(bootstrap),
                            HiHugoPresentationEvent(revision.toLong() + 1, when {
                                snapshot.emergencyStopLatched -> HiHugoFaceState.BLOCKED
                                snapshot.pending != null -> HiHugoFaceState.THINKING
                                snapshot.result == "VIRTUAL_ACTION_APPLIED" -> HiHugoFaceState.BODY_READY
                                else -> HiHugoFaceState.IDLE
                            }),
                        ).model
                        fun action(block: suspend () -> Unit) { scope.launch { block(); revision++ } }
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            Row(Modifier.fillMaxWidth().padding(20.dp, 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text("HUGO", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                    Text("ENGINEERING SHOWCASE", color = Soft, fontSize = 10.sp, letterSpacing = 1.8.sp)
                                }
                                Text("OFFLINE\nSIMULATED BODY", color = Cyan, fontSize = 10.sp, lineHeight = 15.sp)
                            }
                            TabRow(selectedTabIndex = page, containerColor = Ink, contentColor = Cyan) {
                                listOf("Runtime", "Safety", "Memory", "Architecture").forEachIndexed { index, name ->
                                    Tab(selected = page == index, onClick = { page = index }, text = { Text(name, fontSize = 11.sp, maxLines = 1) })
                                }
                            }
                            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item { Spacer(Modifier.height(4.dp)) }
                                when (page) {
                                    0 -> {
                                        item { Title("One identity.\nMany possible bodies.", "Persistent identity, governed capabilities and deterministic local safety.") }
                                        item { Box(Modifier.height(218.dp)) { HiHugoFace(face, HiHugoBodyStage.AVAILABLE, animationActive = true) } }
                                        item { Block("PERSISTENT LOCAL IDENTITY") { Text(bootstrap.safeDisplayIdentity?.value.orEmpty(), color = Cyan, fontWeight = FontWeight.Bold); Text("Created inside this separate demo installation.", fontSize = 12.sp, color = Soft) } }
                                        item { Block("BODY ADAPTER · SIM-01") { Text("Virtual light · ${(snapshot.brightness * 100).toInt()}%", fontSize = 20.sp); Text("Scripted proposals. In-memory body. No device connection.", fontSize = 12.sp, color = Soft) } }
                                        item { Act("Propose virtual light") { session.propose(DemoScenario.LIGHT); revision++; page = 1 } }
                                    }
                                    1 -> {
                                        item { Title("Deterministic safety", "A scripted proposal has no execution authority. Approval creates a fresh typed command.") }
                                        item { Block("LIVE LOCAL RESULT") { Text(snapshot.result, color = if (snapshot.result == "VIRTUAL_ACTION_APPLIED") Cyan else Amber, fontSize = 15.sp, fontWeight = FontWeight.Bold); Text("Virtual light: ${(snapshot.brightness * 100).toInt()}%  ·  Executed: ${snapshot.executionCount}", fontSize = 12.sp, color = Soft) } }
                                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SmallAct("Propose light", Modifier.weight(1f)) { session.propose(DemoScenario.LIGHT); revision++ }
                                            SmallAct("Approve once", Modifier.weight(1f), snapshot.pending != null) { action { session.approve() } }
                                        } }
                                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SmallAct("Overspeed", Modifier.weight(1f)) { session.propose(DemoScenario.OVERSPEED); revision++ }
                                            SmallAct("Stale command", Modifier.weight(1f)) { session.propose(DemoScenario.STALE); revision++ }
                                        } }
                                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SmallAct("Virtual reconnect", Modifier.weight(1f)) { action { session.reconnect() } }
                                            SmallAct("Local reset", Modifier.weight(1f), snapshot.emergencyStopLatched) { session.resetStop(); revision++ }
                                        } }
                                        items(snapshot.trace.size) { i -> Block(snapshot.trace[i].stage) { Text(snapshot.trace[i].result, fontSize = 13.sp, color = Soft) } }
                                        item { Text("Runs the existing SafetyController and E-stop reducers. This demo confirmation shell is separate from the production authorization orchestrator.", fontSize = 11.sp, color = Soft) }
                                    }
                                    2 -> {
                                        item { Title("Memory is not belief.", "Real H061 factories. Synthetic envelopes only. Nothing is persisted by this lab.") }
                                        item { Block("MEMORY CONTRACT RESULT") { Text(memoryResult, fontSize = 14.sp, color = Amber, fontWeight = FontWeight.Bold) } }
                                        item { Act("Create model candidate") { memory.createCandidate(); revision++ } }
                                        item { Act("Try model self-validation") { memory.attemptModelSelfValidation(); revision++ } }
                                        item { Act("Build user correction draft", memoryEnvelope != null) { memory.userCorrectionDraft(); revision++ } }
                                        item { Block("CURRENT ENVELOPE · SYNTHETIC") {
                                            val e = memoryEnvelope
                                            if (e == null) Text("Create a candidate to inspect the contract.", color = Soft, fontSize = 13.sp)
                                            else {
                                                Metric("Validation", e.validationState.name)
                                                Metric("Provenance", e.provenance.origin.name)
                                                Metric("Privacy", e.privacy.name)
                                                Metric("Allowed use", e.allowedUse.name)
                                                Metric("Supersedes", if (e.supersedes != null) "Previous candidate" else "None")
                                                Metric("Grants authority", e.grantsAuthority.toString())
                                            }
                                        } }
                                        item { Text("Correction here constructs an envelope; it does not commit a durable change-set. AES-GCM storage, authenticated recovery and atomic corrections have separate source tests.", color = Soft, fontSize = 11.sp) }
                                    }
                                    else -> {
                                        item { Title("Boundaries by design.", "One runtime identity. Typed contracts. Separate authority, transport and memory.") }
                                        item { Block("01 · CAPABILITIES") { Text("BodyAdapter · DeviceAdapter · ServiceConnector\nDiscovery and manifests describe capability; they grant no trust.", color = Soft, fontSize = 13.sp) } }
                                        item { Block("02 · AUTHORITY") { Text("Trust → scoped permission → approval → policy\nModel output is an untrusted typed proposal.", color = Soft, fontSize = 13.sp) } }
                                        item { Block("03 · SAFETY & TRANSPORT") { Text("Monotonic leases · dead-man · latched stop\nGeneration correlation · serialized GATT operations\nFirst-party research code; physical access disabled here.", color = Soft, fontSize = 13.sp) } }
                                        item { Block("04 · MEMORY") { Text("Provenance · candidates · explicit corrections\nAES-256-GCM storage · authenticated journals\nModel belief cannot promote itself to fact.", color = Soft, fontSize = 13.sp) } }
                                        item { Block("05 · ENGINEERING EVIDENCE") { Text("Architecture import guards · adversarial fixtures\nPure JVM tests · Android build and instrumentation\nSoftware, emulator and real-device evidence stay separate.", color = Soft, fontSize = 13.sp) } }
                                        item { Text("This is an evaluation shell over existing HUGO components. Planned orchestration, Developer Mode and general autonomy are not implemented by this showcase.", color = Soft, fontSize = 11.sp) }
                                    }
                                }
                                item { Spacer(Modifier.height(8.dp)) }
                            }
                            Column(Modifier.background(Ink).padding(20.dp, 8.dp)) {
                                Button(onClick = { action { session.emergencyStop() }; page = 1 }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF512E38), contentColor = Color(0xFFFFDBDD)), shape = RoundedCornerShape(12.dp)) { Text("LOCAL STOP", fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
                                Text("No Internet · no Bluetooth · no microphone or camera", color = Soft, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Title(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = Soft) } }
@Composable private fun Block(title: String, content: @Composable () -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title.uppercase(), fontSize = 9.sp, letterSpacing = 1.sp, color = Cyan); content() } } }
@Composable private fun Metric(name: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name, fontSize = 11.sp, color = Soft); Text(value, fontSize = 11.sp, fontWeight = FontWeight.Medium) } }
@Composable private fun Act(name: String, enabled: Boolean = true, click: () -> Unit) { Button(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(name, fontSize = 12.sp) } }
@Composable private fun SmallAct(name: String, modifier: Modifier, enabled: Boolean = true, click: () -> Unit) { Button(onClick = click, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(7.dp, 10.dp)) { Text(name, fontSize = 10.sp, maxLines = 1) } }
