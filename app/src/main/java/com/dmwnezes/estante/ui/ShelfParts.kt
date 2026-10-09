package com.dmwnezes.estante.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dmwnezes.estante.data.Box as SeriesBox
import com.dmwnezes.estante.data.Episodes
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.ShelfItem
import com.dmwnezes.estante.data.Video
import java.io.File

/** Proporção de uma caixa de DVD (13,5 × 19 cm). */
const val CASE_RATIO = 0.71f

/** Capa: imagem salva ou capa gerada com o título. */
@Composable
fun BoxScope.CoverImage(path: String?, title: String) {
    if (path != null && File(path).exists()) {
        AsyncImage(model = File(path), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    } else {
        GeneratedCover(title, Modifier.matchParentSize())
    }
}

@Composable
fun BoxScope.CoverArt(video: Video) = CoverImage(video.cover, video.title)

/** Casca da caixa: sombra, lombada, capa, brilho do plástico e o que vier por cima. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaseShell(
    modifier: Modifier,
    spineWidth: Int = 7,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    cover: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
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
            Spine(Modifier.fillMaxHeight().width(spineWidth.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) { cover() }
        }
        PlasticGlare()
        overlay()
    }
}

/**
 * Uma caixa de DVD: lombada de plástico à esquerda, capa, brilho do plástico,
 * barra de progresso (se começou a assistir) e selo de "assistido".
 */
@Composable
fun DvdCase(
    video: Video,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    CaseShell(modifier, onClick = onClick, onLongClick = onLongClick, cover = { CoverArt(video) }) {
        if (showProgress) {
            val p = Resume.progress(video)
            if (Resume.startAt(video) > 0 && p > 0f) ProgressBar(p)
            if (video.finished) WatchedBadge()
        }
    }
}

/**
 * Caixa de série (box): lombada mais grossa, discos "empilhados" atrás e uma faixa
 * com o número de episódios. O progresso é a fração de episódios assistidos.
 */
@Composable
fun BoxCase(
    box: SeriesBox,
    episodes: List<Video>,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Box(modifier.aspectRatio(CASE_RATIO)) {
        // Duas caixas atrás, levemente deslocadas: dá a ideia de box.
        for (i in 2 downTo 1) {
            Box(
                Modifier.matchParentSize()
                    .offset(x = (i * 4).dp, y = (-i * 3).dp)
                    .clip(Shapes.case)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF221C19), Color(0xFF3A302A))))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), Shapes.case)
            )
        }
        CaseShell(Modifier.matchParentSize(), spineWidth = 11, onClick = onClick, onLongClick = onLongClick, cover = { CoverImage(box.cover, box.title) }) {
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(start = 15.dp, end = 6.dp, top = 10.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SÉRIE · ${episodes.size} EP",
                    color = Cinema.accent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp,
                    maxLines = 1, overflow = TextOverflow.Clip,
                )
            }
            if (showProgress && episodes.isNotEmpty()) {
                val done = episodes.count { it.finished }.toFloat() / episodes.size
                if (done > 0f) ProgressBar(done)
                if (episodes.all { it.finished }) WatchedBadge()
            }
        }
    }
}

/** Desenha o que estiver na prateleira (DVD ou série). */
@Composable
fun ItemCase(item: ShelfItem, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    when (item) {
        is ShelfItem.Single -> DvdCase(item.video, modifier, onLongClick = onLongClick, onClick = onClick)
        is ShelfItem.Series -> BoxCase(item.box, item.episodes, modifier, onLongClick = onLongClick, onClick = onClick)
    }
}

@Composable
private fun BoxScope.ProgressBar(p: Float) {
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = 0.55f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Cinema.accent))
    }
}

@Composable
private fun BoxScope.WatchedBadge() {
    Box(
        Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(Cinema.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, "Assistido", tint = Cinema.onAccent, modifier = Modifier.size(14.dp))
    }
}

/** Capa automática: cor tirada do título, título grande e um rolo de filme. */
@Composable
fun GeneratedCover(title: String, modifier: Modifier = Modifier) {
    val h = title.fold(17) { acc, c -> acc * 131 + c.code }
    val hue = Cinema.coverHues[Math.floorMod(h xor (h ushr 13), Cinema.coverHues.size)]
    Box(modifier.background(Brush.verticalGradient(listOf(hue, hue.darken(0.55f))))) {
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
                title, color = Color.White, fontFamily = Outfit, fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp, lineHeight = 17.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
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

/**
 * Caixa abrindo: a capa gira pela lombada para a esquerda e mostra o disco.
 * [open] vai de 0 (fechada) a 1 (aberta); [spin] é o giro do disco em graus.
 * Ocupa a largura de duas capas.
 */
@Composable
fun OpenCase(coverPath: String?, title: String, open: Float, spin: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.aspectRatio(CASE_RATIO * 2)) {
        val half = maxWidth / 2
        // Bandeja do disco (metade direita): plástico preto com o disco.
        Box(
            Modifier.offset(x = half).width(half).fillMaxHeight()
                .shadow(10.dp, Shapes.case).clip(Shapes.case)
                .background(Brush.verticalGradient(listOf(Color(0xFF1E1A18), Color(0xFF0C0A09))))
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Disc(coverPath, title, Modifier.fillMaxWidth().aspectRatio(1f).rotate(spin))
        }
        // Capa: dobra na lombada (canto esquerdo da bandeja).
        val angle = -180f * open
        Box(
            Modifier.offset(x = half).width(half).fillMaxHeight()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = angle
                    cameraDistance = 14f * density
                }
                .clip(Shapes.case),
        ) {
            if (angle > -90f) {
                Box(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxSize()) {
                        Spine(Modifier.fillMaxHeight().width(7.dp))
                        Box(Modifier.weight(1f).fillMaxHeight()) { CoverImage(coverPath, title) }
                    }
                    PlasticGlare()
                }
            } else {
                // Lado de dentro da capa (aparece espelhado, então desenhamos o encarte "virado").
                Box(
                    Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }
                        .background(Brush.verticalGradient(listOf(Color(0xFF2A2522), Color(0xFF151210)))),
                ) {
                    Box(
                        Modifier.align(Alignment.Center).fillMaxWidth(0.72f).fillMaxHeight(0.8f)
                            .clip(RoundedCornerShape(4.dp)).graphicsLayer { alpha = 0.55f },
                    ) { CoverImage(coverPath, title) }
                    Canvas(Modifier.matchParentSize()) {
                        // presilhas do encarte
                        val c = Color.White.copy(alpha = 0.12f)
                        drawRoundRect(c, Offset(size.width * 0.82f, size.height * 0.08f), Size(size.width * 0.1f, size.height * 0.04f), CornerRadius(4f))
                        drawRoundRect(c, Offset(size.width * 0.82f, size.height * 0.88f), Size(size.width * 0.1f, size.height * 0.04f), CornerRadius(4f))
                    }
                }
            }
        }
    }
}

