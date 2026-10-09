package com.dmwnezes.estante

import com.dmwnezes.estante.data.DEFAULT_SHELF
import com.dmwnezes.estante.data.Library
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.party.PartySync
import com.dmwnezes.estante.party.SessionDiary
import com.dmwnezes.estante.party.ChatMessage
import com.dmwnezes.estante.player.SeekThumbs
import com.dmwnezes.estante.ui.ShelfLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Batch3Test {

    private fun lib() = Library(File(Files.createTempDirectory("estante").toFile(), "estante.json")) { 1000L }
    private fun v(ref: String) = Video(id = "id-$ref", source = Source.DRIVE, ref = ref, title = ref, shelf = DEFAULT_SHELF)

    @Test fun `favoritos e pilha para ver`() {
        val l = lib()
        l.add(listOf(v("a"), v("b")))
        l.setFavorite("id-a", true)
        l.setToWatch("id-b", true)
        val items = l.current.shelfItems
        assertEquals(listOf("a"), items.filter { it.favorite }.map { it.title })
        assertEquals(listOf("b"), items.filter { it.toWatch }.map { it.title })
        // Terminou: sai da pilha sozinho.
        l.savePosition("id-b", 99_000, 100_000)
        assertFalse(l.current.video("id-b")!!.toWatch)
    }

    @Test fun `serie sai da pilha quando todos os episodios terminam`() {
        val l = lib()
        val box = l.createBox("S", DEFAULT_SHELF)
        l.add(listOf(v("e1").copy(boxId = box.id), v("e2").copy(boxId = box.id)))
        l.setToWatch(box.id, true)
        l.savePosition("id-e1", 99_000, 100_000)
        assertTrue(l.current.box(box.id)!!.toWatch)
        l.savePosition("id-e2", 99_000, 100_000)
        assertFalse(l.current.box(box.id)!!.toWatch)
    }

    @Test fun `velocidade lembrada por video e por serie`() {
        val l = lib()
        val box = l.createBox("Aulas", DEFAULT_SHELF)
        l.add(listOf(v("filme"), v("aula1").copy(boxId = box.id), v("aula2").copy(boxId = box.id)))
        l.saveSpeed("id-aula1", 1.5f)
        assertEquals(1.5f, l.speedFor("id-aula2"))
        assertEquals(1f, l.speedFor("id-filme"))
        l.saveSpeed("id-filme", 1.25f)
        assertEquals(1.25f, l.speedFor("id-filme"))
    }

    @Test fun `continua 10 s antes`() {
        val x = v("a").copy(positionMs = 60_000, durationMs = 100_000)
        assertEquals(50_000L, Resume.resumeFrom(x))
        assertEquals(5_000L, Resume.resumeFrom(x.copy(positionMs = 15_000)))
        assertEquals(0L, Resume.resumeFrom(v("b")))
    }

    @Test fun `ordem das miniaturas preenche o video`() {
        val o = SeekThumbs.order(48)
        assertEquals(48, o.size)
        assertEquals(48, o.toSet().size)
        assertEquals(0, o[0])
        assertEquals(24, o[1])
        assertEquals(10, SeekThumbs.nearest(setOf(0, 10, 47), 0.2f))
        assertNull(SeekThumbs.nearest(emptySet(), 0.5f))
    }

    @Test fun `digitando e luz da estante`() {
        assertEquals("Mandis está digitando…", PartySync.typingLabel(listOf("Mandis")))
        assertEquals("Ana e Bia estão digitando…", PartySync.typingLabel(listOf("Ana", "Bia")))
        assertNull(PartySync.typingLabel(emptyList()))
        assertTrue(PartySync.typing(10_000, 13_000))
        assertFalse(PartySync.typing(10_000, 15_000))
        assertTrue(ShelfLight.isNight(21))
        assertTrue(ShelfLight.isNight(3))
        assertFalse(ShelfLight.isNight(14))
    }

    @Test fun `diario guarda conversa e fotos`() {
        val dir = Files.createTempDirectory("diario").toFile()
        val d = SessionDiary(dir)
        // Sozinho e sem conversa: não guarda.
        assertNull(d.save("s0", "v", "Filme", null, 0, 60_000, listOf("Daniel"), emptyList(), "me"))
        val img = "data:image/jpeg;base64," + android.util.Base64.encodeToString(byteArrayOf(1, 2, 3), android.util.Base64.NO_WRAP)
        val e = d.save(
            "s1", "v", "Filme", null, 0, 120_000, listOf("Daniel", "Mandis"),
            listOf(ChatMessage("-a", "Mandis", "oi", 10, "x"), ChatMessage("-b", "Daniel", "", 20, "me", img)), "me",
        )!!
        assertEquals(1, e.photos.size)
        assertTrue(File(e.photos[0]).exists())
        assertTrue(e.messages[1].mine)
        // Lê de novo do arquivo.
        assertEquals(1, SessionDiary(dir).entries.value.size)
        d.delete("s1")
        assertTrue(SessionDiary(dir).entries.value.isEmpty())
    }
}
