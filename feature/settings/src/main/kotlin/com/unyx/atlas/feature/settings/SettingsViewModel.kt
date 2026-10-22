package com.unyx.atlas.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.unyx.atlas.athena.AthenaCoordinator
import com.unyx.atlas.data.SettingsRepository
import com.unyx.atlas.memory.AccountKeyRecoveryRepository
import com.unyx.atlas.memory.MemoryRepository
import com.unyx.atlas.memory.RecoveryEnvelopeAlreadyExistsException
import com.unyx.atlas.memory.auth.AuthRepository
import com.unyx.atlas.memory.auth.AuthMfaFactor
import com.unyx.atlas.memory.auth.TotpEnrollment
import com.unyx.atlas.security.AccountKeyState
import com.unyx.atlas.security.AtlasAccountKeyManager
import com.unyx.atlas.security.RecoveryEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DataExportUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

data class AccountDeletionUiState(
    val busy: Boolean = false,
    val error: String? = null,
)

data class AtlasSecurityUiState(
    val keyState: AccountKeyState? = null,
    val persistentStorageAvailable: Boolean = true,
    val recoveryEnvelopeAvailable: Boolean? = null,
    val recoveryStatusError: String? = null,
    val recoveryCode: String? = null,
    val codeInput: String = "",
    val recoveryEnvelopeUploaded: Boolean = false,
    val recoveryEnvelopeConflict: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val mfaFactors: List<AuthMfaFactor> = emptyList(),
    val totpEnrollment: TotpEnrollment? = null,
    val mfaCode: String = "",
    val mfaBusy: Boolean = false,
    val mfaMessage: String? = null,
    val mfaError: String? = null,
) {
    override fun toString(): String =
        "AtlasSecurityUiState(keyState=$keyState, persistentStorageAvailable=$persistentStorageAvailable, " +
            "recoveryCode=[REDACTED], codeInput=[REDACTED], recoveryEnvelopeUploaded=$recoveryEnvelopeUploaded, " +
            "recoveryEnvelopeConflict=$recoveryEnvelopeConflict, busy=$busy, message=$message, error=$error, " +
            "mfaFactors=${mfaFactors.size}, totpEnrollment=[REDACTED], mfaCode=[REDACTED], mfaBusy=$mfaBusy, " +
            "mfaMessage=$mfaMessage, mfaError=$mfaError)"
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    // ATHENA has no screen of its own (2026-09-30 correction) — ATLAS's own Settings renders its
    // controls directly against this coordinator, the same one the background worker/app startup
    // already use. See AthenaSectionContent in SettingsScreen.kt.
    val athenaCoordinator: AthenaCoordinator,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val accountKeyManager: AtlasAccountKeyManager,
    private val recoveryRepository: AccountKeyRecoveryRepository,
    private val memoryRepository: MemoryRepository,
    private val aiUsageMeter: com.unyx.atlas.data.ai.AiUsageMeter,
) : ViewModel() {
    val authState = authRepository.state
    val overlayEnabled = settingsRepository.overlayEnabled

    suspend fun setOverlayEnabled(enabled: Boolean) {
        settingsRepository.setOverlayEnabled(enabled)
    }

    /** Last 7 days of AI token usage measured on this device, newest first (see AiUsageMeter). */
    fun aiUsageDays(): List<com.unyx.atlas.data.ai.AiDayUsage> = aiUsageMeter.recent(7)

    private val _exportState = MutableStateFlow(DataExportUiState())
    val exportState: StateFlow<DataExportUiState> = _exportState.asStateFlow()

    /** Writes the user's decrypted data as JSON to the stream [openOutput] returns (the document the
     * user picked). Stays on this device; the file is the user's to keep or share. */
    fun exportData(userId: String, openOutput: () -> java.io.OutputStream?) {
        if (authRepository.currentUserId() != userId || _exportState.value.busy) return
        viewModelScope.launch {
            _exportState.value = DataExportUiState(busy = true)
            _exportState.value = try {
                val json = memoryRepository.exportAll().toString(2)
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val stream = openOutput() ?: error("Não foi possível abrir o arquivo escolhido.")
                    stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                DataExportUiState(message = "Dados exportados. Guarde o arquivo em um lugar seguro: ele não é cifrado.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DataExportUiState(error = "Não foi possível exportar agora. Verifique a chave de memória e a conexão e tente novamente.")
            }
        }
    }

    private val _deletionState = MutableStateFlow(AccountDeletionUiState())
    val deletionState: StateFlow<AccountDeletionUiState> = _deletionState.asStateFlow()

    /** Permanently deletes the signed-in account (server data + session). On success the auth state
     * flips to LoggedOut by itself, which the Settings screen already reacts to. */
    fun deleteAccount(userId: String) {
        if (authRepository.currentUserId() != userId || _deletionState.value.busy) return
        viewModelScope.launch {
            _deletionState.value = AccountDeletionUiState(busy = true)
            val result = authRepository.deleteAccount()
            _deletionState.value = result.fold(
                onSuccess = { AccountDeletionUiState() },
                onFailure = {
                    AccountDeletionUiState(error = "Não foi possível excluir a conta agora. Verifique a conexão e tente novamente — nada foi apagado.")
                },
            )
        }
    }

    fun clearDeletionError() {
        _deletionState.value = _deletionState.value.copy(error = null)
    }

    private val _securityState = MutableStateFlow(AtlasSecurityUiState())
    val securityState: StateFlow<AtlasSecurityUiState> = _securityState.asStateFlow()
    private var pendingEnvelopeForScreen: RecoveryEnvelope? = null
    private var loadedSecurityUserId: String? = null

    fun loadSecurity(userId: String?) {
        if (loadedSecurityUserId == userId) return
        loadedSecurityUserId = userId
        pendingEnvelopeForScreen = null
        if (userId == null) {
            _securityState.value = AtlasSecurityUiState()
            return
        }
        _securityState.value = AtlasSecurityUiState(busy = true)
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { accountKeyManager.status(userId) }
                val recoveryResult = recoveryRepository.fetch(userId)
                val factorsResult = authRepository.mfaFactors()
                if (authRepository.currentUserId() == userId) {
                    _securityState.value = AtlasSecurityUiState(
                        keyState = status.state,
                        persistentStorageAvailable = status.persistentStorageAvailable,
                        recoveryEnvelopeAvailable = when {
                            recoveryResult.isFailure -> null
                            recoveryResult.getOrNull() != null -> true
                            else -> false
                        },
                        recoveryStatusError = recoveryResult.exceptionOrNull()?.let {
                            "Não foi possível verificar se há uma chave de recuperação salva na conta."
                        },
                        mfaFactors = factorsResult.getOrDefault(emptyList()),
                        mfaError = factorsResult.exceptionOrNull()?.let { "Não foi possível consultar a autenticação em duas etapas." },
                    )
                    // Best-effort sweep for any legacy table this account's own workflow never
                    // happened to touch — see MemoryRepository.migrateAllLegacyData's doc.
                    if (status.state == AccountKeyState.ACTIVE) {
                        viewModelScope.launch { memoryRepository.migrateAllLegacyData() }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (authRepository.currentUserId() == userId) {
                    _securityState.value = AtlasSecurityUiState(
                        persistentStorageAvailable = false,
                        recoveryStatusError = "Não foi possível verificar a chave de recuperação da conta.",
                        error = "Não foi possível consultar o estado da chave neste aparelho.",
                    )
                }
            }
        }
    }

    fun refreshSecurity(userId: String) {
        if (authRepository.currentUserId() != userId) return
        loadedSecurityUserId = null
        loadSecurity(userId)
    }

    fun updateRecoveryCode(value: String) {
        _securityState.value = _securityState.value.copy(codeInput = value, error = null)
    }

    fun updateMfaCode(value: String) {
        _securityState.value = _securityState.value.copy(
            mfaCode = value.filter(Char::isDigit).take(6),
            mfaError = null,
            mfaMessage = null,
        )
    }

    fun beginTotpEnrollment(userId: String) {
        if (authRepository.currentUserId() != userId || _securityState.value.mfaBusy) return
        viewModelScope.launch {
            _securityState.value = _securityState.value.copy(mfaBusy = true, mfaError = null, mfaMessage = null)
            val result = authRepository.beginTotpEnrollment()
            if (authRepository.currentUserId() != userId) return@launch
            _securityState.value = result.fold(
                onSuccess = { enrollment ->
                    _securityState.value.copy(
                        totpEnrollment = enrollment,
                        mfaCode = "",
                        mfaBusy = false,
                        mfaMessage = "Adicione o segredo ao seu autenticador e confirme com o código atual.",
                    )
                },
                onFailure = {
                    _securityState.value.copy(mfaBusy = false, mfaError = "Não foi possível iniciar a configuração de MFA.")
                },
            )
        }
    }

    fun confirmTotpEnrollment(userId: String) {
        val enrollment = _securityState.value.totpEnrollment ?: return
        val code = _securityState.value.mfaCode
        if (authRepository.currentUserId() != userId || _securityState.value.mfaBusy) return
        viewModelScope.launch {
            _securityState.value = _securityState.value.copy(mfaBusy = true, mfaError = null, mfaMessage = null)
            val result = authRepository.verifyTotpEnrollment(enrollment, code)
            if (authRepository.currentUserId() != userId) return@launch
            if (result.isFailure) {
                _securityState.value = _securityState.value.copy(
                    mfaBusy = false,
                    mfaError = "O código não confere ou expirou. Confira o horário do aparelho e tente novamente.",
                )
                return@launch
            }
            val factors = authRepository.mfaFactors()
            if (authRepository.currentUserId() != userId) return@launch
            _securityState.value = _securityState.value.copy(
                mfaFactors = factors.getOrDefault(emptyList()),
                totpEnrollment = null,
                mfaCode = "",
                mfaBusy = false,
                mfaMessage = "Autenticação em duas etapas ativada.",
                mfaError = factors.exceptionOrNull()?.let { "MFA foi ativado, mas a lista não pôde ser atualizada." },
            )
        }
    }

    fun cancelTotpEnrollment(userId: String) {
        val enrollment = _securityState.value.totpEnrollment ?: return
        if (authRepository.currentUserId() != userId || _securityState.value.mfaBusy) return
        viewModelScope.launch {
            _securityState.value = _securityState.value.copy(mfaBusy = true, mfaError = null, mfaMessage = null)
            val result = authRepository.removeMfaFactor(enrollment.factorId)
            if (authRepository.currentUserId() != userId) return@launch
            _securityState.value = _securityState.value.copy(
                totpEnrollment = null,
                mfaCode = "",
                mfaBusy = false,
                mfaMessage = if (result.isSuccess) "Configuração pendente removida." else null,
                mfaError = result.exceptionOrNull()?.let { "Não foi possível remover a configuração pendente." },
            )
        }
    }

    fun removeTotpFactor(userId: String, factorId: String) {
        if (authRepository.currentUserId() != userId || _securityState.value.mfaBusy) return
        viewModelScope.launch {
            _securityState.value = _securityState.value.copy(mfaBusy = true, mfaError = null, mfaMessage = null)
            val result = authRepository.removeMfaFactor(factorId)
            if (authRepository.currentUserId() != userId) return@launch
            if (result.isFailure) {
                _securityState.value = _securityState.value.copy(
                    mfaBusy = false,
                    mfaError = "Não foi possível remover o autenticador. Tente novamente.",
                )
                return@launch
            }
            val factors = authRepository.mfaFactors()
            if (authRepository.currentUserId() != userId) return@launch
            _securityState.value = _securityState.value.copy(
                mfaFactors = factors.getOrDefault(emptyList()),
                mfaBusy = false,
                mfaMessage = "Autenticador removido.",
                mfaError = factors.exceptionOrNull()?.let { "Autenticador removido, mas a lista não pôde ser atualizada." },
            )
        }
    }

    fun beginAccountKeySetup(userId: String) {
        viewModelScope.launch {
            _securityState.value = _securityState.value.copy(busy = true, error = null, message = null)
            try {
                check(authRepository.currentUserId() == userId) { "The signed-in account changed" }
                val setup = withContext(Dispatchers.IO) { accountKeyManager.beginSetup(userId) }
                if (authRepository.currentUserId() != userId) {
                    withContext(Dispatchers.IO) { accountKeyManager.discardPendingSetup(userId) }
                    return@launch
                }
                pendingEnvelopeForScreen = setup.recoveryEnvelope
                _securityState.value = _securityState.value.copy(
                    keyState = AccountKeyState.SETUP_PENDING,
                    recoveryCode = setup.recoveryCode,
                    codeInput = "",
                    recoveryEnvelopeUploaded = false,
                    recoveryEnvelopeConflict = false,
                    busy = false,
                    message = "Guarde o código em local seguro e confirme que consegue recuperá-lo.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (authRepository.currentUserId() != userId) return@launch
                _securityState.value = _securityState.value.copy(
                    busy = false,
                    error = "Não foi possível iniciar a proteção. Verifique se o armazenamento seguro do Android está disponível.",
                )
            }
        }
    }

    fun confirmAccountKeySetup(userId: String, recoveryCodeSaved: Boolean) {
        viewModelScope.launch {
            if (authRepository.currentUserId() != userId) return@launch
            val current = _securityState.value
            if (!recoveryCodeSaved) {
                _securityState.value = current.copy(error = "Salve o código fora do Atlas antes de ativar a chave.")
                return@launch
            }
            val code = current.codeInput
            _securityState.value = current.copy(busy = true, error = null)
            var envelopeUploaded = current.recoveryEnvelopeUploaded
            try {
                check(authRepository.currentUserId() == userId) { "The signed-in account changed" }
                withContext(Dispatchers.IO) { accountKeyManager.verifyRecoveryCode(userId, code) }
                if (!envelopeUploaded) {
                    val envelope = pendingEnvelopeForScreen
                        ?: withContext(Dispatchers.IO) { accountKeyManager.pendingRecoveryEnvelope(userId) }
                    val publishResult = recoveryRepository.publish(userId, envelope)
                    val publishFailure = publishResult.exceptionOrNull()
                    if (publishFailure != null) {
                        if (authRepository.currentUserId() != userId) return@launch
                        _securityState.value = current.copy(
                            busy = false,
                            recoveryEnvelopeUploaded = false,
                            recoveryEnvelopeConflict = publishFailure is RecoveryEnvelopeAlreadyExistsException,
                            error = if (publishFailure is RecoveryEnvelopeAlreadyExistsException) {
                                "Esta conta já tem uma chave de recuperação. Restaure a chave existente para evitar substituí-la."
                            } else {
                                "Não foi possível salvar o envelope no Supabase. Confira a migração e tente novamente."
                            },
                        )
                        return@launch
                    }
                    if (authRepository.currentUserId() != userId) return@launch
                    envelopeUploaded = true
                    _securityState.value = current.copy(
                        busy = true,
                        recoveryEnvelopeUploaded = true,
                        recoveryEnvelopeConflict = false,
                        message = "Código de recuperação salvo na conta. Ativando a chave…",
                    )
                }
                withContext(Dispatchers.IO) {
                    accountKeyManager.confirmRecoveryCode(
                        userId = userId,
                        recoveryCode = code,
                        recoveryEnvelopeUploaded = envelopeUploaded,
                    )
                }
                if (authRepository.currentUserId() != userId) return@launch
                pendingEnvelopeForScreen = null
                _securityState.value = AtlasSecurityUiState(
                    keyState = AccountKeyState.ACTIVE,
                    persistentStorageAvailable = true,
                    recoveryEnvelopeAvailable = true,
                    message = "Chave da conta protegida e código de recuperação confirmado neste aparelho.",
                )
                viewModelScope.launch { memoryRepository.migrateAllLegacyData() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (authRepository.currentUserId() != userId) return@launch
                _securityState.value = current.copy(
                    busy = false,
                    recoveryEnvelopeUploaded = envelopeUploaded,
                    error = if (envelopeUploaded) {
                        "O código foi salvo, mas não foi possível ativar a chave. Tente confirmar novamente."
                    } else {
                        "O código não confere. Digite exatamente o código exibido e tente novamente."
                    },
                )
            }
        }
    }

    fun restartPendingSetup(userId: String) {
        viewModelScope.launch {
            try {
                check(authRepository.currentUserId() == userId) { "The signed-in account changed" }
                withContext(Dispatchers.IO) { accountKeyManager.discardPendingSetup(userId) }
                if (authRepository.currentUserId() != userId) return@launch
                pendingEnvelopeForScreen = null
                _securityState.value = AtlasSecurityUiState(persistentStorageAvailable = true)
                beginAccountKeySetup(userId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (authRepository.currentUserId() != userId) return@launch
                _securityState.value = _securityState.value.copy(
                    error = "Não foi possível reiniciar a configuração da chave.",
                )
            }
        }
    }

    fun cancelPendingSetupToRestore(userId: String) {
        viewModelScope.launch {
            try {
                check(authRepository.currentUserId() == userId) { "The signed-in account changed" }
                withContext(Dispatchers.IO) { accountKeyManager.discardPendingSetup(userId) }
                if (authRepository.currentUserId() != userId) return@launch
                pendingEnvelopeForScreen = null
                _securityState.value = AtlasSecurityUiState(persistentStorageAvailable = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (authRepository.currentUserId() != userId) return@launch
                _securityState.value = _securityState.value.copy(
                    error = "Não foi possível cancelar a configuração pendente.",
                )
            }
        }
    }

    fun restoreAccountKey(userId: String) {
        viewModelScope.launch {
            val current = _securityState.value
            if (authRepository.currentUserId() != userId) return@launch
            _securityState.value = current.copy(busy = true, error = null, message = null)
            val envelope = recoveryRepository.fetch(userId).getOrElse {
                if (authRepository.currentUserId() != userId) return@launch
                _securityState.value = current.copy(
                    busy = false,
                    error = "Não foi possível obter o envelope de recuperação da conta.",
                )
                return@launch
            }
            if (envelope == null) {
                if (authRepository.currentUserId() != userId) return@launch
                _securityState.value = current.copy(
                    busy = false,
                    error = "Esta conta ainda não tem um envelope de recuperação salvo.",
                )
                return@launch
            }
            try {
                check(authRepository.currentUserId() == userId) { "The signed-in account changed" }
                withContext(Dispatchers.IO) {
                    accountKeyManager.restoreFromRecovery(userId, current.codeInput, envelope)
                }
                if (authRepository.currentUserId() != userId) return@launch
                _securityState.value = AtlasSecurityUiState(
                    keyState = AccountKeyState.ACTIVE,
                    persistentStorageAvailable = true,
                    recoveryEnvelopeAvailable = true,
                    message = "Chave da conta restaurada e protegida pelo Keystore deste aparelho.",
                )
                viewModelScope.launch { memoryRepository.migrateAllLegacyData() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (authRepository.currentUserId() != userId) return@launch
                _securityState.value = current.copy(
                    busy = false,
                    error = "Não foi possível restaurar. Confira o código e tente novamente.",
                )
            }
        }
    }
}
