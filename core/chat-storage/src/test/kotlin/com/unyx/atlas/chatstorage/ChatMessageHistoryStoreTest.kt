package com.unyx.atlas.chatstorage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageHistoryStoreTest {

    @Test
    fun `loadAll for an unknown conversation returns an empty list instead of throwing`() {
        val store = ChatMessageHistoryStore(FakeSharedPreferences())
        assertTrue(store.loadAll("never-heard-of-it").isEmpty())
    }

    @Test
    fun `append then loadAll round trips records in chronological order`() {
        val store = ChatMessageHistoryStore(FakeSharedPreferences())
        store.append("conv-1", ChatMessageRecord(0L, ChatMessageDirection.SENT, "oi", 1_000_000L))
        store.append("conv-1", ChatMessageRecord(0L, ChatMessageDirection.RECEIVED, "oi de volta", 1_000_005L))
        store.append("conv-1", ChatMessageRecord(1L, ChatMessageDirection.SENT, "tudo bem?", 1_000_010L))

        val history = store.loadAll("conv-1")
        assertEquals(3, history.size)
        assertEquals("oi", history[0].text)
        assertEquals("oi de volta", history[1].text)
        assertEquals("tudo bem?", history[2].text)
    }

    @Test
    fun `append is idempotent per direction and sequence`() {
        val store = ChatMessageHistoryStore(FakeSharedPreferences())
        val record = ChatMessageRecord(0L, ChatMessageDirection.SENT, "mensagem unica", 1_000_000L)
        store.append("conv-1", record)
        store.append("conv-1", record) // a retry re-appending the exact same message

        assertEquals(1, store.loadAll("conv-1").size)
    }

    @Test
    fun `sent and received sequences are independent, not shared`() {
        val store = ChatMessageHistoryStore(FakeSharedPreferences())
        // Same sequence number 0, different directions — both must be kept, not deduplicated
        // against each other, since the two chains number independently.
        store.append("conv-1", ChatMessageRecord(0L, ChatMessageDirection.SENT, "de mim", 1_000_000L))
        store.append("conv-1", ChatMessageRecord(0L, ChatMessageDirection.RECEIVED, "do outro lado", 1_000_001L))

        assertEquals(2, store.loadAll("conv-1").size)
    }

    @Test
    fun `lastMessage returns the most recent record, or null when there is none`() {
        val store = ChatMessageHistoryStore(FakeSharedPreferences())
        assertNull(store.lastMessage("conv-1"))

        store.append("conv-1", ChatMessageRecord(0L, ChatMessageDirection.SENT, "primeira", 1_000_000L))
        store.append("conv-1", ChatMessageRecord(1L, ChatMessageDirection.SENT, "ultima", 1_000_050L))

        assertEquals("ultima", store.lastMessage("conv-1")?.text)
    }

    @Test
    fun `deleteAll clears only the targeted conversation's history`() {
        val store = ChatMessageHistoryStore(FakeSharedPreferences())
        store.append("conv-1", ChatMessageRecord(0L, ChatMessageDirection.SENT, "a", 1_000_000L))
        store.append("conv-2", ChatMessageRecord(0L, ChatMessageDirection.SENT, "b", 1_000_000L))

        store.deleteAll("conv-1")

        assertTrue(store.loadAll("conv-1").isEmpty())
        assertEquals(1, store.loadAll("conv-2").size)
    }

    @Test
    fun `deleteOne removes only the targeted direction and sequence`() {
        val store = ChatMessageHistoryStore(FakeSharedPreferences())
        store.append("conv-1", ChatMessageRecord(0L, ChatMessageDirection.SENT, "a", 1_000_000L))
        store.append("conv-1", ChatMessageRecord(0L, ChatMessageDirection.RECEIVED, "b", 1_000_001L))
        store.append("conv-1", ChatMessageRecord(1L, ChatMessageDirection.SENT, "c", 1_000_002L))

        store.deleteOne("conv-1", ChatMessageDirection.SENT, 0L)

        val remaining = store.loadAll("conv-1")
        assertEquals(2, remaining.size)
        assertTrue(remaining.none { it.direction == ChatMessageDirection.SENT && it.sequence == 0L })
        // The RECEIVED message with the SAME sequence number is untouched — the two chains are
        // independent, deleteOne must not confuse them.
        assertTrue(remaining.any { it.direction == ChatMessageDirection.RECEIVED && it.sequence == 0L })
    }

    @Test
    fun `deleteOne for a message that is already gone is a harmless no-op`() {
        val store = ChatMessageHistoryStore(FakeSharedPreferences())
        store.append("conv-1", ChatMessageRecord(0L, ChatMessageDirection.SENT, "a", 1_000_000L))

        store.deleteOne("conv-1", ChatMessageDirection.SENT, 99L)

        assertEquals(1, store.loadAll("conv-1").size)
    }
}
