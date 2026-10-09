package com.dmwnezes.estante.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.Video
import java.io.File

/** Proporção de uma caixa de DVD (13,5 × 19 cm). */
const val CASE_RATIO = 0.71f

/**
 * Uma caixa de DVD: lombada de plástico à esquerda, capa, brilho do plástico,
 * barra de progresso (se começou a assistir) e selo de "assistido".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DvdCase(
    video: Video,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .aspectRatio(CASE_RATIO)
            .shadow(10.dp, Shapes.case, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(Shapes.case)
            .background(Color(0xFF0E0B0A))
            .then(
                if (onClick != null || onLongClick != null)
                    Modifier.combinedClickable(onLongClick = onLongClick, onClick = { onClick?.invoke() })
                else Modifier
            ),
    ) {
        Row(Modifier.fillMaxSize()) {
            Spine(Modifier.fillMaxHeight().width(7.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CoverArt(video)
            }
        }
        PlasticGlare()
        if (showProgress) {
            val p = Resume.progress(video)
            if (Resume.startAt(video) > 0 && p > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Cinema.accent))
                }
            }
            if (video.finished) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape)
                        .background(Cinema.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, "Assistido", tint = Cinema.onAccent, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/** A capa: imagem escolhida ou uma capa gerada com o título. */
@Composable
fun BoxScope.CoverArt(video: Video) {
    val path = video.cover
    if (path != null && File(path).exists()) {
        AsyncImage(
            model = File(path),
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    } else {
        GeneratedCover(video.title, Modifier.matchParentSize())
    }
}

/** Capa automática: cor tirada do título, título grande e um rolo de filme. */
@Composable
fun GeneratedCover(title: String, modifier: Modifier = Modifier) {
    val h = title.fold(17) { acc, c -> acc * 131 + c.code }
    val hue = Cinema.coverHues[Math.floorMod(h xor (h ushr 13), Cinema.coverHues.size)]
    Box(
        modifier.background(Brush.verticalGradient(listOf(hue, hue.darken(0.55f)))),
    ) {
        // faixas diagonais discretas
        Canvas(Modifier.matchParentSize()) {
            val step = size.width / 3
            for (i in -2..4) {
                drawLine(
                    Color.White.copy(alpha = 0.06f),
                    Offset(i * step, size.height), Offset(i * step + size.height * 0.6f, 0f),
                    strokeWidth = step * 0.35f,
                )
            }
        }
        Column(Modifier.matchParentSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                title,
                color = Color.White,
                fontFamily = Outfit,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                lineHeight = 17.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Rounded.Movie, null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        }
    }
}

private fun Color.darken(f: Float) = Color(red * f, green * f, blue * f, alpha)

/** Lombada de plástico preto com frisos. */
@Composable
private fun Spine(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.horizontalGradient(listOf(Color(0xFF2B2624), Color(0xFF0B0909), Color(0xFF1C1816))))
        val ridge = Color.White.copy(alpha = 0.07f)
        var y = size.height * 0.08f
        while (y < size.height * 0.92f) {
            drawLine(ridge, Offset(size.width * 0.25f, y), Offset(size.width * 0.75f, y), strokeWidth = 1f)
            y += size.height * 0.035f
        }
    }
}

/** Reflexo do plástico da caixa. */
@Composable
private fun BoxScope.PlasticGlare() {
    Box(
        Modifier.matchParentSize()
            .background(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.20f),
                    0.28f to Color.White.copy(alpha = 0.04f),
                    0.55f to Color.Transparent,
                    1f to Color.White.copy(alpha = 0.06f),
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.10f), Shapes.case)
    )
}

/** Tábua de madeira da estante (face de cima clara, frente escura com veios e sombra). */
@Composable
fun Plank(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        val top = 7.dp.toPx()
        val front = 11.dp.toPx()
        val r = CornerRadius(5.dp.toPx())
        // sombra projetada embaixo
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), startY = top + front, endY = size.height),
            topLeft = Offset(6.dp.toPx(), top + front - 2), size = Size(size.width - 12.dp.toPx(), size.height - top - front + 2),
        )
        // frente
        drawRoundRect(
            Brush.verticalGradient(listOf(Cinema.woodFront, Cinema.woodDark), startY = top, endY = top + front),
            topLeft = Offset(0f, top - 2), size = Size(size.width, front + 2), cornerRadius = r,
        )
        // veios da madeira
        for (i in 0 until 3) {
            val y = top + front * (0.3f + i * 0.22f)
            drawLine(Cinema.woodGrain, Offset(size.width * (0.05f + i * 0.1f), y), Offset(size.width * (0.6f + i * 0.12f), y + 1), strokeWidth = 1.2f)
        }
        // face de cima
        drawRoundRect(
            Brush.verticalGradient(listOf(Cinema.woodTop.copy(alpha = 0.85f), Cinema.woodTop), startY = 0f, endY = top),
            topLeft = Offset(0f, 0f), size = Size(size.width, top), cornerRadius = r,
        )
        drawLine(Color.White.copy(alpha = 0.18f), Offset(r.x, 1f), Offset(size.width - r.x, 1f), strokeWidth = 1.5f)
    }
}

/**
 * Uma fileira da estante: até [columns] DVDs em pé sobre a tábua, com os títulos
 * logo abaixo, como etiquetas.
 */
@Composable
fun ShelfRow(
    videos: List<Video>,
    columns: Int,
    onOpen: (Video) -> Unit,
    onLongPress: (Video) -> Unit = {},
    gap: Dp = 14.dp,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).offset(y = 5.dp).zIndex(1f),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (i in 0 until columns) {
                val v = videos.getOrNull(i)
                Box(Modifier.weight(1f)) {
                    if (v != null) DvdCase(v, Modifier.fillMaxWidth(), onLongClick = { onLongPress(v) }, onClick = { onOpen(v) })
                }
            }
        }
        Plank()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            for (i in 0 until columns) {
                Box(Modifier.weight(1f)) {
                    videos.getOrNull(i)?.let {
                        Text(
                            it.title,
                            color = Cinema.muted,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

/** Plaquinha com o nome da prateleira. */
@Composable
fun ShelfLabel(text: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(Cinema.woodTop, Cinema.woodFront)))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(text, color = Color(0xFFFFF4E6), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (count == 1) "1 DVD" else "$count DVDs", color = Cinema.muted, fontSize = 12.sp)
    }
}
