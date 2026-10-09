package com.dmwnezes.estante

import com.dmwnezes.estante.party.DbEvent
import com.dmwnezes.estante.party.PartyDb
import com.dmwnezes.estante.party.PartySync
import com.dmwnezes.estante.party.Person
import com.dmwnezes.estante.party.PlayState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PartyTest {

    @Test fun `le eventos do stream do firebase`() {
        val e = PartyDb.parseEvent("put", """{"path":"/chat/-a1","data":{"text":"oi"}}""")!!
        assertEquals("/chat/-a1", e.path)
        assertNull(PartyDb.parseEvent("keep-alive", "null"))
    }

    @Test fun `monta a copia local da sala`() {
        var tree: Any? = null
        tree = PartyDb.apply(tree, DbEvent("put", "/", JSONObject("""{"host":"a","state":{"playing":false,"position":0}}""")))
        tree = PartyDb.apply(tree, DbEvent("put", "/chat/-x", JSONObject("""{"text":"oi"}""")))
        tree = PartyDb.apply(tree, DbEvent("patch", "/state", JSONObject("""{"playing":true,"position":12.5}""")))
        tree = PartyDb.apply(tree, DbEvent("put", "/people/b", JSONObject("""{"name":"Mandis"}""")))
        tree = PartyDb.apply(tree, DbEvent("put", "/people/b", JSONObject.NULL))
        val t = tree as JSONObject
        assertEquals("oi", t.getJSONObject("chat").getJSONObject("-x").getString("text"))
        assertTrue(t.getJSONObject("state").getBoolean("playing"))
        assertEquals(12.5, t.getJSONObject("state").getDouble("position"), 0.0)
        assertFalse(t.getJSONObject("people").has("b"))
        // Sala apagada inteira
        assertNull(PartyDb.apply(t, DbEvent("put", "/", JSONObject.NULL)))
    }

    @Test fun `posicao esperada e ajuste`() {
        val s = PlayState(true, 100.0, at = 10_000, by = "x")
        assertEquals(105.0, PartySync.expected(s, 15_000), 0.001)
        assertEquals(100.0, PartySync.expected(s.copy(playing = false), 15_000), 0.001)
        assertFalse(PartySync.needsSeek(104.4, 105.0))
        assertTrue(PartySync.needsSeek(103.5, 105.0))
        assertEquals(1_000L, PartySync.offset(sentAt = 100, receivedAt = 300, serverTs = 1_200))
        assertTrue(PartySync.online(Person("a", "A", 10_000, "web"), 30_000))
        assertFalse(PartySync.online(Person("a", "A", 10_000, "web"), 40_000))
    }

    @Test fun `codigo link e endereco do banco`() {
        val code = PartySync.newCode(Random(1))
        assertEquals(6, code.length)
        assertTrue(code.none { it in "0O1IL" })
        assertEquals("https://x-default-rtdb.firebaseio.com", PartySync.normalizeDbUrl(" https://x-default-rtdb.firebaseio.com/ "))
        assertEquals("https://y.europe-west1.firebasedatabase.app", PartySync.normalizeDbUrl("y.europe-west1.firebasedatabase.app"))
        assertNull(PartySync.normalizeDbUrl("https://google.com"))
        assertEquals("https://dmwnezes.github.io/estante/sala/#ABC123@x.firebaseio.com", PartySync.link("ABC123", "https://x.firebaseio.com"))
    }

    @Test fun `formatos que o iphone toca`() {
        assertTrue(PartySync.iphoneFriendly("Filme.MP4", null))
        assertTrue(PartySync.iphoneFriendly("Filme.mkv", null))
        assertFalse(PartySync.iphoneFriendly("Filme.avi", null))
        assertEquals("video/x-matroska", PartySync.roomMime("A.MKV"))
        assertEquals("video/mp4", PartySync.roomMime(null))
        assertTrue(PartySync.iphoneFriendly(null, "video/mp4"))
    }

    @Test fun `so aceita foto de verdade no chat`() {
        assertTrue(com.dmwnezes.estante.party.ChatImages.isValid("data:image/jpeg;base64,/9j/4AAQ"))
        assertFalse(com.dmwnezes.estante.party.ChatImages.isValid("javascript:alert(1)"))
        assertFalse(com.dmwnezes.estante.party.ChatImages.isValid("https://exemplo.com/a.jpg"))
        assertFalse(com.dmwnezes.estante.party.ChatImages.isValid(null))
    }
}
