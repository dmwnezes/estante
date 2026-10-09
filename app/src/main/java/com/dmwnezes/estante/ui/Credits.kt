package com.dmwnezes.estante.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmwnezes.estante.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

const val CREATOR_HANDLE = "dmwnezes"

/** Abre o perfil do criador no app do Instagram (ou no navegador). */
fun openCreatorInstagram(context: Context) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/_u/$CREATOR_HANDLE")).setPackage("com.instagram.android")
    try {
        context.startActivity(app)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/$CREATOR_HANDLE/")))
    }
}

/** Foto redonda + "criado por: @dmwnezes", tocável, igual ao Sintonia e ao Palavreiro. */
@Composable
fun CreatorCredit(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable { openCreatorInstagram(context) }
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.criador),
            contentDescription = "Foto de @$CREATOR_HANDLE",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(34.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text("criado por: ", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        Text("@$CREATOR_HANDLE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
    }
}

/** Queda com quique amortecido: 0 = lá em cima, 1 = pousado na tábua. */
private fun drop(t: Float): Float {
    if (t <= 0f) return 0f
    val fall = 0.45f
    if (t < fall) { val x = t / fall; return x * x }
    val s = t - fall
    return 1f - abs(cos(s * 9f + PI.toFloat() / 2)) * exp(-s * 6f) * 0.18f
}

/**
 * Abertura: três DVDs caem e pousam na tábua, aparece o nome e os créditos.
 * Some sozinha depois de ~3,4 s ou ao tocar na tela.
 */
@Composable
fun SplashCredits(onDone: () -> Unit) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (time < 3.4f) withFrameNanos { now -> time = (now - start) / 1_000_000_000f }
        onDone()
    }
    val textAlpha = ((time - 1.4f) / 0.5f).coerceIn(0f, 1f)
    val cases = listOf(
        Triple(Cinema.coverHues[0], 0.10f, -4f),
        Triple(Cinema.accent, 0.30f, 0f),
        Triple(Color(0xFFF4E6D4), 0.50f, 8f),
    )
    Box(
        Modifier.fillMaxSize().background(Cinema.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDone),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.width(230.dp).height(130.dp).padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                cases.forEachIndexed { i, (color, delay, tilt) ->
                    val d = drop(time - delay)
                    Box(
                        Modifier.weight(1f).height(if (i == 1) 110.dp else 96.dp)
                            .graphicsLayer {
                                translationY = -(1f - d) * 420f
                                alpha = (d * 3f).coerceIn(0f, 1f)
                                rotationZ = tilt * d
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                            }
                            .clip(Shapes.case)
                            .background(Brush.verticalGradient(listOf(color, color.copy(red = color.red * 0.6f, green = color.green * 0.6f, blue = color.blue * 0.6f))))
                    ) {
                        Box(Modifier.width(6.dp).height(200.dp).background(Color.Black.copy(alpha = 0.35f)))
                    }
                }
            }
            Plank(Modifier.width(250.dp))
            Spacer(Modifier.height(26.dp))
            Text(
                "Estante", color = Cinema.text, fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Outfit,
                modifier = Modifier.graphicsLayer { alpha = textAlpha; translationY = (1f - textAlpha) * 30f },
            )
        }
        CreatorCredit(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp).graphicsLayer { alpha = textAlpha },
        )
    }
}
