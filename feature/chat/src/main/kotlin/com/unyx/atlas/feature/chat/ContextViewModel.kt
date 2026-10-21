package com.unyx.atlas.feature.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unyx.atlas.memory.MemoryContext
import com.unyx.atlas.memory.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

private const val CONTEXT_KEY_DEVICE_SNAPSHOT = "device_snapshot"

@HiltViewModel
class ContextViewModel @Inject constructor(
    private val memoryRepository: MemoryRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    var snapshot by mutableStateOf<DeviceContextSnapshot?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    /** Captures fresh, shows it immediately, and — best-effort, never blocking the UI on it — logs
     * it into `memory_context` (chave "device_snapshot") so it's the same real data a future chat
     * tool could read, not just something this screen alone knows about. */
    fun refresh() {
        val fresh = captureDeviceContext(appContext)
        snapshot = fresh
        viewModelScope.launch {
            isLoading = true
            error = null
            runCatching {
                val valor = JSONObject()
                    .put("batteryPercent", fresh.batteryPercent ?: JSONObject.NULL)
                    .put("batteryCharging", fresh.batteryCharging ?: JSONObject.NULL)
                    .put("network", fresh.network.name)
                memoryRepository.setContext(MemoryContext(chave = CONTEXT_KEY_DEVICE_SNAPSHOT, valor = valor.toString()))
            }.onFailure { error = it.message ?: "Não consegui salvar o contexto na memória (mostrando mesmo assim)" }
            isLoading = false
        }
    }
}
