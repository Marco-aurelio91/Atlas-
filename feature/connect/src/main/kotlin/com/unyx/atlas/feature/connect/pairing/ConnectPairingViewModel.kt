package com.unyx.atlas.feature.connect.pairing

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unyx.atlas.chatcrypto.ChatSession
import com.unyx.atlas.chatpairing.InvalidPairingCardException
import com.unyx.atlas.chatpairing.PairingInvitation
import com.unyx.atlas.chatremote.AcceptedInvite
import com.unyx.atlas.chatremote.ChatMessagingRepository
import com.unyx.atlas.chatremote.ChatPairingException
import com.unyx.atlas.chatremote.ChatPairingRepository
import com.unyx.atlas.chatremote.ClaimedInvite
import com.unyx.atlas.chatstorage.ChatAvatarStore
import com.unyx.atlas.chatstorage.ChatProfile
import com.unyx.atlas.chatstorage.ChatProfileStore
import com.unyx.atlas.feature.connect.nfc.NfcCardPayloadHolder
import com.unyx.atlas.feature.connect.qr.QrCodeGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PairingMethod { PRESENT_QR, SCAN_QR }

/** Which step of the plan's §8.3 bilateral flow the screen is currently showing. Two distinct
 *  "waiting" steps exist deliberately — [AWAITING_CLAIM] (A, before anyone has scanned) and
 *  [AWAITING_REMOTE_CONFIRMATION] (B, after scanning but before A finishes confirming) — because
 *  they wait on different things and would otherwise read as the same vague "loading" state. */
enum class PairingPhase {
    METHOD_CHOICE,
    ENTERING_NAME,
    PRESENTING,
    AWAITING_CLAIM,
    VERIFY_AS_CREATOR,
    SCANNING,
    VERIFY_AS_CLAIMER,
    AWAITING_REMOTE_CONFIRMATION,
    ESTABLISHED,
    ERROR,
}

private const val POLL_INTERVAL_MILLIS = 2500L
private const val QR_BITMAP_SIZE_PX = 800

/**
 * Orchestrates the NFC/QR pairing screens on top of [ChatPairingRepository] — the repository
 * already does the real work (crypto, storage, the 4 Supabase RPCs); this ViewModel is only the
 * UI state machine on top of it, per
 * `docs/ATLAS_CHAT_Plano_Arquitetura_Layouts_e_Telas.md` §3's layering rule ("ViewModel: estado,
 * validações de interação e coordenação" / "não executa primitivas criptográficas próprias").
 *
 * NFC itself (plan §8.1) is explicitly NOT implemented here — see [PairingMethod], which only
 * has QR options. A hardware NFC reader/HCE service needs a connected device to build against
 * and test at all; QR covers the exact same bilateral handshake and works on any camera, so it's
 * what's built first, with NFC left as a later, additive option on the same method-choice screen.
 */
