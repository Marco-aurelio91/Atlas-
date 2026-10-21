package com.unyx.atlas.feature.chat

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.unyx.atlas.data.ai.ChatMessage
import com.unyx.atlas.data.ai.PersistedAction
import com.unyx.atlas.security.AtlasSecurePreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How many recent messages stay on disk per conversation — a personal assistant transcript can
 * run for a very long time; this keeps local storage bounded without needing a real database. */
private const val MAX_STORED_MESSAGES = 200

/** One conversation's metadata (not its messages — those are stored/loaded separately, keyed by
 * [id], so switching conversations or listing them for the drawer never has to touch message
 * bodies that aren't needed yet). [title] is null until either the user renames it or the first
 * message arrives (see [ChatViewModel]'s auto-title-from-first-message) — the UI falls back to
 * "Nova conversa" for a null title. */
data class ConversationSummary(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
)

/**
 * Persists Atlas's chat transcripts locally so reopening Atlas resumes where the user left off,
 * across possibly-many named conversations (item 20–22 of the design spec: novas conversas,
 * renomear, pesquisar, exportar, fixar). Encrypted (same approach as
 * core.memory.auth.AuthSessionStore) since a personal conversation can easily contain sensitive
 * content — this is local scrollback only, separate from the cognitive memory in Supabase.
 *
 * Storage shape: one `conversations_index` key plus one `messages_<id>` key per conversation.
 * PDF bodies are stored separately in authenticated-encrypted files and referenced by content
 * digest, keeping large documents out of SharedPreferences. If the Android Keystore is unavailable,
 * new data stays in process memory.
 */
