package com.unyx.atlas.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unyx.atlas.data.ai.AiUsageCost
import com.unyx.atlas.ui.expressive.LocalExpressivePalette

/**
 * Transparency about the AI usage this device made over the last 7 days, from the token counts the
 * provider itself reported (see AiUsageMeter). Only numbers are kept — never prompts or replies.
 * The cost is an estimate at public rates, useful for sizing the daily limits of the plans.
 */
@Composable
fun AiUsageContent(viewModel: SettingsViewModel) {
    val palette = LocalExpressivePalette.current
    val days = remember { viewModel.aiUsageDays() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Mostra quantas chamadas ao Claude este aparelho fez nos últimos 7 dias e quantos tokens elas usaram. " +
                "Só números são guardados, nunca o conteúdo das conversas.",
            color = palette.dim,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (days.isEmpty()) {
            Text("Ainda sem medições: o consumo é registrado a cada resposta da IA.", color = palette.dim)
            return@Column
        }
        days.forEach { day ->
            Text(
                "${day.date} · ${day.requests} chamadas · entrada ${day.inputTokens} · cache lido ${day.cacheReadTokens} · " +
                    "cache escrito ${day.cacheWriteTokens} · saída ${day.outputTokens} · ≈ US$ ${"%.3f".format(AiUsageCost.estimateUsd(day))}",
                color = palette.text,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AiUsageCost.averageUsdPerRequest(days)?.let { average ->
            Text(
                "Média ≈ US$ ${"%.4f".format(average)} por chamada (estimativa pela tabela de preços do Sonnet 5).",
                color = palette.text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
