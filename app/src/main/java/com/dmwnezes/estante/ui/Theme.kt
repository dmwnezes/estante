package com.dmwnezes.estante.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dmwnezes.estante.R

/** Paleta "Noite suave", a mesma do Palavreiro: azul-escuro e roxo aveludados, lavanda, verde e amarelo. */
object Cinema {
    val bgTop = Color(0xFF2A2058)
    val bgBottom = Color(0xFF120E2B)
    val surface = Color(0xFF231C48)
    val surfaceHigh = Color(0xFF2F275C)
    val outline = Color(0xFF4A4180)
    val text = Color(0xFFF4F1FF)
    val muted = Color(0xFFA9A2D0)
    val accent = Color(0xFF9B8CFF)
    val onAccent = Color(0xFF1A1438)
    val red = Color(0xFFE5737A)
    /** Verde e amarelo do Palavreiro: assistido / destaque. */
    val green = Color(0xFF5FB873)
    val yellow = Color(0xFFE6C14F)
    /** Fundo do chat da sala e campos. */
    val field = Color(0xFF1C1640)

    // Madeira (usada pelos temas de madeira)
    val woodTop = Color(0xFFB07C50)
    val woodFront = Color(0xFF7E522F)
    val woodDark = Color(0xFF4A2F1B)
    val woodGrain = Color(0x22000000)

    val background: Brush get() = Brush.verticalGradient(listOf(bgTop, bgBottom))

    /** Cores das capas geradas (quando o DVD não tem imagem). */
    val coverHues = listOf(
        Color(0xFF7A6BE0), Color(0xFFE6C14F), Color(0xFF5FB873), Color(0xFF5C6FA8),
        Color(0xFFB48CF0), Color(0xFFE5737A), Color(0xFF4E7FA0), Color(0xFF6FA8E8),
    )
}

/** Formas orgânicas: cantos bem arredondados. */
object Shapes {
    val case = RoundedCornerShape(7.dp)
    val card = RoundedCornerShape(26.dp)
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val pill = RoundedCornerShape(50)
    val field = RoundedCornerShape(18.dp)
}

/** Fonte geométrica: Outfit (a mesma do Palavreiro). */
val Outfit = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_extrabold, FontWeight.ExtraBold),
)

@Composable
fun EstanteTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.o() = copy(fontFamily = Outfit)
    val typography = Typography(
        displayLarge = base.displayLarge.o(), displayMedium = base.displayMedium.o(), displaySmall = base.displaySmall.o(),
        headlineLarge = base.headlineLarge.o(), headlineMedium = base.headlineMedium.o(), headlineSmall = base.headlineSmall.o(),
        titleLarge = base.titleLarge.o(), titleMedium = base.titleMedium.o(), titleSmall = base.titleSmall.o(),
        bodyLarge = base.bodyLarge.o(), bodyMedium = base.bodyMedium.o(), bodySmall = base.bodySmall.o(),
        labelLarge = base.labelLarge.o(), labelMedium = base.labelMedium.o(), labelSmall = base.labelSmall.o(),
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cinema.accent,
            onPrimary = Cinema.onAccent,
            secondary = Cinema.green,
            background = Cinema.bgBottom,
            surface = Cinema.surface,
            surfaceVariant = Cinema.surfaceHigh,
            surfaceContainer = Cinema.surface,
            surfaceContainerHigh = Cinema.surfaceHigh,
            surfaceContainerHighest = Cinema.surfaceHigh,
            surfaceContainerLow = Cinema.surface,
            onSurface = Cinema.text,
            onSurfaceVariant = Cinema.muted,
            outline = Cinema.outline,
            outlineVariant = Cinema.outline,
            error = Cinema.red,
        ),
        typography = typography,
        content = content,
    )
}
