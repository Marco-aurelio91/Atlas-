package com.unyx.atlas.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Opens encrypted preferences and imports a legacy plain store only after encryption is available.
 * If Android Keystore cannot be used, values stay in process memory and are never written as
 * plaintext. In that case the caller can continue for this process, but data will not survive an
 * app restart.
 */
class AtlasSecurePreferences private constructor(
    val preferences: SharedPreferences,
    val persistent: Boolean,
) {
    companion object {
        private const val TAG = "AtlasSecurity"
        private val processOnlyStores = ConcurrentHashMap<String, SharedPreferences>()

        fun open(
            context: Context,
            secureName: String,
            legacyPlainName: String? = null,
        ): AtlasSecurePreferences {
            val appContext = context.applicationContext
            val encrypted = runCatching { createEncryptedPrefs(appContext, secureName) }
                // A failure here is most often a mismatch between the ciphertext already on disk
                // and the Keystore key currently available to decrypt it (seen live as
                // AEADBadTagException from a flaky Keymaster/Keystore2 implementation on some
                // devices/Android builds) — not necessarily "this device can never do this again".
                // Wipe the stale file for this one store and retry once before assuming the worst;
                // this self-heals the common case instead of permanently downgrading every launch
                // to a non-persistent, in-memory-only store over what was often a transient HAL hiccup.
                .recoverCatching { firstFailure ->
                    Log.w(
                        TAG,
                        "Falha ao abrir '$secureName' (${firstFailure.javaClass.simpleName}); limpando o arquivo e tentando de novo.",
                    )
                    appContext.deleteSharedPreferences(secureName)
                    createEncryptedPrefs(appContext, secureName)
                }
                // Still failing: the shared Keystore master key itself (not just this one file) is
                // likely the problem. Every AtlasSecurePreferences store on this device shares that
                // same key, so regenerating it affects all of them — acceptable here since each
                // store already treats its own ciphertext as disposable (the account-key setup and
                // login flows already handle "start over"); the alternative is silently losing
                // persistence forever on this device.
                .recoverCatching { secondFailure ->
                    Log.w(
                        TAG,
                        "'$secureName' ainda falhou (${secondFailure.javaClass.simpleName}); regenerando a chave mestra do Keystore.",
                    )
                    runCatching {
                        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                            .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    }
                    appContext.deleteSharedPreferences(secureName)
                    createEncryptedPrefs(appContext, secureName)
                }
                .getOrElse { failure ->
                    Log.w(TAG, "Keystore indisponível; usando preferências somente em memória (${failure.javaClass.simpleName}).")
                    val key = "${appContext.packageName}:$secureName"
                    val memoryStore = processOnlyStores.getOrPut(key) { ProcessOnlySharedPreferences() }
                    if (!legacyPlainName.isNullOrBlank()) {
                        runCatching { migrateLegacyStore(appContext, memoryStore, legacyPlainName) }
                            .onFailure { Log.w(TAG, "Não foi possível remover os dados antigos sem criptografia.") }
                    }
                    return AtlasSecurePreferences(
                        memoryStore,
                        persistent = false,
                    )
                }

            if (!legacyPlainName.isNullOrBlank()) {
                runCatching { migrateLegacyStore(appContext, encrypted, legacyPlainName) }
                    .onFailure { Log.w(TAG, "Não foi possível concluir a migração segura de preferências antigas.") }
            }
            return AtlasSecurePreferences(encrypted, persistent = true)
        }

        private fun createEncryptedPrefs(context: Context, secureName: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                secureName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun migrateLegacyStore(context: Context, target: SharedPreferences, legacyName: String) {
            val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            val entries = legacy.all
            if (entries.isEmpty()) return

            val editor = target.edit()
            entries.forEach { (key, value) ->
                if (target.contains(key)) return@forEach
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
                }
            }
            if (editor.commit() && !legacy.edit().clear().commit()) {
                Log.w(TAG, "A migração terminou, mas os dados antigos sem criptografia não foram removidos.")
            }
        }
    }
}

/** A process-only implementation used solely when the device Keystore is unavailable. */
private class ProcessOnlySharedPreferences : SharedPreferences {
    private val lock = Any()
    private val values = mutableMapOf<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = synchronized(lock) {
        val copy = mutableMapOf<String, Any>()
        values.forEach { (key, value) -> copy[key] = copyPreferenceValue(value)!! }
        copy
    }

    override fun getString(key: String, defValue: String?): String? = synchronized(lock) {
        values[key] as? String ?: defValue
    }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = synchronized(lock) {
        @Suppress("UNCHECKED_CAST")
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()
    }

    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) { values[key] as? Int ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) { values[key] as? Long ?: defValue }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) { values[key] as? Float ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) { values[key] as? Boolean ?: defValue }
    override fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }
    override fun edit(): SharedPreferences.Editor = ProcessOnlyEditor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)
    }

    private fun applyChanges(clear: Boolean, changes: Map<String, Any?>) {
        val changed = linkedSetOf<String>()
        synchronized(lock) {
            if (clear) {
                changed += values.keys
                values.clear()
            }
            changes.forEach { (key, value) ->
                when {
                    value === RemovedPreferenceValue -> if (values.remove(key) != null) changed += key
                    value != null -> {
                        values[key] = copyPreferenceValue(value)!!
                        changed += key
                    }
                }
            }
        }
        changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this, key) } }
    }

    private class ProcessOnlyEditor(private val owner: ProcessOnlySharedPreferences) : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toMutableSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor = put(key, RemovedPreferenceValue)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean {
            owner.applyChanges(clear, changes)
            return true
        }
        override fun apply() { commit() }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply {
            changes[key] = value ?: RemovedPreferenceValue
        }
    }
}

private object RemovedPreferenceValue

private fun copyPreferenceValue(value: Any?): Any? = when (value) {
    is Set<*> -> value.toMutableSet()
    else -> value
}
