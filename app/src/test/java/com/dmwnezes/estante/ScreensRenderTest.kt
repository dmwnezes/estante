package com.dmwnezes.estante

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dmwnezes.estante.data.LibraryState
import com.dmwnezes.estante.data.Playlist
import com.dmwnezes.estante.data.SortOrder
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.ui.EstanteTheme
import com.dmwnezes.estante.ui.ShelfScreen
import com.dmwnezes.estante.ui.SplashCredits
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreensRenderTest {

    @get:Rule val compose = createComposeRule()

    private val sample = LibraryState(
        videos = listOf(
            Video("1", Source.DRIVE, "a", "O Grande Filme", shelf = "Filmes", positionMs = 600_000, durationMs = 6_000_000, watchedAt = 3),
            Video("2", Source.DRIVE, "b", "Episódio 1", shelf = "Séries", finished = true),
            Video("3", Source.LOCAL, "content://x", "Vídeo da festa"),
        ),
        playlists = listOf(Playlist("p", "Maratona", listOf("1", "2"))),
    )

    @Test fun `estante com prateleiras`() {
        compose.setContent {
            EstanteTheme {
                ShelfScreen(sample, SortOrder.TITLE, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Continuar assistindo").assertExists()
        compose.onNodeWithText("Minha estante").assertExists()
        compose.onNodeWithText("parou em 10:00").assertExists()
    }

    @Test fun `estante vazia`() {
        compose.setContent { EstanteTheme { ShelfScreen(LibraryState(), SortOrder.TITLE, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Sua estante está vazia").assertExists()
    }

    @Test fun `abertura`() {
        compose.setContent { EstanteTheme { SplashCredits {} } }
        compose.onNodeWithText("@dmwnezes").assertExists()
    }
}
