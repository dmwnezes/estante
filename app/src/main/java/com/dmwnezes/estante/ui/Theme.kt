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

/** Paleta "Sala de cinema": parede escura e quente, madeira e luz âmbar. */
object Cinema {
    val bgTop = Color(0xFF2E211B)
    val bgBottom = Color(0xFF130D0B)
    val surface = Color(0xFF2A1F1A)
    val surfaceHigh = Color(0xFF3A2C25)
    val outline = Color(0xFF574538)
    val text = Color(0xFFF7EEE6)
    val muted = Color(0xFFBFAE9F)
    val accent = Color(0xFFF0A94B)
    val onAccent = Color(0xFF2A1A0E)
    val red = Color(0xFFE5737A)

    // Madeira da estante
    val woodTop = Color(0xFFB07C50)
    val woodFront = Color(0xFF7E522F)
    val woodDark = Color(0xFF4A2F1B)
    val woodGrain = Color(0x22000000)

    val background: Brush get() = Brush.verticalGradient(listOf(bgTop, bgBottom))

    /** Cores das capas geradas (quando o DVD não tem imagem). */
    val coverHues = listOf(
        Color(0xFFC8643F), Color(0xFFF0A94B), Color(0xFF5E8C7A), Color(0xFF5C6FA8),
        Color(0xFF9A5B8C), Color(0xFFB8893E), Color(0xFF4E7FA0), Color(0xFF8C4A3E),
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
            secondary = Cinema.woodTop,
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
