package com.dmwnezes.estante

import com.dmwnezes.estante.data.DEFAULT_SHELF
import com.dmwnezes.estante.data.Library
import com.dmwnezes.estante.data.LibraryState
import com.dmwnezes.estante.data.Playlist
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.data.formatTime
import com.dmwnezes.estante.data.titleFromFileName
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibraryTest {

    private fun lib() = Library(File(Files.createTempDirectory("estante").toFile(), "estante.json")) { 1000L }
    private fun v(ref: String, shelf: String = DEFAULT_SHELF) = Video(id = "id-$ref", source = Source.DRIVE, ref = ref, title = ref, shelf = shelf)

    @Test fun `nao repete o mesmo arquivo na estante`() {
        val l = lib()
        assertEquals(2, l.add(listOf(v("a"), v("b"))).size)
        assertEquals(1, l.add(listOf(v("a").copy(id = "outro"), v("c"))).size)
        assertEquals(listOf("a", "b", "c"), l.current.videos.map { it.ref })
    }

    @Test fun `tirar da estante tira das listas`() {
        val l = lib()
        l.add(listOf(v("a"), v("b")))
        val p = l.createPlaylist("Série", listOf("id-a", "id-b"))
        l.remove("id-a")
        assertEquals(listOf("id-b"), l.current.playlist(p.id)!!.videoIds)
        assertNull(l.current.video("id-a"))
    }

    @Test fun `reordenar lista`() {
        val l = lib()
        l.add(listOf(v("a"), v("b"), v("c")))
        val p = l.createPlaylist("L", listOf("id-a", "id-b", "id-c"))
        l.moveInPlaylist(p.id, 2, 0)
        assertEquals(listOf("id-c", "id-a", "id-b"), l.current.playlist(p.id)!!.videoIds)
        l.moveInPlaylist(p.id, 0, 5) // fora do limite: nada muda
        assertEquals(listOf("id-c", "id-a", "id-b"), l.current.playlist(p.id)!!.videoIds)
    }

    @Test fun `prateleira padrao vem primeiro`() {
        val s = LibraryState(listOf(v("a", "Séries"), v("b"), v("c", "Animação")))
        assertEquals(listOf(DEFAULT_SHELF, "Animação", "Séries"), s.shelves)
    }

    @Test fun `json ida e volta`() {
        val s = LibraryState(
            listOf(v("a").copy(cover = "/capa.jpg", positionMs = 5000, durationMs = 90000, finished = true), v("b").copy(source = Source.LOCAL)),
            listOf(Playlist("p", "Lista", listOf("id-a", "id-b"), 7)),
        )
        assertEquals(s, LibraryState.fromJson(JSONObject(s.toJson().toString())))
    }

    @Test fun `retomar de onde parou`() {
        val base = v("a")
        val mid = Resume.record(base, 600_000, 6_000_000, 1)
        assertEquals(600_000, Resume.startAt(mid))
        assertFalse(mid.finished)

        val start = Resume.record(base, 4_000, 6_000_000, 1)
        assertEquals(0, Resume.startAt(start))

        val end = Resume.record(mid, 5_800_000, 6_000_000, 2)
        assertTrue(end.finished)
        assertEquals(0, Resume.startAt(end))

        // Recomeçou um vídeo já assistido e passou do início: volta a ter ponto de retomada.
        val again = Resume.record(end, 120_000, 6_000_000, 3)
        assertEquals(120_000, Resume.startAt(again))
        assertFalse(again.finished)
    }

    @Test fun `continuar assistindo so com os comecados`() {
        val s = LibraryState(listOf(
            v("a").copy(positionMs = 60_000, durationMs = 100_000, watchedAt = 1),
            v("b"),
            v("c").copy(positionMs = 30_000, durationMs = 100_000, watchedAt = 5),
        ))
        assertEquals(listOf("c", "a"), s.continueWatching.map { it.ref })
    }

    @Test fun `titulo a partir do nome do arquivo`() {
        assertEquals("Meu filme 2019", titleFromFileName("Meu_filme.2019.mp4"))
        assertEquals("Aula 01", titleFromFileName("Aula 01.mkv"))
        assertEquals("semextensao", titleFromFileName("semextensao"))
    }

    @Test fun `formato de tempo`() {
        assertEquals("12:34", formatTime(754_000))
        assertEquals("1:23:45", formatTime(5_025_000))
    }
}
