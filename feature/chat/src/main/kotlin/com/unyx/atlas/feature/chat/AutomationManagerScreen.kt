package com.unyx.atlas.feature.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.unyx.atlas.feature.chat.automation.Automation
import com.unyx.atlas.feature.chat.automation.AutomationAction
import com.unyx.atlas.feature.chat.automation.AutomationGeofenceManager
import com.unyx.atlas.feature.chat.automation.AutomationStore
import com.unyx.atlas.feature.chat.automation.AutomationTrigger
import com.unyx.atlas.feature.chat.automation.automationActionRaw
import com.unyx.atlas.feature.chat.automation.automationSummary
import com.unyx.atlas.feature.chat.automation.automationTriggerRaw
import com.unyx.atlas.feature.chat.automation.parseAutomationAction
import com.unyx.atlas.feature.chat.automation.parseAutomationTrigger
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

@Composable
fun AutomationManagerScreen(
    automations: List<Automation>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onSave: (Automation) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: (Automation) -> Unit,
) {
    var editing by remember { mutableStateOf<Automation?>(null) }
    var deleting by remember { mutableStateOf<Automation?>(null) }
    var creatingLocation by remember { mutableStateOf(false) }

    // Workers and system receivers can run routines while this screen is open. Refresh so their
    // latest result appears without making the user leave and reopen the screen.
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            delay(10_000)
        }
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                }
                Text("Automações", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            item {
                Text(
                    "As rotinas são executadas neste aparelho. Horários podem atrasar alguns minutos; locais podem levar alguns minutos para detectar a chegada ou saída.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { creatingLocation = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Text("  Criar rotina por localização")
                }
            }
            if (automations.isEmpty()) {
                item {
                    Text(
                        "Nenhuma rotina ainda. Crie uma por localização ou peça ao Atlas para automatizar uma ação recorrente.",
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(automations, key = { it.id }) { automation ->
                AutomationCard(
                    automation = automation,
                    onEnabledChange = { onSetEnabled(automation.id, it) },
                    onEdit = { editing = automation },
                    onDelete = { deleting = automation },
                )
            }
        }
    }

    editing?.let { automation ->
        AutomationEditorDialog(
            automation = automation,
            onDismiss = { editing = null },
            onSave = { updated -> onSave(updated); editing = null },
        )
    }
    deleting?.let { automation ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir automação?") },
            text = { Text("${automation.label}\n${automationSummary(automation)}") },
            confirmButton = {
                TextButton(onClick = { onDelete(automation.id); deleting = null }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
    if (creatingLocation) {
        LocationAutomationDialog(
            onDismiss = { creatingLocation = false },
            onCreate = { onCreate(it); creatingLocation = false },
        )
    }
}

@Composable
private fun AutomationCard(
    automation: Automation,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(automation.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        automationSummary(automation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = automation.enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(Modifier.height(12.dp))
            val executedAt = automation.lastExecutionAt
            val status = when {
                executedAt == null -> "Ainda não executada"
                automation.lastExecutionSuccess == true -> "Última execução: ${formatExecutionTime(executedAt)} · concluída"
                else -> "Última execução: ${formatExecutionTime(executedAt)} · falhou"
            }
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = if (executedAt != null && automation.lastExecutionSuccess == false) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            automation.lastExecutionMessage?.takeIf { executedAt != null }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text("  Editar")
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Text("  Excluir")
                }
            }
        }
    }
}

private data class AutomationEditFields(val label: String, val trigger: String, val action: String)

@Composable
private fun AutomationEditorDialog(
    automation: Automation,
    onDismiss: () -> Unit,
    onSave: (Automation) -> Unit,
) {
    var fields by remember(automation.id) {
        mutableStateOf(
            AutomationEditFields(
                label = automation.label,
                trigger = automationTriggerRaw(automation.trigger),
                action = automationActionRaw(automation.action),
            ),
        )
    }
    var error by remember(automation.id) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar automação") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fields.label,
                    onValueChange = { fields = fields.copy(label = it) },
                    label = { Text("Nome") },
                    singleLine = true,
                )
                if (automation.trigger is AutomationTrigger.Geofence) {
                    Text("Local salvo: ${com.unyx.atlas.feature.chat.automation.automationTriggerText(automation.trigger)}", style = MaterialTheme.typography.bodySmall)
                } else OutlinedTextField(
                        value = fields.trigger,
                        onValueChange = { fields = fields.copy(trigger = it) },
                        label = { Text("Gatilho") },
                        supportingText = { Text("carregador_conectado, bateria_baixa, fone_desconectado ou horario:07:30:seg,ter") },
                        singleLine = true,
                    )
                OutlinedTextField(
                    value = fields.action,
                    onValueChange = { fields = fields.copy(action = it) },
                    label = { Text("Ação") },
                    supportingText = { Text("abrir_app:WhatsApp, volume:50:media, brilho:60 ou notificar:Texto") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val trigger = if (automation.trigger is AutomationTrigger.Geofence) automation.trigger
                    else parseAutomationTrigger(fields.trigger.trim())
                val action = parseAutomationAction(fields.action.trim())
                if (fields.label.isBlank() || trigger == null || action == null) {
                    error = "Confira o nome, gatilho e ação. Use os formatos de exemplo."
                } else {
                    onSave(automation.copy(label = fields.label.trim(), trigger = trigger, action = action))
                }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun LocationAutomationDialog(
    onDismiss: () -> Unit,
    onCreate: (Automation) -> Unit,
) {
    val context = LocalContext.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    var placeName by remember { mutableStateOf("Casa") }
    var radius by remember { mutableStateOf("150") }
    var entering by remember { mutableStateOf(true) }
    var actionRaw by remember { mutableStateOf("notificar:Você chegou ao local") }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var locationBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fineGranted = remember(permissionRevision) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val backgroundGranted = remember(permissionRevision) {
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
    }
    val settingsPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionRevision++
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rotina por localização") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Salve o ponto enquanto estiver em casa ou no trabalho. As coordenadas ficam protegidas no aparelho.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(placeName, { placeName = it }, label = { Text("Local") }, singleLine = true)
                OutlinedTextField(radius, { radius = it.filter(Char::isDigit).take(4) }, label = { Text("Raio em metros (100–5000)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { entering = true }) { Text(if (entering) "✓ Ao chegar" else "Ao chegar") }
                    OutlinedButton(onClick = { entering = false }) { Text(if (!entering) "✓ Ao sair" else "Ao sair") }
                }
                OutlinedTextField(
                    value = actionRaw,
                    onValueChange = { actionRaw = it },
                    label = { Text("Ação") },
                    supportingText = { Text("notificar:Texto, abrir_app:Maps, volume:30:media ou brilho:50") },
                    singleLine = true,
                )
                if (!fineGranted) {
                    OutlinedButton(onClick = {
                        foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    }) { Text("Permitir localização precisa") }
                } else if (!backgroundGranted) {
                    Text("Para a rotina funcionar com o Atlas fechado, habilite localização ‘Permitir o tempo todo’.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        settingsPermission.launch(settings)
                    }) { Text("Abrir permissões do Android") }
                } else {
                    OutlinedButton(
                        enabled = !locationBusy,
                        onClick = {
                            locationBusy = true
                            error = null
                            LocationServices.getFusedLocationProviderClient(context)
                                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                                .addOnSuccessListener { fix ->
                                    locationBusy = false
                                    if (fix == null) error = "Não consegui obter sua localização. Tente novamente em um local aberto."
                                    else {
                                        latitude = fix.latitude
                                        longitude = fix.longitude
                                    }
                                }
                                .addOnFailureListener { failure ->
                                    locationBusy = false
                                    error = failure.message ?: "Falha ao obter localização."
                                }
                        },
                    ) { Text(if (locationBusy) "Obtendo localização…" else if (latitude == null) "Salvar ponto atual" else "Atualizar ponto atual") }
                    Text(if (latitude == null) "Ponto ainda não salvo" else "Ponto capturado neste aparelho", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val radiusMeters = radius.toFloatOrNull()
                val action = parseAutomationAction(actionRaw.trim())
                if (placeName.isBlank() || radiusMeters == null || radiusMeters !in 100f..5000f ||
                    latitude == null || longitude == null || !fineGranted || !backgroundGranted || action == null
                ) {
                    error = "Informe o local, um raio entre 100 e 5000 m, salve o ponto e confira a ação e permissões."
                } else {
                    val trigger = AutomationTrigger.Geofence(placeName.trim(), latitude!!, longitude!!, radiusMeters, entering)
                    onCreate(
                        Automation(
                            id = AutomationStore.newId(),
                            label = "${placeName.trim()} · ${if (entering) "chegada" else "saída"}",
                            trigger = trigger,
                            action = action,
                        ),
                    )
                }
            }, enabled = fineGranted && backgroundGranted && latitude != null) { Text("Criar rotina") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun formatExecutionTime(timestamp: Long): String = runCatching {
    DateTimeFormatter.ofPattern("dd/MM HH:mm").format(
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()),
    )
}.getOrDefault("horário desconhecido")
