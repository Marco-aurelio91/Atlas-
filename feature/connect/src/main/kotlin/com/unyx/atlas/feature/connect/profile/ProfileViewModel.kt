package com.unyx.atlas.feature.connect.profile

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unyx.atlas.chatremote.ChatMessagingRepository
import com.unyx.atlas.chatstorage.ChatAvatarStore
import com.unyx.atlas.chatstorage.ChatContactStore
import com.unyx.atlas.chatstorage.ChatProfile
import com.unyx.atlas.chatstorage.ChatProfileStore
import com.unyx.atlas.memory.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

const val PROFILE_DISPLAY_NAME_MAX_LENGTH = 60
const val PROFILE_STATUS_MESSAGE_MAX_LENGTH = 140
const val PROFILE_BIO_MAX_LENGTH = 280
const val PROFILE_INTEREST_MAX_COUNT = 10
const val PROFILE_INTEREST_MAX_CHARS_EACH = 24

/** Own chat identity, with account ID kept read-only and the profile fields shared over E2EE. */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileStore: ChatProfileStore,
    private val contactStore: ChatContactStore,
    private val avatarStore: ChatAvatarStore,
    private val messagingRepository: ChatMessagingRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    var displayName by mutableStateOf("")
        private set
    var statusMessage by mutableStateOf("")
        private set
    var bio by mutableStateOf("")
        private set
    /** Raw comma-separated text as the user types it — kept separate from the parsed
     *  [interesses] list so an in-progress "café, foto" doesn't lose the trailing comma while
     *  typing the next word. Parsed only on read ([interesses]) and on [save]. */
    var interessesInput by mutableStateOf("")
        private set
    val interesses: List<String> get() = parseInteresses(interessesInput)
    var atlasId by mutableStateOf("")
        private set
    var isDirty by mutableStateOf(false)
        private set
    var justSaved by mutableStateOf(false)
        private set
    var avatarBytes by mutableStateOf<ByteArray?>(null)
        private set
    var editorBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var isEditingAvatar by mutableStateOf(false)
        private set
    var isProcessingAvatar by mutableStateOf(false)
        private set
    var avatarError by mutableStateOf<String?>(null)
        private set

    private var loaded = false
    private var pendingAvatarUri: Uri? = null
    private var editorRotationDegrees = 0

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val profile = profileStore.load()
        displayName = profile?.displayName.orEmpty()
        statusMessage = profile?.statusMessage.orEmpty()
        bio = profile?.bio.orEmpty()
        interessesInput = profile?.interesses.orEmpty().joinToString(", ")
        avatarBytes = avatarStore.loadOwn()
        atlasId = authRepository.currentUserId().orEmpty()
    }

    fun onDisplayNameChange(value: String) {
        displayName = value.takeCodePoints(PROFILE_DISPLAY_NAME_MAX_LENGTH)
        isDirty = true
        justSaved = false
    }

    fun onStatusMessageChange(value: String) {
        statusMessage = value.takeCodePoints(PROFILE_STATUS_MESSAGE_MAX_LENGTH)
        isDirty = true
        justSaved = false
    }

    fun onBioChange(value: String) {
        bio = value.takeCodePoints(PROFILE_BIO_MAX_LENGTH)
        isDirty = true
        justSaved = false
    }

    fun onInteressesInputChange(value: String) {
        interessesInput = value
        isDirty = true
        justSaved = false
    }

    val canSave: Boolean
        get() = isDirty && displayName.isNotBlank()

    fun save() {
        if (!canSave) return
        val trimmedName = displayName.trim()
        val parsedInteresses = interesses
        profileStore.save(
            ChatProfile(displayName = trimmedName, statusMessage = statusMessage, bio = bio, interesses = parsedInteresses),
        )
        displayName = trimmedName
        isDirty = false
        justSaved = true
        broadcastProfileToExistingContacts(trimmedName, statusMessage, bio, parsedInteresses)
    }

    /** Loads an EXIF-corrected preview; photo changes are committed only after the user confirms the crop. */
    fun pickAvatar(resolver: ContentResolver, uri: Uri) {
        isProcessingAvatar = true
        avatarError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) { AvatarImagePipeline.prepareForEditing(resolver, uri) }
            when (result) {
                is AvatarEditorLoadResult.Success -> {
                    pendingAvatarUri = uri
                    editorBitmap = result.bitmap
                    editorRotationDegrees = 0
                    isEditingAvatar = true
                }
                is AvatarEditorLoadResult.Rejected -> avatarError = result.reason
            }
            isProcessingAvatar = false
        }
    }

    fun rotateEditorLeft() = rotateEditor(-90)
    fun rotateEditorRight() = rotateEditor(90)

    private fun rotateEditor(degrees: Int) {
        val current = editorBitmap ?: return
        val matrix = Matrix().apply { setRotate(degrees.toFloat()) }
        editorBitmap = runCatching {
            Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
        }.getOrDefault(current)
        editorRotationDegrees = (editorRotationDegrees + degrees + 360) % 360
    }

    fun cancelAvatarEdit() {
        editorBitmap = null
        pendingAvatarUri = null
        editorRotationDegrees = 0
        isEditingAvatar = false
        avatarError = null
    }

    fun applyAvatarCrop(resolver: ContentResolver, crop: AvatarCrop) {
        val uri = pendingAvatarUri ?: return
        isProcessingAvatar = true
        avatarError = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                AvatarImagePipeline.process(resolver, uri, crop, editorRotationDegrees)
            }
            when (result) {
                is AvatarProcessingResult.Success -> {
                    avatarStore.saveOwn(result.jpegBytes)
                    avatarBytes = result.jpegBytes
                    broadcastAvatarToExistingContacts(result.jpegBytes)
                    cancelAvatarEdit()
                }
                is AvatarProcessingResult.Rejected -> avatarError = result.reason
            }
            isProcessingAvatar = false
        }
    }

    fun removeAvatar() {
        avatarStore.deleteOwn()
        avatarBytes = null
        broadcastAvatarToExistingContacts(null)
    }

    private fun broadcastProfileToExistingContacts(displayName: String, statusMessage: String, bio: String, interesses: List<String>) {
        viewModelScope.launch {
            contactStore.listAll().forEach { contact ->
                runCatching {
                    messagingRepository.sendProfileUpdate(contact.conversationId, displayName, statusMessage, bio, interesses)
                }
            }
        }
    }

    private fun broadcastAvatarToExistingContacts(bytes: ByteArray?) {
        viewModelScope.launch {
            contactStore.listAll().forEach { contact ->
                runCatching { messagingRepository.sendAvatarUpdate(contact.conversationId, bytes) }
            }
        }
    }
}

private fun String.takeCodePoints(maximum: Int): String {
    val count = codePointCount(0, length)
    return if (count <= maximum) this else substring(0, offsetByCodePoints(0, maximum))
}

/** Splits the user's free-typed "café, fotografia, corrida" into a clean list — blank entries
 *  (consecutive commas, a trailing one) dropped, each trimmed and capped per
 *  [PROFILE_INTEREST_MAX_CHARS_EACH], the whole list capped at [PROFILE_INTEREST_MAX_COUNT]. */
internal fun parseInteresses(raw: String): List<String> = raw.split(",")
    .map { it.trim().takeCodePoints(PROFILE_INTEREST_MAX_CHARS_EACH) }
    .filter { it.isNotEmpty() }
    .take(PROFILE_INTEREST_MAX_COUNT)
