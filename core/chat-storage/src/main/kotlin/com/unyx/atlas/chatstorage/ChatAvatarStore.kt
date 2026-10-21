package com.unyx.atlas.chatstorage

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Plan §4.2 step 8 "Armazena no cache local protegido; não grava cópia aberta na galeria" —
 * avatar bytes (already processed: square, ≤512×512, ≤256 KiB, JPEG with EXIF stripped by the
 * re-encode itself — see `feature:connect`'s avatar pipeline) at rest, one slot for this device's
 * own avatar and one per paired contact who has sent one.
 *
 * An interface, not a class directly, because its only real implementation ([EncryptedFileChatAvatarStore])
 * is Context/Keystore-coupled the way [androidx.security.crypto.EncryptedFile] has no JVM-fake
 * equivalent (unlike `SharedPreferences`, which [FakeSharedPreferences] stands in for elsewhere in
 * this module) — same seam `core/chat-remote` already uses for
 * [com.unyx.atlas.chatremote.ChatMessageRemoteClient]/[com.unyx.atlas.chatremote.ChatPairingRemoteClient],
 * so tests can inject an in-memory fake instead of pulling in Robolectric.
 */
interface ChatAvatarStore {
    fun saveOwn(bytes: ByteArray)
    fun loadOwn(): ByteArray?
    fun deleteOwn()
    fun saveForContact(conversationId: String, bytes: ByteArray)
    fun loadForContact(conversationId: String): ByteArray?
    fun deleteForContact(conversationId: String)
}

/** Real production implementation — [EncryptedFile] with the same [MasterKey] scheme
 *  [com.unyx.atlas.security.AtlasSecurePreferences] already uses for every other `chat:storage`
 *  secret. An avatar is a photo of a real person, not lower-sensitivity than a display name. */
class EncryptedFileChatAvatarStore(private val context: Context) : ChatAvatarStore {

    private val directory: File by lazy { File(context.filesDir, "chat_avatars").apply { mkdirs() } }

    override fun saveOwn(bytes: ByteArray) = write(ownFile(), bytes)

    override fun loadOwn(): ByteArray? = read(ownFile())

    override fun deleteOwn() {
        ownFile().delete()
    }

    override fun saveForContact(conversationId: String, bytes: ByteArray) = write(contactFile(conversationId), bytes)

    override fun loadForContact(conversationId: String): ByteArray? = read(contactFile(conversationId))

    override fun deleteForContact(conversationId: String) {
        contactFile(conversationId).delete()
    }

    private fun write(file: File, bytes: ByteArray) {
        // EncryptedFile refuses to overwrite an existing file, unlike a normal FileOutputStream —
        // delete first so saving a NEW avatar over an old one (the common case: every profile
        // update) doesn't throw.
        file.delete()
        encryptedFile(file).openFileOutput().use { it.write(bytes) }
    }

    private fun read(file: File): ByteArray? {
        if (!file.exists()) return null
        return runCatching { encryptedFile(file).openFileInput().use { it.readBytes() } }.getOrNull()
    }

    private fun encryptedFile(file: File): EncryptedFile {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedFile.Builder(context, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
    }

    private fun ownFile() = File(directory, "own.bin")

    // Sanitized rather than used raw — conversationId is normally filesystem-safe already (a
    // pairing invitation id), but a filename built from external data is never trusted blind.
    private fun contactFile(conversationId: String): File {
        val safe = conversationId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(directory, "contact_$safe.bin")
    }
}
