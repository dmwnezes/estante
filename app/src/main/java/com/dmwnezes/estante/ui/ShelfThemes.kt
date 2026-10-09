package com.dmwnezes.estante.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.dmwnezes.estante.AppGraph

/** Aparência da estante: parede (fundo), tábua e plaquinha das prateleiras. */
data class ShelfTheme(
    val key: String,
    val name: String,
    val wallTop: Color,
    val wallBottom: Color,
    val plankTop: Color,
    val plankFront: Color,
    val plankDark: Color,
    val grain: Color,
    val labelTop: Color,
    val labelBottom: Color,
    val labelText: Color,
    /** Detalhe extra desenhado na tábua: veios (madeira), parafusos (metal) ou faixa (locadora). */
    val style: Style,
) {
    enum class Style { WOOD, METAL, RETRO }

    val wall: Brush get() = Brush.verticalGradient(listOf(wallTop, wallBottom))
}

object ShelfThemes {
    val darkWood = ShelfTheme(
        "escura", "Madeira escura",
        Color(0xFF2E211B), Color(0xFF130D0B),
        Color(0xFFB07C50), Color(0xFF7E522F), Color(0xFF4A2F1B), Color(0x22000000),
        Color(0xFFB07C50), Color(0xFF7E522F), Color(0xFFFFF4E6),
        ShelfTheme.Style.WOOD,
    )
    val lightWood = ShelfTheme(
        "clara", "Madeira clara",
        Color(0xFF4A3C31), Color(0xFF241B15),
        Color(0xFFE8CFA6), Color(0xFFC9A273), Color(0xFF9C7648), Color(0x1F5A3A1A),
        Color(0xFFEBD3AA), Color(0xFFC9A273), Color(0xFF3B2715),
        ShelfTheme.Style.WOOD,
    )
    val metal = ShelfTheme(
        "metal", "Metal",
        Color(0xFF24272B), Color(0xFF0E1012),
        Color(0xFFB9C0C8), Color(0xFF7D858F), Color(0xFF4B5159), Color(0x33FFFFFF),
        Color(0xFFC9CFD6), Color(0xFF8A929C), Color(0xFF1A1D21),
        ShelfTheme.Style.METAL,
    )
    val retro = ShelfTheme(
        "locadora", "Locadora anos 90",
        Color(0xFF1C1440), Color(0xFF0A0718),
        Color(0xFFFFD23F), Color(0xFF1F4FD1), Color(0xFF12307F), Color(0x33FFFFFF),
        Color(0xFFFF3D7F), Color(0xFFC21E5B), Color(0xFFFFFFFF),
        ShelfTheme.Style.RETRO,
    )

    val all = listOf(darkWood, lightWood, metal, retro)

    /** Tema escolhido (fica salvo). */
    var current by mutableStateOf(darkWood)
        private set

    fun load() {
        val k = runCatching { AppGraph.prefs.getString("shelfTheme", null) }.getOrNull()
        current = all.firstOrNull { it.key == k } ?: darkWood
    }

    fun select(t: ShelfTheme) {
        current = t
        runCatching { AppGraph.prefs.edit().putString("shelfTheme", t.key).apply() }
    }
}

val LocalShelfTheme = compositionLocalOf { ShelfThemes.darkWood }
