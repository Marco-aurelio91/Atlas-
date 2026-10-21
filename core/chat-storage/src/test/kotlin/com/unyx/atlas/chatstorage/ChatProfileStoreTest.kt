package com.unyx.atlas.chatstorage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatProfileStoreTest {

    @Test
    fun `load with nothing saved yet returns null instead of throwing`() {
        val store = ChatProfileStore(FakeSharedPreferences())
        assertNull(store.load())
    }

    @Test
    fun `save then load round trips the exact same profile`() {
        val store = ChatProfileStore(FakeSharedPreferences())
        val profile = ChatProfile(displayName = "Marco Aurélio", statusMessage = "Disponível")
        store.save(profile)
        assertEquals(profile, store.load())
    }

    @Test
    fun `save overwrites a previous profile instead of merging`() {
        val store = ChatProfileStore(FakeSharedPreferences())
        store.save(ChatProfile("Marco", "Ocupado"))
        store.save(ChatProfile("Marco Aurélio", ""))

        val loaded = store.load()
        assertEquals("Marco Aurélio", loaded?.displayName)
        assertEquals("", loaded?.statusMessage)
    }

    @Test
    fun `an empty status message round trips as empty, not null`() {
        val store = ChatProfileStore(FakeSharedPreferences())
        store.save(ChatProfile(displayName = "Alice", statusMessage = ""))
        assertEquals("", store.load()?.statusMessage)
    }

    @Test
    fun `bio and interesses round trip exactly`() {
        val store = ChatProfileStore(FakeSharedPreferences())
        val profile = ChatProfile(
            displayName = "Marco Aurélio",
            statusMessage = "Disponível",
            bio = "Gosto de café e trilhas.",
            interesses = listOf("café", "trilhas", "fotografia"),
        )
        store.save(profile)
        assertEquals(profile, store.load())
    }

    @Test
    fun `a profile saved before bio and interesses existed loads with empty defaults instead of failing`() {
        val backing = FakeSharedPreferences()
        val legacyJson = JSONObject().put("display_name", "Alice").put("status_message", "Ocupada")
        backing.edit().putString("profile", legacyJson.toString()).apply()

        val loaded = ChatProfileStore(backing).load()
        assertEquals("Alice", loaded?.displayName)
        assertEquals("", loaded?.bio)
        assertEquals(emptyList<String>(), loaded?.interesses)
    }
}
