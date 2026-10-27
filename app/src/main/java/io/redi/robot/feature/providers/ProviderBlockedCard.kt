package io.redi.robot.feature.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Reusable blocked-state presentation. It intentionally renders no Button or clickable modifier. */
@Composable
fun ProviderBlockedCard(
    state: ProviderBlockedUiState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
) {
    val content = state.textContent()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = content.heading,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            content.providerLabel?.let {
                Text(text = it, style = MaterialTheme.typography.titleMedium)
            }
            content.sections.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = label, style = MaterialTheme.typography.labelLarge)
                    Text(text = value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (content.details.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        content.details.forEach { detail ->
                            Text(text = "• $detail", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(text = content.resolutionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}
