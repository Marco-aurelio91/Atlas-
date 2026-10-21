package com.unyx.atlas.feature.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.unyx.atlas.feature.updater.UpdateSection
import com.unyx.atlas.memory.auth.AuthState
import com.unyx.atlas.ui.DefaultAssistant
import com.unyx.atlas.ui.LauncherIcons
import com.unyx.atlas.ui.expressive.Accent
import com.unyx.atlas.ui.expressive.DarkExpressivePalette
import com.unyx.atlas.ui.expressive.ExpressiveActionRow
import com.unyx.atlas.ui.expressive.ExpressiveCard
import com.unyx.atlas.ui.expressive.ExpressivePalette
import com.unyx.atlas.ui.expressive.LightExpressivePalette
import com.unyx.atlas.ui.expressive.LocalExpressivePalette
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var isDefaultAssistant by remember { mutableStateOf(DefaultAssistant.isDefault(context)) }
    var canDrawOverlays by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val overlayEnabled by viewModel.overlayEnabled.collectAsStateWithLifecycle(initialValue = false)
    val coroutineScope = rememberCoroutineScope()
    val setDefaultAssistant = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { isDefaultAssistant = DefaultAssistant.isDefault(context) }
    LifecycleResumeEffect(Unit) {
        isDefaultAssistant = DefaultAssistant.isDefault(context)
        canDrawOverlays = Settings.canDrawOverlays(context)
        onPauseOrDispose { }
    }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        canDrawOverlays = Settings.canDrawOverlays(context)
        if (canDrawOverlays) {
            coroutineScope.launch {
                try {
                    viewModel.setOverlayEnabled(true)
                    startAtlasOverlayService(context)
                } catch (failure: Exception) {
                    viewModel.setOverlayEnabled(false)
                    android.widget.Toast.makeText(context, "Não foi possível ativar o Atlas flutuante.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } else {
            android.widget.Toast.makeText(context, "A permissão não foi concedida. O Atlas flutuante continua desativado.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val palette = if (isSystemInDarkTheme()) DarkExpressivePalette else LightExpressivePalette
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val signedInUserId = (authState as? AuthState.LoggedIn)?.userId
    // Hoisted so the main list's scroll position survives recomposition; saveable so it also
    // survives activity recreation.
    val mainScrollState = rememberSaveable(saver = androidx.compose.foundation.ScrollState.Saver) {
        androidx.compose.foundation.ScrollState(0)
    }

    CompositionLocalProvider(LocalExpressivePalette provides palette) {
        Surface(modifier = modifier.fillMaxSize(), color = palette.bg) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(mainScrollState)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    color = palette.text,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp),
                )

                CollapsibleSection(title = stringResource(R.string.settings_permissions)) {
                    PermissionRow(
                        label = stringResource(R.string.settings_permission_calendar),
                        permissions = listOf(android.Manifest.permission.READ_CALENDAR),
                    )
                    PermissionRow(
                        label = stringResource(R.string.settings_permission_location),
                        permissions = listOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                    WriteSettingsPermissionRow()
                }

                CollapsibleSection(title = stringResource(R.string.settings_atlas_chat)) {
                    ExpressiveActionRow(
                        label = when {
                            overlayEnabled && !canDrawOverlays -> "Conceder novamente a permissão do overlay"
                            overlayEnabled -> "Desativar personagem flutuante"
                            else -> "Ativar personagem flutuante"
                        },
                        description = when {
                            overlayEnabled && canDrawOverlays -> "Ligado: o Atlas pode aparecer sobre outros apps em automações e sugestões."
                            !canDrawOverlays -> "Requer permissão para aparecer sobre outros apps. Nenhuma tela é capturada."
                            else -> "Mostra o personagem ao lado do Assistente rápido e em eventos do Atlas."
                        },
                    ) {
                        if (!canDrawOverlays) {
                            overlayPermissionLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } else if (overlayEnabled) {
                            coroutineScope.launch {
                                try {
                                    viewModel.setOverlayEnabled(false)
                                    stopAtlasOverlayService(context)
                                } catch (failure: Exception) {
                                    android.widget.Toast.makeText(context, "Não foi possível desativar o Atlas flutuante.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            coroutineScope.launch {
                                try {
                                    viewModel.setOverlayEnabled(true)
                                    startAtlasOverlayService(context)
                                } catch (failure: Exception) {
                                    viewModel.setOverlayEnabled(false)
                                    android.widget.Toast.makeText(context, "Não foi possível ativar o Atlas flutuante.", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    ExpressiveActionRow(
                        label = "Assistente padrão do telefone",
                        description = if (isDefaultAssistant) "O ATLAS é seu assistente padrão" else "Usar o ATLAS pelo gesto ou botão de assistente",
                    ) {
                        runCatching { setDefaultAssistant.launch(DefaultAssistant.requestIntent(context)) }
                            .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                            .onFailure {
                                android.widget.Toast.makeText(context, "Abra Configurar > Apps > Apps padrão > Assistente digital", android.widget.Toast.LENGTH_LONG).show()
                            }
                    }
                    ExpressiveActionRow(
                        label = stringResource(R.string.settings_atlas_chat),
                        description = stringResource(R.string.settings_atlas_chat_desc),
                    ) {
                        // setClassName so feature:settings doesn't need a compile dep on :app.
                        context.startActivity(
                            android.content.Intent().setClassName(context.packageName, "com.unyx.atlas.AtlasChatActivity"),
                        )
                    }
                }

                CollapsibleSection(title = "Memórias: proteção e recuperação") {
                    AtlasSecurityContent(viewModel = viewModel, userId = signedInUserId)
                }
                CollapsibleSection(title = "Conta: autenticação em duas etapas") {
                    AtlasMfaContent(viewModel = viewModel, userId = signedInUserId)
                }

                // ATHENA has no screen/plugin identity of its own (2026-09-30 architecture
                // correction) — it's the perception layer ATLAS queries, not a second assistant.
                // Its controls render right here, as an ATLAS-owned settings section, instead of a
                // dedicated Athena activity.
                CollapsibleSection(title = "Percepção externa") {
                    AthenaSectionContent(coordinator = viewModel.athenaCoordinator)
                }
                UpdateSection()
            }
        }
    }
}

private fun startAtlasOverlayService(context: android.content.Context) {
    val intent = Intent().setClassName(
        context.packageName,
        "com.unyx.atlas.feature.chat.AtlasOverlayService",
    ).setAction("com.unyx.atlas.overlay.START")
    androidx.core.content.ContextCompat.startForegroundService(context, intent)
}

private fun stopAtlasOverlayService(context: android.content.Context) {
    context.stopService(
        Intent().setClassName(context.packageName, "com.unyx.atlas.feature.chat.AtlasOverlayService"),
    )
}

/**
 * ATHENA's controls, rendered as an ATLAS-owned settings section (see the CollapsibleSection call
 * site's doc) — the same [AthenaCoordinator] the background worker uses, with no dedicated Athena
 * activity/plugin in between. Presentation (Island, notification) is intentionally NOT wired here:
 * that's ATLAS's own decision to rebuild through its own surfaces, not something Athena pushes on
 * its own — see the coordinator's own doc for why that coupling was removed.
 */
@Composable
private fun AthenaSectionContent(coordinator: com.unyx.atlas.athena.AthenaCoordinator) {
    val prefs by coordinator.preferences.collectAsStateWithLifecycle()
    val message by coordinator.message.collectAsStateWithLifecycle()
    val events by coordinator.runtime.state.collectAsStateWithLifecycle()
    val health by coordinator.runtime.health.collectAsStateWithLifecycle()
    val diagnostics by coordinator.runtime.diagnostics.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun act(action: suspend () -> Unit) {
        scope.launch {
            busy = true; error = null
            try {
                action()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Não foi possível salvar a alteração."
            } finally {
                busy = false
            }
        }
    }
    val p = LocalExpressivePalette.current
    Text(
        text = "Clima e disponibilidade de serviços que o Atlas pode consultar. O acompanhamento dura 24 horas e pode ser pausado a qualquer momento.",
        style = MaterialTheme.typography.bodySmall,
        color = p.text,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    Text(message, style = MaterialTheme.typography.bodySmall, color = p.text, modifier = Modifier.padding(bottom = 10.dp))
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp))
    }
    val now = System.currentTimeMillis()
    SwitchRow("Acompanhar clima por 24 horas", prefs.weatherUntil > now) { act { coordinator.configure(weatherEnabled = it) } }
    SwitchRow("Acompanhar GitHub por 24 horas", prefs.githubUntil > now) { act { coordinator.configure(githubEnabled = it) } }
    if (maxOf(prefs.weatherUntil, prefs.githubUntil) > now) {
        Text(
            text = "Acompanhamento termina até ${athenaTime(java.time.Instant.ofEpochMilli(maxOf(prefs.weatherUntil, prefs.githubUntil)))}",
            style = MaterialTheme.typography.bodySmall,
            color = p.text,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 10.dp)) {
        Button(enabled = !busy, onClick = { act { coordinator.refresh() } }) { Text("Atualizar") }
        TextButton(enabled = !busy, onClick = { act { coordinator.configure(weatherEnabled = false, githubEnabled = false) } }) { Text("Pausar tudo") }
    }
    val freshEvents = events.filter { it.fresh(java.time.Instant.now()) && it.payload !is com.unyx.athena.core.WorldPayload.SustainedCold }
    if (freshEvents.isNotEmpty()) {
        Text("Condições consultadas", style = MaterialTheme.typography.titleSmall, color = p.text, modifier = Modifier.padding(bottom = 4.dp))
        freshEvents.forEach { event ->
            val detail = when (val payload = event.payload) {
                is com.unyx.athena.core.WorldPayload.Weather -> "${payload.conditions.temperatureC} °C"
                is com.unyx.athena.core.WorldPayload.Service -> when (payload.condition) {
                    com.unyx.athena.core.ServiceCondition.OPERATIONAL -> "Operacional"
                    com.unyx.athena.core.ServiceCondition.DEGRADED -> "Desempenho degradado"
                    com.unyx.athena.core.ServiceCondition.MAJOR_OUTAGE -> "Indisponibilidade parcial"
                    com.unyx.athena.core.ServiceCondition.CRITICAL_OUTAGE -> "Indisponibilidade importante"
                }
                else -> ""
            }
            Text(
                text = "${event.source.provider}: $detail — consultado ${athenaTime(event.observedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = p.text,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
    if (health.isNotEmpty()) {
        Text("Diagnóstico", style = MaterialTheme.typography.titleSmall, color = p.text, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        health.forEach { (id, status) ->
            Text(
                text = "$id: ${status.state} · ${status.attempts} tentativas · ${status.latencyMillis ?: 0} ms",
                style = MaterialTheme.typography.bodySmall,
                color = p.text,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Text(
            text = "Cache: ${diagnostics.cacheHits} acertos · ${diagnostics.cacheMisses} consultas",
            style = MaterialTheme.typography.bodySmall,
            color = p.text,
        )
    }
}

private fun athenaTime(instant: java.time.Instant): String =
    java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(java.time.ZoneId.systemDefault()).format(instant)

/**
 * A section header that starts collapsed and toggles open/closed on tap, so a fresh Settings open
 * shows a scannable list of section names instead of every option already expanded. Keyed by
 * [title] so each section keeps its own expanded state independently of the others.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val p = LocalExpressivePalette.current
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "sectionChevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(top = 26.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = p.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.2).sp,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        Icon(
            painter = painterResource(LauncherIcons.ChevronRight),
            contentDescription = null,
            tint = p.text,
            modifier = Modifier.size(20.dp).rotate(rotation),
        )
    }
    AnimatedVisibility(visible = expanded) {
        Column { content() }
    }
}

/** One runtime permission's live status; tap requests it. When Android suppresses the dialog
 *  (permanently denied), the only remaining path is the app's system settings page — opened
 *  automatically on a dialog-less denial. Re-checked on resume so a grant/revoke made in the
 *  system settings shows the moment the user comes back (the v0.6.7 notif-access pattern). */
@Composable
private fun PermissionRow(label: String, permissions: List<String>) {
    val context = LocalContext.current
    fun anyGranted() = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(anyGranted()) }
    LifecycleResumeEffect(permissions) {
        granted = anyGranted()
        onPauseOrDispose { }
    }
    // RequestMultiplePermissions (not the single-permission contract) even for location's pair:
    // requesting ACCESS_FINE_LOCATION alone without ACCESS_COARSE_LOCATION in the same call can
    // leave some OEM dialogs without the Precise/Approximate toggle.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        granted = results.values.any { it }
        val activity = context.findActivity()
        if (!granted && activity != null &&
            permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        ) {
            openAppDetailsSettings(context)
        }
    }
    ExpressiveActionRow(
        label = label,
        description = stringResource(
            if (granted) R.string.settings_permission_granted else R.string.settings_permission_not_granted,
        ),
    ) {
        if (!granted) launcher.launch(permissions.toTypedArray())
    }
}

/** WRITE_SETTINGS is a special-access permission, not a runtime one — there's no dialog to launch
 * via [ActivityResultContracts.RequestPermission]; the user must flip it on in its own dedicated
 * system screen ([Settings.ACTION_MANAGE_WRITE_SETTINGS]), the same way [DefaultAssistant] already
 * handles the assistant role elsewhere on this screen. Added after a user hit Atlas's set_brightness
 * tool reporting "preciso de uma permissão especial" with no way to act on it from the chat — this
 * row is that way. */
@Composable
private fun WriteSettingsPermissionRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Settings.System.canWrite(context)) }
    LifecycleResumeEffect(Unit) {
        granted = Settings.System.canWrite(context)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { granted = Settings.System.canWrite(context) }
    ExpressiveActionRow(
        label = stringResource(R.string.settings_permission_brightness),
        description = stringResource(
            if (granted) R.string.settings_permission_granted else R.string.settings_permission_not_granted,
        ),
    ) {
        if (!granted) {
            launcher.launch(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
}

/** Unwraps the Activity behind a Compose LocalContext (a ContextWrapper chain). */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The app's system details page — where a permanently denied permission can still be granted. */
private fun openAppDetailsSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = LocalExpressivePalette.current.text, fontSize = 16.sp)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val p = LocalExpressivePalette.current
    ExpressiveCard {
        RowLabel(label, Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                checkedThumbColor = Color.White,
                checkedBorderColor = Accent,
                uncheckedTrackColor = p.trackOff,
                uncheckedThumbColor = p.thumbOff,
                uncheckedBorderColor = p.trackOff,
            ),
        )
    }
}