@HiltViewModel
class ConnectPairingViewModel @Inject constructor(
    private val repository: ChatPairingRepository,
    private val profileStore: ChatProfileStore,
    private val avatarStore: ChatAvatarStore,
    private val messagingRepository: ChatMessagingRepository,
    private val nfcCardPayloadHolder: NfcCardPayloadHolder,
) : ViewModel() {

    /** This device's own profile, for the self-preview [com.unyx.atlas.feature.connect.profile.ProfileCard]
     *  shown while presenting/scanning — "here's the card the other side will see once connected",
     *  never anything that has actually traveled over NFC/QR (see this class's own header doc). */
    var myProfile by mutableStateOf<ChatProfile?>(null)
        private set
    var myAvatarBytes by mutableStateOf<ByteArray?>(null)
        private set

    var phase by mutableStateOf(PairingPhase.METHOD_CHOICE)
        private set
    var method by mutableStateOf<PairingMethod?>(null)
        private set
    var displayNameInput by mutableStateOf("")
    /** What THIS device will call the new connection — shown alongside the fingerprint
     *  verification, not the name-entry step. See [ChatPairingRepository.confirmAndEstablish]'s
     *  own doc for why this is a name the user picks rather than something self-reported by the
     *  other side. Prefilled with the other side's own `displayName` when that's known (B always
     *  has it, straight from the card); left blank for A, who has no such default available. */
    var contactNameInput by mutableStateOf("")
    var isBusy by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    // A's side (presenting).
    var myInvitation by mutableStateOf<PairingInvitation?>(null)
        private set
    var myQrBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var detectedClaim by mutableStateOf<ClaimedInvite?>(null)
        private set

    // B's side (scanning).
    var accepted by mutableStateOf<AcceptedInvite?>(null)
        private set

    var establishedSession by mutableStateOf<ChatSession?>(null)
        private set

    private var pollingJob: Job? = null

    fun chooseMethod(selected: PairingMethod) {
        method = selected
        errorMessage = null
        // Only the presenting side's name is actually used today — it's embedded in the
        // invitation card itself (ChatPairingRepository.createInvite). The scanning side's
        // acceptInvite() takes no display name at all; asking for one here first would be a
        // text field that quietly does nothing; see onQrScanned's own doc for where that's
        // expected to change once chat:remote exchanges names in both directions.
        loadMyCard()
        phase = when (selected) {
            PairingMethod.PRESENT_QR -> {
                // Prefill from the local profile (plan §3) instead of starting blank every time —
                // the user can still edit it before generating the QR, this is only a default.
                if (displayNameInput.isBlank()) displayNameInput = profileStore.load()?.displayName.orEmpty()
                PairingPhase.ENTERING_NAME
            }
            PairingMethod.SCAN_QR -> PairingPhase.SCANNING
        }
    }

    /** Loads this device's own profile/avatar once per method choice, for the self-preview card —
     *  see this class's own doc for why this never represents anything received over the wire. */
    private fun loadMyCard() {
        myProfile = profileStore.load()
        myAvatarBytes = avatarStore.loadOwn()
    }

    /** Fire-and-forget, same `runCatching` discipline as `ProfileViewModel`'s own broadcast —
     *  without this, a freshly paired contact only learns this device's bio/interesses/avatar the
     *  next time "Meu perfil" is opened and saved again, which might never happen. */
    private fun broadcastOwnProfileTo(conversationId: String) {
        val profile = profileStore.load() ?: return
        viewModelScope.launch {
            runCatching {
                messagingRepository.sendProfileUpdate(
                    conversationId, profile.displayName, profile.statusMessage, profile.bio, profile.interesses,
                )
            }
            avatarStore.loadOwn()?.let { bytes -> runCatching { messagingRepository.sendAvatarUpdate(conversationId, bytes) } }
        }
    }

    fun back() {
        pollingJob?.cancel()
        when (phase) {
            PairingPhase.ENTERING_NAME -> {
                method = null
                phase = PairingPhase.METHOD_CHOICE
            }
            else -> {
                reset()
            }
        }
    }

    private fun reset() {
        pollingJob?.cancel()
        phase = PairingPhase.METHOD_CHOICE
        method = null
        displayNameInput = ""
        contactNameInput = ""
        isBusy = false
        errorMessage = null
        myInvitation = null
        myQrBitmap = null
        detectedClaim = null
        accepted = null
        establishedSession = null
        myProfile = null
        myAvatarBytes = null
        nfcCardPayloadHolder.current = null
    }

    /** Called once the user has typed a display name and confirmed — starts whichever flow
     *  [method] picked. */
    fun confirmName() {
        val name = displayNameInput.trim()
        if (name.isEmpty()) {
            errorMessage = "Digite um nome para continuar"
            return
        }
        if (method == PairingMethod.PRESENT_QR) startPresenting(name)
    }

    private fun startPresenting(displayName: String) {
        isBusy = true
        errorMessage = null
        viewModelScope.launch {
            val invitation = try {
                repository.createInvite(displayName, nowEpochSeconds())
            } catch (failure: ChatPairingException) {
                isBusy = false
                errorMessage = failure.message ?: "Não foi possível criar o convite"
                phase = PairingPhase.ERROR
                return@launch
            }
            myInvitation = invitation
            myQrBitmap = QrCodeGenerator.generate(invitation.serialize(), QR_BITMAP_SIZE_PX)
            // Both transports are offered at once from here on — QR and NFC race, whichever the
            // other person uses first wins (see this class's own header doc).
            nfcCardPayloadHolder.current = invitation.serialize()
            isBusy = false
            phase = PairingPhase.PRESENTING
            startClaimPolling(invitation)
        }
    }

    private fun startClaimPolling(invitation: PairingInvitation) {
        phase = PairingPhase.AWAITING_CLAIM
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                val claim = try {
                    repository.fetchClaim(invitation)
                } catch (failure: ChatPairingException) {
                    errorMessage = failure.message ?: "Erro ao verificar o convite"
                    phase = PairingPhase.ERROR
                    return@launch
                }
                if (claim != null) {
                    detectedClaim = claim
                    phase = PairingPhase.VERIFY_AS_CREATOR
                    return@launch
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** A taps "Confirmo" after comparing the fingerprint presencialmente (plan §8.3 step 7) —
     *  this is the one action that actually fixes the relationship server-side. [contactNameInput]
     *  is what A is choosing to call this connection going forward (see this class's own field
     *  doc); blank is rejected the same way an empty invitation name is. */
    fun confirmFingerprintAsCreator() {
        val invitation = myInvitation ?: return
        val claim = detectedClaim ?: return
        val contactName = contactNameInput.trim()
        if (contactName.isEmpty()) {
            errorMessage = "Digite um nome para essa conexão"
            return
        }
        isBusy = true
        viewModelScope.launch {
            try {
                val session = repository.confirmAndEstablish(invitation, claim, contactName)
                establishedSession = session
                isBusy = false
                phase = PairingPhase.ESTABLISHED
                broadcastOwnProfileTo(session.conversationId)
            } catch (failure: ChatPairingException) {
                isBusy = false
                errorMessage = failure.message ?: "Não foi possível confirmar o pareamento"
                phase = PairingPhase.ERROR
            }
        }
    }

    /** Either transport (camera-scanned QR, or NFC tap — see [NfcCardReader]) handed back raw
     *  card bytes — parse/claim immediately, same as the repository's own
     *  [ChatPairingRepository.acceptInvite] contract (format validation happens inside it,
     *  surfacing [InvalidPairingCardException] for a malformed/expired card — T-05). Prefills
     *  [contactNameInput] with A's own self-declared name from the card — B can keep it or
     *  rename before confirming. */
    fun onCardBytesReceived(cardBytes: ByteArray) {
        if (isBusy) return
        isBusy = true
        errorMessage = null
        viewModelScope.launch {
            val result = try {
                repository.acceptInvite(cardBytes, nowEpochSeconds())
            } catch (invalid: InvalidPairingCardException) {
                isBusy = false
                errorMessage = "QR inválido ou expirado — peça para gerar um novo"
                phase = PairingPhase.ERROR
                return@launch
            } catch (failure: ChatPairingException) {
                isBusy = false
                errorMessage = failure.message ?: "Não foi possível ler este convite"
                phase = PairingPhase.ERROR
                return@launch
            }
            accepted = result
            contactNameInput = result.invitation.displayName
            isBusy = false
            phase = PairingPhase.VERIFY_AS_CLAIMER
        }
    }

    /** B taps "Confirmo" after comparing the same fingerprint (plan §8.3 step 7) — unlike A's
     *  side, this doesn't call any RPC itself; it starts polling for A's own confirmation
     *  ([ChatPairingRepository.waitForConfirmation] is a one-shot check, this loop is what turns
     *  it into "wait until"). */
    fun confirmFingerprintAsClaimer() {
        val acceptedInvite = accepted ?: return
        val contactName = contactNameInput.trim()
        if (contactName.isEmpty()) {
            errorMessage = "Digite um nome para essa conexão"
            return
        }
        phase = PairingPhase.AWAITING_REMOTE_CONFIRMATION
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                val session = try {
                    repository.waitForConfirmation(acceptedInvite, contactName)
                } catch (failure: ChatPairingException) {
                    errorMessage = failure.message ?: "Erro ao verificar a confirmação"
                    phase = PairingPhase.ERROR
                    return@launch
                }
                if (session != null) {
                    establishedSession = session
                    phase = PairingPhase.ESTABLISHED
                    broadcastOwnProfileTo(session.conversationId)
                    return@launch
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        myQrBitmap?.recycle()
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000
}