/** O disco: prata com a arte da capa no rótulo, anéis e o furo do meio. */
@Composable
fun Disc(coverPath: String?, title: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(Brush.sweepGradient(listOf(Color(0xFFE6E6EA), Color(0xFF9EA3AD), Color(0xFFF5F5F7), Color(0xFFB5B9C2), Color(0xFFE6E6EA))))) {
        Box(Modifier.align(Alignment.Center).fillMaxSize(0.78f).clip(CircleShape)) {
            CoverImage(coverPath, title)
        }
        Canvas(Modifier.matchParentSize()) {
            val r = size.minDimension / 2
            val c = center
            drawCircle(Color.White.copy(alpha = 0.18f), r * 0.78f, c, style = Stroke(1.5f))
            drawCircle(Color(0xFFCFD2D8), r * 0.2f, c)
            drawCircle(Color.Black.copy(alpha = 0.25f), r * 0.2f, c, style = Stroke(1f))
            drawCircle(Color(0xFF0C0A09), r * 0.09f, c)
            // brilho que gira junto com o disco
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent)),
                startAngle = -20f, sweepAngle = 40f, useCenter = true,
            )
        }
    }
}

/** Tábua da estante, no tema escolhido (madeira, metal ou locadora). */
@Composable
fun Plank(modifier: Modifier = Modifier) {
    val t = LocalShelfTheme.current
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        val top = 7.dp.toPx()
        val front = 11.dp.toPx()
        val r = CornerRadius(5.dp.toPx())
        drawRoundRect(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), startY = top + front, endY = size.height),
            topLeft = Offset(6.dp.toPx(), top + front - 2), size = Size(size.width - 12.dp.toPx(), size.height - top - front + 2),
        )
        drawRoundRect(
            Brush.verticalGradient(listOf(t.plankFront, t.plankDark), startY = top, endY = top + front),
            topLeft = Offset(0f, top - 2), size = Size(size.width, front + 2), cornerRadius = r,
        )
        when (t.style) {
            com.dmwnezes.estante.ui.ShelfTheme.Style.WOOD -> for (i in 0 until 3) {
                val y = top + front * (0.3f + i * 0.22f)
                drawLine(t.grain, Offset(size.width * (0.05f + i * 0.1f), y), Offset(size.width * (0.6f + i * 0.12f), y + 1), strokeWidth = 1.2f)
            }
            com.dmwnezes.estante.ui.ShelfTheme.Style.METAL -> {
                drawLine(t.grain, Offset(0f, top + front * 0.35f), Offset(size.width, top + front * 0.35f), strokeWidth = 1f)
                for (x in listOf(0.03f, 0.97f, 0.5f)) drawCircle(Color.White.copy(alpha = 0.35f), 1.8.dp.toPx(), Offset(size.width * x, top + front * 0.55f))
            }
            com.dmwnezes.estante.ui.ShelfTheme.Style.RETRO -> {
                drawRect(t.plankTop, Offset(0f, top + front * 0.55f), Size(size.width, front * 0.18f))
            }
        }
        drawRoundRect(
            Brush.verticalGradient(listOf(t.plankTop.copy(alpha = 0.85f), t.plankTop), startY = 0f, endY = top),
            topLeft = Offset(0f, 0f), size = Size(size.width, top), cornerRadius = r,
        )
        drawLine(Color.White.copy(alpha = 0.18f), Offset(r.x, 1f), Offset(size.width - r.x, 1f), strokeWidth = 1.5f)
    }
}

/** Plaquinha com o nome da prateleira. */
@Composable
fun ShelfLabel(text: String, count: Int, modifier: Modifier = Modifier) {
    val t = LocalShelfTheme.current
    Row(modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(Brush.verticalGradient(listOf(t.labelTop, t.labelBottom)))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(text, color = t.labelText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (count == 1) "1 item" else "$count itens", color = Cinema.muted, fontSize = 12.sp)
    }
}

/** Texto curto embaixo do DVD ou da série. */
fun itemCaption(item: ShelfItem): String? = when (item) {
    is ShelfItem.Single -> null
    is ShelfItem.Series -> Episodes.progressLabel(item.episodes)
}
