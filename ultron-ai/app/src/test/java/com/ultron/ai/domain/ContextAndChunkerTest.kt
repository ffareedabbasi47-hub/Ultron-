package com.ultron.ai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAndChunkerTest {

    @Test fun `history is bounded by turns and starts with user`() {
        val turns = (1..10).flatMap {
            listOf(ChatTurn(ChatTurn.USER, "q$it"), ChatTurn(ChatTurn.ASSISTANT, "a$it"))
        }
        val out = ContextBuilder.bound(turns, maxTurns = 5, maxChars = 10_000)
        assertEquals(ChatTurn.USER, out.first().role)
        assertTrue(out.size <= 5)
        assertEquals("a10", out.last().text)
    }

    @Test fun `history is bounded by chars but keeps newest`() {
        val turns = listOf(
            ChatTurn(ChatTurn.USER, "x".repeat(500)),
            ChatTurn(ChatTurn.ASSISTANT, "y".repeat(500)),
            ChatTurn(ChatTurn.USER, "latest"),
        )
        val out = ContextBuilder.bound(turns, maxTurns = 10, maxChars = 100)
        assertEquals(listOf(ChatTurn(ChatTurn.USER, "latest")), out)
    }

    @Test fun `chunker emits sentences incrementally`() {
        val c = SentenceChunker(minChunkChars = 10)
        val out = ArrayList<String>()
        out += c.feed("Systems ready. Batao, kya ")
        out += c.feed("solve karna hai? Chalo shuru karte")
        c.flush()?.let { out += it }
        assertEquals(listOf("Systems ready.", "Batao, kya solve karna hai?", "Chalo shuru karte"), out)
    }

    @Test fun `chunker does not split decimals`() {
        val c = SentenceChunker(minChunkChars = 5)
        val out = c.feed("Pi is 3.14 roughly. ")
        assertEquals(listOf("Pi is 3.14 roughly."), out)
    }
}
