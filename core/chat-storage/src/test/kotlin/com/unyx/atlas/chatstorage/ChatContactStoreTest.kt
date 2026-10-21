package com.unyx.atlas.chatstorage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContactStoreTest {

    @Test
    fun `load for an unknown conversation returns null instead of throwing`() {
        val store = ChatContactStore(FakeSharedPreferences())
        assertNull(store.load("never-heard-of-it"))
    }

    @Test
    fun `save then load round trips the exact same contact`() {
        val store = ChatContactStore(FakeSharedPreferences())
        val contact = ChatContact(
            conversationId = "conv-1", displayName = "Alice", remoteUserId = "user-alice",
            remoteDeviceId = "device-alice", pairedAtEpochSeconds = 1_000_000L,
            verifiedFingerprint = "12345 67890",
        )
        store.save(contact)
        assertEquals(contact, store.load("conv-1"))
    }

    @Test
    fun `listAll returns every saved contact`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))
        store.save(ChatContact("conv-2", "Bob", "user-bob", "device-bob", 1_000_100L, "fp-bob"))

        val all = store.listAll().sortedBy { it.conversationId }
        assertEquals(2, all.size)
        assertEquals("Alice", all[0].displayName)
        assertEquals("Bob", all[1].displayName)
    }

    @Test
    fun `delete removes only the targeted conversation's contact`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))
        store.save(ChatContact("conv-2", "Bob", "user-bob", "device-bob", 1_000_100L, "fp-bob"))

        store.delete("conv-1")

        assertNull(store.load("conv-1"))
        assertEquals("Bob", store.load("conv-2")?.displayName)
        assertTrue(store.listAll().size == 1)
    }

    @Test
    fun `save overwrites a previous contact for the same conversation`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))
        store.save(ChatContact("conv-1", "Alice (renomeada)", "user-alice", "device-alice-2", 1_000_200L, "fp-alice"))

        val loaded = store.load("conv-1")
        assertEquals("Alice (renomeada)", loaded?.displayName)
        assertEquals("device-alice-2", loaded?.remoteDeviceId)
        assertEquals(1, store.listAll().size)
    }

    @Test
    fun `a contact persisted before verifiedFingerprint existed loads with an empty one instead of failing`() {
        val backing = FakeSharedPreferences()
        val legacyJson = JSONObject()
            .put("display_name", "Alice")
            .put("remote_user_id", "user-alice")
            .put("remote_device_id", "device-alice")
            .put("paired_at", 1_000_000L)
        backing.edit().putString("contact:conv-1", legacyJson.toString()).apply()

        val loaded = ChatContactStore(backing).load("conv-1")
        assertEquals("", loaded?.verifiedFingerprint)
        assertEquals("Alice", loaded?.displayName)
    }

    @Test
    fun `receivedDisplayName and receivedStatusMessage default to null until a profile update arrives`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))

        val loaded = store.load("conv-1")
        assertNull(loaded?.receivedDisplayName)
        assertNull(loaded?.receivedStatusMessage)
    }

    @Test
    fun `applyReceivedProfile sets received fields without touching the local display name`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice (do trabalho)", "user-alice", "device-alice", 1_000_000L, "fp-alice"))

        store.applyReceivedProfile("conv-1", displayName = "Alice Souza", statusMessage = "Disponível")

        val loaded = store.load("conv-1")
        assertEquals("Alice Souza", loaded?.receivedDisplayName)
        assertEquals("Disponível", loaded?.receivedStatusMessage)
        // The label THIS device chose is untouched by what the other side self-reports.
        assertEquals("Alice (do trabalho)", loaded?.displayName)
    }

    @Test
    fun `applyReceivedProfile for an unknown conversation is a no-op, not an error`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.applyReceivedProfile("never-paired", displayName = "X", statusMessage = "Y")
        assertNull(store.load("never-paired"))
    }

    @Test
    fun `a later applyReceivedProfile call overwrites the earlier received profile`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))

        store.applyReceivedProfile("conv-1", "Alice", "Ocupada")
        store.applyReceivedProfile("conv-1", "Alice", "Disponível de novo")

        assertEquals("Disponível de novo", store.load("conv-1")?.receivedStatusMessage)
    }

    @Test
    fun `applyReceivedProfile carries bio and interesses through`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))

        store.applyReceivedProfile(
            "conv-1", displayName = "Alice Souza", statusMessage = "Disponível",
            bio = "Adora trilhas.", interesses = listOf("trilhas", "café"),
        )

        val loaded = store.load("conv-1")
        assertEquals("Adora trilhas.", loaded?.receivedBio)
        assertEquals(listOf("trilhas", "café"), loaded?.receivedInteresses)
    }

    @Test
    fun `receivedBio and receivedInteresses default to null and empty until a profile update arrives`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))

        val loaded = store.load("conv-1")
        assertNull(loaded?.receivedBio)
        assertEquals(emptyList<String>(), loaded?.receivedInteresses)
    }

    @Test
    fun `isBlocked defaults to false for a newly saved contact`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))
        assertEquals(false, store.load("conv-1")?.isBlocked)
    }

    @Test
    fun `setBlocked toggles the preference and leaves everything else untouched`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))

        store.setBlocked("conv-1", true)
        var loaded = store.load("conv-1")
        assertEquals(true, loaded?.isBlocked)
        assertEquals("Alice", loaded?.displayName)

        store.setBlocked("conv-1", false)
        loaded = store.load("conv-1")
        assertEquals(false, loaded?.isBlocked)
    }

    @Test
    fun `setFavorite toggles the local favorite flag`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))

        store.setFavorite("conv-1", true)
        assertEquals(true, store.load("conv-1")?.isFavorite)

        store.setFavorite("conv-1", false)
        assertEquals(false, store.load("conv-1")?.isFavorite)
    }

    @Test
    fun `setBlocked for an unknown conversation is a no-op, not an error`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.setBlocked("never-paired", true)
        assertNull(store.load("never-paired"))
    }

    @Test
    fun `a contact persisted before isBlocked existed loads as not blocked instead of failing`() {
        val backing = FakeSharedPreferences()
        val legacyJson = JSONObject()
            .put("display_name", "Alice")
            .put("remote_user_id", "user-alice")
            .put("remote_device_id", "device-alice")
            .put("paired_at", 1_000_000L)
            .put("verified_fingerprint", "fp")
        backing.edit().putString("contact:conv-1", legacyJson.toString()).apply()

        assertEquals(false, ChatContactStore(backing).load("conv-1")?.isBlocked)
    }

    @Test
    fun `mutedUntilEpochSeconds defaults to null and isMuted is false`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))

        val loaded = store.load("conv-1")
        assertNull(loaded?.mutedUntilEpochSeconds)
        assertEquals(false, loaded?.isMuted(nowEpochSeconds = 2_000_000L))
    }

    @Test
    fun `setMutedUntil persists an expiry and isMuted respects it`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))

        store.setMutedUntil("conv-1", 2_000_000L)

        val loaded = store.load("conv-1")!!
        assertEquals(2_000_000L, loaded.mutedUntilEpochSeconds)
        assertTrue(loaded.isMuted(nowEpochSeconds = 1_500_000L))
        // The expiry has already passed — no longer muted, even though the field is still set.
        assertEquals(false, loaded.isMuted(nowEpochSeconds = 2_500_000L))
    }

    @Test
    fun `setMutedUntil with null un-silences`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.save(ChatContact("conv-1", "Alice", "user-alice", "device-alice", 1_000_000L, "fp-alice"))
        store.setMutedUntil("conv-1", Long.MAX_VALUE) // "até reativar"
        assertTrue(store.load("conv-1")!!.isMuted(nowEpochSeconds = 9_999_999_999L))

        store.setMutedUntil("conv-1", null)

        val loaded = store.load("conv-1")!!
        assertNull(loaded.mutedUntilEpochSeconds)
        assertEquals(false, loaded.isMuted(nowEpochSeconds = 9_999_999_999L))
    }

    @Test
    fun `setMutedUntil for an unknown conversation is a no-op, not an error`() {
        val store = ChatContactStore(FakeSharedPreferences())
        store.setMutedUntil("never-paired", Long.MAX_VALUE)
        assertNull(store.load("never-paired"))
    }
}
