package com.dmwnezes.estante.party

import kotlin.math.abs
import kotlin.random.Random

/** Estado do vídeo na sala: tocando ou pausado, em que segundo, e quando isso foi decidido. */
data class PlayState(
    val playing: Boolean,
    val position: Double,
    /** Horário do servidor (ms) em que [position] valia. */
    val at: Long,
    val by: String,
    val seq: Long = 0,
)

data class Person(val id: String, val name: String, val lastSeen: Long, val platform: String, val avatar: String? = null)

/** Emoji mandado na sala (sobe por cima do filme nos dois celulares). */
data class Reaction(val id: String, val emoji: String, val name: String, val at: Long, val by: String)

/** Reações permitidas (as mesmas do site). */
val REACTIONS = listOf("😂", "😱", "❤️", "👏", "😭", "🍿")

data class ChatMessage(val id: String, val name: String, val text: String, val at: Long, val by: String, val image: String? = null)

/** Contas da sincronia (sem Android, para poder testar). */
object PartySync {

    const val SITE = "https://dmwnezes.github.io/estante/sala/"
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Onde o vídeo deveria estar agora, segundo o último comando de quem mexeu. */
    fun expected(s: PlayState, serverNow: Long): Double =
        s.position + if (s.playing) (serverNow - s.at).coerceAtLeast(0) / 1000.0 else 0.0

    /** Diferença maior que isso, ajusta (menos que isso, ninguém percebe). */
    fun needsSeek(local: Double, expected: Double, tolerance: Double = 1.0) = abs(local - expected) > tolerance

    /** Diferença entre o relógio do celular e o do servidor, a partir de uma ida e volta. */
    fun offset(sentAt: Long, receivedAt: Long, serverTs: Long): Long = serverTs - (sentAt + receivedAt) / 2

    /** Está digitando se avisou nos últimos 4 s. */
    fun typing(at: Long, serverNow: Long) = serverNow - at in 0..4_000

    /** "Mandis está digitando…" / "Mandis e Ana estão digitando…". */
    fun typingLabel(names: List<String>): String? = when (names.size) {
        0 -> null
        1 -> "${names[0]} está digitando…"
        else -> names.dropLast(1).joinToString(", ") + " e ${names.last()} estão digitando…"
    }

    /** Está na sala se deu sinal de vida nos últimos 25 s. */
    fun online(p: Person, serverNow: Long) = serverNow - p.lastSeen < 25_000

    fun newCode(random: Random = Random.Default): String = (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** "https://x-default-rtdb.firebaseio.com/" ou só o endereço → "https://x-default-rtdb.firebaseio.com". */
    fun normalizeDbUrl(input: String): String? {
        val host = input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').trim()
        if (host.isBlank() || !(host.endsWith("firebaseio.com") || host.endsWith("firebasedatabase.app"))) return null
        return "https://$host"
    }

    /** Link que a amiga abre no iPhone: código da sala e o banco ficam depois do #. */
    fun link(code: String, dbUrl: String): String = "$SITE#$code@${dbUrl.removePrefix("https://")}"

    /**
     * O iPhone (Safari) toca MP4/MOV (H.264 ou HEVC). MKV o site converte na hora
     * (precisa do iOS 17.1+); AVI, WEBM e outros não abrem.
     */
    fun iphoneFriendly(fileName: String?, mime: String?): Boolean {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in setOf("mp4", "m4v", "mov", "mkv")) return true
        if (ext in setOf("avi", "webm", "wmv", "flv")) return false
        return mime in setOf("video/mp4", "video/quicktime", "video/x-m4v")
    }

    /** Tipo do arquivo que vai para a sala (o site usa para saber se precisa converter o MKV). */
    fun roomMime(fileName: String?): String = when (fileName?.substringAfterLast('.', "")?.lowercase()) {
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        else -> "video/mp4"
    }
}
