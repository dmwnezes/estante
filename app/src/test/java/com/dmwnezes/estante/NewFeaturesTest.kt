package com.dmwnezes.estante

import com.dmwnezes.estante.data.DEFAULT_SHELF
import com.dmwnezes.estante.data.Episodes
import com.dmwnezes.estante.data.Importer
import com.dmwnezes.estante.data.Library
import com.dmwnezes.estante.data.LibraryState
import com.dmwnezes.estante.data.ShelfItem
import com.dmwnezes.estante.data.SortOrder
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Tmdb
import com.dmwnezes.estante.data.TmdbResult
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.data.sortedBy
import com.dmwnezes.estante.drive.DriveItem
import com.dmwnezes.estante.drive.DriveQuery
import com.dmwnezes.estante.player.CastProxy
import com.dmwnezes.estante.player.Subtitles
import com.dmwnezes.estante.ui.filterItems
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NewFeaturesTest {

    private fun lib() = Library(File(Files.createTempDirectory("estante").toFile(), "estante.json")) { 1000L }
    private fun v(ref: String, shelf: String = DEFAULT_SHELF) = Video(id = "id-$ref", source = Source.DRIVE, ref = ref, title = ref, shelf = shelf)

    @Test fun `numeros de episodio`() {
        assertEquals(Episodes.Number(1, 2), Episodes.parse("Serie.S01E02.1080p.mkv"))
        assertEquals(Episodes.Number(2, 10), Episodes.parse("Show 2x10 - Final.mp4"))
        assertEquals(Episodes.Number(3, 4), Episodes.parse("T3E04 - O retorno.mp4"))
        assertEquals(Episodes.Number(2, 7), Episodes.parse("Episódio 7.mp4", folderSeason = 2))
        assertEquals(Episodes.Number(1, 5), Episodes.parse("05 - Aula de acordes.mp4"))
        assertEquals(0, Episodes.parse("2019 Show ao vivo.mp4").episode)
        assertEquals(2, Episodes.seasonFromFolder("Temporada 2"))
        assertEquals(3, Episodes.seasonFromFolder("Season 03"))
    }

    @Test fun `proximo episodio`() {
        fun e(n: Int, finished: Boolean = false, pos: Long = 0, at: Long = 0) =
            Video("e$n", Source.DRIVE, "r$n", "Ep $n", season = 1, episode = n, finished = finished, positionMs = pos, durationMs = 100_000, watchedAt = at)
        assertEquals(0, Episodes.nextIndex(listOf(e(1), e(2), e(3))))
        assertEquals(2, Episodes.nextIndex(listOf(e(1, true, at = 1), e(2, true, at = 2), e(3))))
        // Começou o 3 depois de terminar o 1: continua no 3.
        assertEquals(2, Episodes.nextIndex(listOf(e(1, true, at = 1), e(2), e(3, pos = 40_000, at = 5))))
        // Tudo assistido: volta ao primeiro.
        assertEquals(0, Episodes.nextIndex(listOf(e(1, true, at = 1), e(2, true, at = 2))))
    }

    @Test fun `series aparecem como uma caixa so`() {
        val l = lib()
        l.add(listOf(v("solto")))
        val box = l.createBox("Minha série", DEFAULT_SHELF)
        l.add(listOf(v("e2").copy(boxId = box.id, season = 1, episode = 2), v("e1").copy(boxId = box.id, season = 1, episode = 1)))
        val items = l.current.shelfItems
        assertEquals(2, items.size)
        val series = items.filterIsInstance<ShelfItem.Series>().single()
        assertEquals(listOf("e1", "e2"), series.episodes.map { it.ref })

        l.deleteBox(box.id, keepEpisodes = true)
        assertEquals(3, l.current.shelfItems.size)
        assertTrue(l.current.videos.all { it.boxId == null })
    }

    @Test fun `arrastar muda a ordem e a prateleira`() {
        val l = lib()
        l.add(listOf(v("a"), v("b"), v("c"), v("x", "Outra")))
        fun order(shelf: String) = l.current.shelfItems.filter { it.shelf == shelf }.sortedBy(SortOrder.MANUAL).map { it.title }
        assertEquals(listOf("a", "b", "c"), order(DEFAULT_SHELF))
        l.move("id-c", DEFAULT_SHELF, "id-a")
        assertEquals(listOf("c", "a", "b"), order(DEFAULT_SHELF))
        l.move("id-a", "Outra", null)
        assertEquals(listOf("c", "b"), order(DEFAULT_SHELF))
        assertEquals(listOf("x", "a"), order("Outra"))
    }

    @Test fun `fixar a ordem por titulo antes de arrastar`() {
        val l = lib()
        l.add(listOf(v("c"), v("a"), v("b")))
        l.freezeOrder(SortOrder.TITLE)
        assertEquals(listOf("a", "b", "c"), l.current.shelfItems.sortedBy(SortOrder.MANUAL).map { it.title })
    }

    @Test fun `json com series e pastas`() {
        val l = lib()
        val box = l.createBox("S", "Séries", folderId = "f1")
        l.add(listOf(v("e").copy(boxId = box.id, season = 2, episode = 3, genres = listOf("Drama"), synopsis = "x")))
        l.addSync(com.dmwnezes.estante.data.SyncFolder("f1", "Pasta", "Séries", box.id))
        val s = l.current
        assertEquals(s, LibraryState.fromJson(JSONObject(s.toJson().toString())))
    }

    @Test fun `busca na estante ignora acento`() {
        val items = listOf(ShelfItem.Single(v("Viagem a Jeri")), ShelfItem.Single(v("Ação total")))
        assertEquals(1, filterItems(items, "acao").size)
        assertEquals(2, filterItems(items, "").size)
    }

    @Test fun `limpa o nome para buscar no tmdb`() {
        assertEquals("O Grande Filme" to "2019", Tmdb.cleanQuery("O.Grande.Filme.2019.1080p.BluRay.x264"))
        assertEquals("Breaking Bad", Tmdb.cleanQuery("Breaking Bad S01E01 720p").first)
    }

    @Test fun `le resultados do tmdb`() {
        val json = JSONObject(
            """{"results":[
              {"id":1,"media_type":"movie","title":"Cidade de Deus","release_date":"2002-08-30","poster_path":"/a.jpg","overview":"Favela","genre_ids":[18,80]},
              {"id":2,"media_type":"tv","name":"Cidade dos Homens","first_air_date":"2002-10-15","poster_path":null,"genre_ids":[]},
              {"id":3,"media_type":"person","name":"Fulano"}
            ]}"""
        )
        val r = Tmdb.parseResults(json)
        assertEquals(2, r.size)
        assertEquals("2002", r[0].year)
        assertEquals("https://image.tmdb.org/t/p/w500/a.jpg", r[0].posterUrl)
        assertTrue(r[1].isSeries)
        assertNull(r[1].posterUrl)
    }

    @Test fun `so aceita capa automatica quando o titulo bate`() {
        val r = TmdbResult(1, false, "Cidade de Deus", "2002", null, "/a.jpg", emptyList())
        assertTrue(Importer.goodMatch("Cidade.de.Deus.2002.1080p", r))
        assertFalse(Importer.goodMatch("Vídeo da festa", r))
        assertFalse(Importer.goodMatch("Deus", r))
    }

    @Test fun `legenda em latin1 vira texto certo`() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,500\nAção e coração\n".toByteArray(charset("windows-1252"))
        assertTrue(Subtitles.decode(bytes).contains("Ação e coração"))
        assertTrue(Subtitles.decode("﻿Olá".toByteArray()).startsWith("Olá"))
        val vtt = Subtitles.srtToVtt("1\r\n00:00:01,000 --> 00:00:02,500\r\nOi\r\n")
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:01.000 --> 00:00:02.500"))
    }

    @Test fun `escolhe a legenda certa da pasta`() {
        val subs = listOf(DriveItem("1", "Filme.en.srt", "text/plain"), DriveItem("2", "Filme.pt-BR.srt", "text/plain"), DriveItem("3", "Outro.srt", "text/plain"))
        assertEquals("2", DriveQuery.matchSubtitle("Filme.mp4", subs, 2)?.id)
        assertNull(DriveQuery.matchSubtitle("Nada.mp4", subs, 3))
        assertEquals("9", DriveQuery.matchSubtitle("Video.mp4", listOf(DriveItem("9", "legenda.srt", "text/plain")), 1)?.id)
    }

    @Test fun `faixa de bytes para a tv`() {
        assertEquals(0L to 999L, CastProxy.parseRange(null, 1000))
        assertEquals(100L to 999L, CastProxy.parseRange("bytes=100-", 1000))
        assertEquals(100L to 199L, CastProxy.parseRange("bytes=100-199", 1000))
        assertEquals(900L to 999L, CastProxy.parseRange("bytes=-100", 1000))
        assertEquals("video/x-matroska", CastProxy.mimeFor(v("a").copy(fileName = "a.MKV")))
    }
}
