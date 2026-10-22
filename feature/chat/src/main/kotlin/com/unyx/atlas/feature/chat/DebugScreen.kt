package com.unyx.atlas.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * "Debug Center" (architecture spec section 37) — raw, technical view of what the memory/RAG
 * pipeline is actually doing: semantic search scores, raw events, indexed embeddings, the exact
 * prompt text a reply was grounded on. Deliberately the opposite editorial stance of [MemoryScreen]
 * and [ContextScreen] (clean sentences, no table names) — this screen exists so that stance can be
 * trusted, by letting the raw data behind it be inspected on demand.
 *
 * Only reachable from a debuggable build (see ChatScreen's menu — the "🛠️ Debug" item itself
 * doesn't render in a release APK), so nothing here needs its own separate gate.
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    chatViewModel: ChatViewModel = hiltViewModel(),
    viewModel: DebugViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.reload() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                }
                Text(text = "🛠️ Debug", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::reload) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Atualizar")
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item { SectionTitle("Testar ATHENA (athena-query)") }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = viewModel.athenaIntent,
                            onValueChange = { viewModel.athenaIntent = it },
                            placeholder = { Text("intent, ex: CURRENT_WEATHER") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = viewModel::testAthenaQuery, enabled = !viewModel.athenaBusy) {
                            if (viewModel.athenaBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Enviar")
                            }
                        }
                    }
                }
                viewModel.athenaError?.let { error ->
                    item { Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                viewModel.athenaResult?.let { result ->
                    item {
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }

                item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
                item { SectionTitle("Testar busca local por relevância") }
                item {
                    Text(
                        "A busca compara termos no aparelho. Embeddings remotos estão desativados para manter o conteúdo das memórias fora do serviço de vetores.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = viewModel.searchQuery,
                            onValueChange = { viewModel.searchQuery = it },
                            placeholder = { Text("ex: você abriu algum app recentemente?") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Button(onClick = viewModel::runSearch, enabled = !viewModel.isSearching) {
                            if (viewModel.isSearching) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(imageVector = Icons.Default.Search, contentDescription = "Buscar")
                            }
                        }
                    }
                }
                viewModel.searchError?.let { error ->
                    item { Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                items(viewModel.searchResults) { result ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = "similaridade ${"%.3f".format(result.similarity)} · ${result.tabelaOrigem}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(text = result.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!viewModel.isSearching && viewModel.searchResults.isEmpty() && viewModel.searchQuery.isNotBlank()) {
                    item { EmptyHint("Nenhum resultado — tente buscar depois de rodar alguma ação (abrir app, etc).") }
                }

                item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
                item { SectionTitle("Último contexto enviado ao Claude") }
                item {
                    Text(
                        text = chatViewModel.lastSystemPromptSent ?: "Nenhuma mensagem enviada ainda nesta sessão.",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
                item { SectionTitle("Consumo de IA neste aparelho (últimos 7 dias)") }
                item {
                    val days = chatViewModel.aiUsageDays()
                    if (days.isEmpty()) {
                        EmptyHint("Ainda sem medições: o consumo é registrado a cada chamada ao Claude.")
                    } else {
                        Column {
                            days.forEach { day ->
                                Text(
                                    text = "${day.date} · ${day.requests} chamadas · entrada ${day.inputTokens} + cache ${day.cacheReadTokens} · saída ${day.outputTokens} · ≈ US$ ${"%.3f".format(com.unyx.atlas.data.ai.AiUsageCost.estimateUsd(day))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                            com.unyx.atlas.data.ai.AiUsageCost.averageUsdPerRequest(days)?.let { avg ->
                                Text(
                                    text = "Média ≈ US$ ${"%.4f".format(avg)} por chamada (estimativa pela tabela de preços do Sonnet 5).",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
                item { SectionTitle("Sugestões proativas atuais (${chatViewModel.proactiveNudges.size})") }
                items(chatViewModel.proactiveNudges) { nudge ->
                    Text(text = "• ${nudge.text}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
                }
                if (chatViewModel.proactiveNudges.isEmpty()) {
                    item { EmptyHint("Nenhuma no momento.") }
                }

                item { HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp)) }
                item { SectionTitle("Eventos recentes (${viewModel.recentEvents.size})") }
                items(viewModel.recentEvents) { event ->
                    Text(
                        text = "${event.evento} · ${event.createdAt?.take(19) ?: "?"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
                if (viewModel.recentEvents.isEmpty()) {
                    item { EmptyHint("Nenhum evento registrado ainda.") }
                }

                item { Spacer(modifier = Modifier.size(32.dp)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
