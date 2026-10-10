package com.dmwnezes.estante.party

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmwnezes.estante.R
import com.dmwnezes.estante.ui.Cinema

/**
 * Fotos de perfil prontas da sala (as mesmas do site, em sala/avatars.js).
 * Na sala vai só a chave ("fantasma"); quem não escolheu aparece com a inicial do nome.
 */
object Avatars {
    data class Pic(val key: String, val label: String, val res: Int)

    val all = listOf(
        Pic("fantasma", "Fantasma", R.drawable.av_fantasma),
        Pic("coleira", "Coleira de cachorro", R.drawable.av_coleira),
        Pic("pipoca", "Pipoca", R.drawable.av_pipoca),
        Pic("claquete", "Claquete", R.drawable.av_claquete),
        Pic("disco", "Disco voador", R.drawable.av_disco),
        Pic("oculos", "Óculos de herói de ação", R.drawable.av_oculos),
        Pic("pegada", "Pegada de dinossauro", R.drawable.av_pegada),
        Pic("tubarao", "Barbatana de tubarão", R.drawable.av_tubarao),
        Pic("robo", "Robô", R.drawable.av_robo),
        Pic("mapa", "Mapa do tesouro", R.drawable.av_mapa),
        Pic("vampiro", "Vampiro", R.drawable.av_vampiro),
        Pic("bruxa", "Chapéu de bruxa", R.drawable.av_bruxa),
        Pic("foguete", "Foguete", R.drawable.av_foguete),
        Pic("lupa", "Lupa de detetive", R.drawable.av_lupa),
        Pic("ingresso", "Ingresso de cinema", R.drawable.av_ingresso),
        Pic("camera", "Câmera de cinema", R.drawable.av_camera),
        Pic("cowboy", "Chapéu de cowboy", R.drawable.av_cowboy),
        Pic("mascara", "Máscara de herói", R.drawable.av_mascara),
        Pic("pirata", "Caveira pirata", R.drawable.av_pirata),
        Pic("planeta", "Planeta", R.drawable.av_planeta),
    )

    private val byKey = all.associateBy { it.key }

    fun find(key: String?): Pic? = key?.let { byKey[it] }

    /** Uma qualquer (para quem ainda não escolheu). */
    fun random(): String = all.random().key
}

/** Foto da pessoa: a escolhida ou, se não tiver, a inicial do nome na cor dela. */
@Composable
fun PersonPic(name: String, avatar: String?, size: Dp, modifier: Modifier = Modifier) {
    val pic = Avatars.find(avatar)
    if (pic != null) {
        Image(painterResource(pic.res), pic.label, modifier.size(size).clip(CircleShape))
    } else {
        Box(modifier.size(size).clip(CircleShape).background(personColor(name)), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase().ifBlank { "?" }, color = Cinema.onAccent, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** Grade com todas as fotos para escolher. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarPicker(selected: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatars.all.forEach { p ->
            val on = p.key == selected
            Image(
                painterResource(p.res), p.label,
                Modifier.size(size).clip(CircleShape)
                    .border(if (on) 3.dp else 0.dp, if (on) Cinema.accent else Cinema.surface, CircleShape)
                    .clickable { onPick(p.key) }
                    .semantics { contentDescription = p.label + if (on) " (escolhida)" else "" },
            )
        }
    }
}
