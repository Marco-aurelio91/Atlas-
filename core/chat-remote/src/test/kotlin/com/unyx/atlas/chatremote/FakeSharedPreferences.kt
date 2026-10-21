package com.unyx.atlas.chatremote

import android.content.SharedPreferences

/** Same minimal in-memory fake as `core/chat-storage`'s and `core/chat-pairing`'s own copies —
 *  kept as its own copy here since those are `internal` to their modules, and this module needs a
 *  real [com.unyx.atlas.chatstorage.ChatIdentityStore]/[com.unyx.atlas.chatstorage.ChatConversationStore]
 *  to exercise the repository, not a mock of them. */
internal class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, String>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private class FakeEditor(private val owner: FakeSharedPreferences) : SharedPreferences.Editor {
        private val puts = mutableMapOf<String, String?>()
        private val removes = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { puts[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor = apply { removes += key }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            if (clear) owner.values.clear()
            removes.forEach { owner.values.remove(it) }
            puts.forEach { (key, value) -> if (value == null) owner.values.remove(key) else owner.values[key] = value }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
