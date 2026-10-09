package com.dmwnezes.estante

import com.dmwnezes.estante.drive.DriveClient
import com.dmwnezes.estante.drive.DriveQuery
import com.dmwnezes.estante.update.Updater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAndUpdateTest {

    @Test fun `busca escapa aspas`() {
        assertEquals("name contains 'Dona d\\'Água' and trashed = false and mimeType contains 'video/'", DriveQuery.search(" Dona d'Água "))
        assertTrue(DriveQuery.children("abc").startsWith("'abc' in parents"))
    }

    @Test fun `le a resposta do drive`() {
        val json = JSONObject(
            """{"files":[
                {"id":"1","name":"Filmes","mimeType":"application/vnd.google-apps.folder"},
                {"id":"2","name":"a.mp4","mimeType":"video/mp4","size":"1500","thumbnailLink":"https://x/=s220","videoMediaMetadata":{"durationMillis":"90000"}}
            ]}"""
        )
        val items = DriveQuery.parseItems(json)
        assertTrue(items[0].isFolder)
        assertEquals(90_000L, items[1].durationMs)
        assertEquals(1500L, items[1].sizeBytes)
        assertEquals("https://x/=s220", items[1].thumbnail)
    }

    @Test fun `endereco do streaming`() {
        assertEquals("https://www.googleapis.com/drive/v3/files/XYZ?alt=media&supportsAllDrives=true", DriveClient.streamUrl("XYZ"))
    }

    @Test fun `numero da versao pela tag`() {
        assertEquals(12, Updater.numberFromTag("v1.0.12"))
        assertEquals("Nova capa", Updater.cleanNotes("Nova capa\n\nCo-Authored-By: x\nClaude-Session: y"))
    }
}