class ChatHistoryStore(context: Context) {
    private val saveMutex = Mutex()
    private val appContext = context.applicationContext
    private val attachmentsDirectory = File(appContext.filesDir, "atlas_chat_attachments")
    private val masterKey by lazy {
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    private val prefs = AtlasSecurePreferences.open(
        context = appContext,
        secureName = "atlas_chat_secure",
        legacyPlainName = "atlas_chat",
    ).preferences

    // ---- Conversation index ----

    fun listConversations(): List<ConversationSummary> {
        val raw = prefs.getString(KEY_INDEX, null) ?: run {
            // First-ever run of the multi-conversation store: migrate the old single flat
            // transcript (if any) into a real conversation instead of silently discarding it.
            return migrateLegacySingleConversation()
        }
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i -> array.getJSONObject(i).toConversationSummary() }
        }.getOrElse { emptyList() }
    }

    private fun migrateLegacySingleConversation(): List<ConversationSummary> {
        val legacy = prefs.getString(KEY_LEGACY_MESSAGES, null) ?: return emptyList()
        val messages = runCatching { parseMessages(JSONArray(legacy)) }.getOrElse { emptyList() }
        if (messages.isEmpty()) return emptyList()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val summary = ConversationSummary(id = id, title = null, createdAt = now, updatedAt = now, pinned = false)
        writeMessages(id, messages)
        writeIndex(listOf(summary))
        setCurrentConversationId(id)
        prefs.edit().remove(KEY_LEGACY_MESSAGES).apply()
        return listOf(summary)
    }

    private fun writeIndex(conversations: List<ConversationSummary>) {
        val array = JSONArray()
        conversations.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_INDEX, array.toString()).apply()
    }

    fun createConversation(): ConversationSummary {
        val now = System.currentTimeMillis()
        val summary = ConversationSummary(id = UUID.randomUUID().toString(), title = null, createdAt = now, updatedAt = now, pinned = false)
        writeIndex(listConversations() + summary)
        return summary
    }

    fun renameConversation(id: String, title: String) {
        writeIndex(listConversations().map { if (it.id == id) it.copy(title = title) else it })
    }

    /** Called once, the moment a brand-new conversation gets its first message — only takes effect
     * if the conversation is still untitled, so it never clobbers a manual rename. */
    fun autoTitleIfUntitled(id: String, title: String) {
        writeIndex(listConversations().map { if (it.id == id && it.title == null) it.copy(title = title) else it })
    }

    fun setPinned(id: String, pinned: Boolean) {
        writeIndex(listConversations().map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun touch(id: String) {
        writeIndex(listConversations().map { if (it.id == id) it.copy(updatedAt = System.currentTimeMillis()) else it })
    }

    fun deleteConversation(id: String) {
        writeIndex(listConversations().filterNot { it.id == id })
        prefs.edit().remove(messagesKey(id)).apply()
        if (currentConversationId() == id) prefs.edit().remove(KEY_CURRENT_ID).apply()
        pruneUnreferencedPdfFiles()
    }

    fun currentConversationId(): String? = prefs.getString(KEY_CURRENT_ID, null)

    fun setCurrentConversationId(id: String) {
        prefs.edit().putString(KEY_CURRENT_ID, id).apply()
    }

    // ---- Per-conversation messages ----

    fun loadMessages(conversationId: String): List<ChatMessage> {
        val raw = prefs.getString(messagesKey(conversationId), null) ?: return emptyList()
        return runCatching { parseMessages(JSONArray(raw)) }.getOrElse { emptyList() }
    }

    suspend fun saveMessages(conversationId: String, messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        saveMutex.withLock { writeMessages(conversationId, messages) }
    }

    private fun writeMessages(conversationId: String, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_STORED_MESSAGES).forEach { message ->
            val attachmentKey = message.pdfBase64?.let(::persistPdf)
            array.put(message.toJson(attachmentKey))
        }
        prefs.edit().putString(messagesKey(conversationId), array.toString()).apply()
        touch(conversationId)
    }

    /** Wipes every conversation — used on sign-out, since this local scrollback shouldn't survive
     * for a different person signing in on the same device. */
    fun clearAll() {
        val ids = listConversations().map { it.id }
        val editor = prefs.edit()
        ids.forEach { editor.remove(messagesKey(it)) }
        editor.remove(KEY_INDEX)
        editor.remove(KEY_CURRENT_ID)
        editor.remove(KEY_LEGACY_MESSAGES)
        editor.apply()
        attachmentsDirectory.listFiles()
            ?.filter { it.name.startsWith(PDF_FILE_PREFIX) && it.name.endsWith(PDF_FILE_SUFFIX) }
            ?.forEach(File::delete)
    }

    private fun messagesKey(id: String) = "messages_$id"

    private fun parseMessages(array: JSONArray): List<ChatMessage> =
        List(array.length()) { i -> array.getJSONObject(i).toChatMessage() }

    private fun ChatMessage.toJson(pdfAttachmentKey: String?): JSONObject = JSONObject()
        .put("role", role)
        .put("content", content)
        .put("usedMemory", usedMemory)
        .put("modelText", modelText ?: JSONObject.NULL)
        .put(
            "actions",
            JSONArray().apply {
                actions.forEach { action ->
                    put(
                        JSONObject()
                            .put("toolName", action.toolName)
                            .put("resultSummary", action.resultSummary)
                            .put("failed", action.failed),
                    )
                }
            },
        )
        .put("memoryLabels", JSONArray(memoryLabels))
        .put("imageBase64", imageBase64 ?: JSONObject.NULL)
        .put("pdfFileName", pdfFileName ?: JSONObject.NULL)
        .put("pdfAttachment", pdfAttachmentKey ?: JSONObject.NULL)

    private fun JSONObject.toChatMessage(): ChatMessage {
        val attachmentKey = optString("pdfAttachment").takeIf(String::isNotBlank)
        val pdfBase64 = attachmentKey?.let { key ->
            try {
                readPdf(key)
            } catch (failure: Exception) {
                Log.e(TAG, "Não foi possível recuperar o PDF anexado à conversa.", failure)
                null
            }
        }
        val content = getString("content")
        val attachmentUnavailable = attachmentKey != null && pdfBase64 == null
        return ChatMessage(
            role = getString("role"),
            content = if (attachmentUnavailable) "$content\n[Anexo indisponível: não foi possível recuperar o PDF.]" else content,
            usedMemory = optBoolean("usedMemory", false),
            modelText = optionalString("modelText"),
            actions = optJSONArray("actions")?.let { arr ->
                List(arr.length()) { i ->
                    val a = arr.getJSONObject(i)
                    PersistedAction(
                        toolName = a.getString("toolName"),
                        resultSummary = a.getString("resultSummary"),
                        failed = a.getBoolean("failed"),
                    )
                }
            } ?: emptyList(),
            memoryLabels = optJSONArray("memoryLabels")?.let { arr -> List(arr.length()) { i -> arr.getString(i) } } ?: emptyList(),
            imageBase64 = optionalString("imageBase64"),
            pdfBase64 = pdfBase64,
            pdfFileName = optionalString("pdfFileName"),
        )
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun persistPdf(base64: String): String {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        try {
            require(bytes.size <= MAX_PDF_BYTES) { "O PDF excede o limite de armazenamento local." }
            val key = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val file = pdfFile(key)
            if (!file.exists()) {
                check(attachmentsDirectory.exists() || attachmentsDirectory.mkdirs()) {
                    "Não foi possível criar o diretório seguro dos anexos."
                }
                try {
                    encryptedFile(file).openFileOutput().use { it.write(bytes) }
                } catch (failure: Exception) {
                    file.delete()
                    throw failure
                }
            }
            return key
        } finally {
            bytes.fill(0)
        }
    }

    private fun readPdf(key: String): String {
        require(key.matches(PDF_KEY_REGEX)) { "Identificador inválido para anexo PDF." }
        val file = pdfFile(key)
        check(file.isFile) { "Arquivo de PDF anexado não encontrado." }
        val output = ByteArrayOutputStream()
        encryptedFile(file).openFileInput().use { input ->
            val buffer = ByteArray(8192)
                try {
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_PDF_BYTES) { "O PDF armazenado excede o limite permitido." }
                        output.write(buffer, 0, count)
                    }
                } finally {
                    buffer.fill(0)
                }
            }
        val bytes = output.toByteArray()
        return try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bytes.fill(0)
        }
    }

    private fun encryptedFile(file: File): EncryptedFile = EncryptedFile.Builder(
        appContext,
        file,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    private fun pdfFile(key: String) = File(attachmentsDirectory, "$PDF_FILE_PREFIX$key$PDF_FILE_SUFFIX")

    private fun pruneUnreferencedPdfFiles() {
        val referenced = mutableSetOf<String>()
        for (conversation in listConversations()) {
            val raw = prefs.getString(messagesKey(conversation.id), null) ?: continue
            try {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.optString("pdfAttachment")
                        ?.takeIf { it.matches(PDF_KEY_REGEX) }
                        ?.let(referenced::add)
                }
            } catch (failure: Exception) {
                Log.w(TAG, "Anexos antigos foram mantidos porque o histórico não pôde ser analisado.", failure)
                return
            }
        }
        attachmentsDirectory.listFiles()
            ?.filter { file ->
                file.name.startsWith(PDF_FILE_PREFIX) && file.name.endsWith(PDF_FILE_SUFFIX) &&
                    file.name.removePrefix(PDF_FILE_PREFIX).removeSuffix(PDF_FILE_SUFFIX) !in referenced
            }
            ?.forEach(File::delete)
    }

    private fun ConversationSummary.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title ?: JSONObject.NULL)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("pinned", pinned)

    private fun JSONObject.toConversationSummary(): ConversationSummary = ConversationSummary(
        id = getString("id"),
        title = optionalString("title"),
        createdAt = optLong("createdAt", 0L),
        updatedAt = optLong("updatedAt", 0L),
        pinned = optBoolean("pinned", false),
    )

    private companion object {
        const val TAG = "AtlasChatHistory"
        const val MAX_PDF_BYTES = 32L * 1024 * 1024
        const val PDF_FILE_PREFIX = "pdf_"
        const val PDF_FILE_SUFFIX = ".enc"
        val PDF_KEY_REGEX = Regex("[0-9a-f]{64}")
        const val KEY_INDEX = "conversations_index"
        const val KEY_CURRENT_ID = "current_conversation_id"
        // Pre-multi-conversation storage key — read once for migration, then removed.
        const val KEY_LEGACY_MESSAGES = "messages"
    }
}
