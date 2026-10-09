package com.dmwnezes.estante.data

/** Reconhece temporada e episódio pelos nomes e decide qual episódio vem a seguir. */
object Episodes {

    private val collator = java.text.Collator.getInstance(java.util.Locale("pt", "BR"))

    /** Temporada, episódio e depois o título (para episódios sem número). */
    val order: Comparator<Video> = compareBy<Video> { if (it.season == 0) Int.MAX_VALUE else it.season }
        .thenBy { if (it.episode == 0) Int.MAX_VALUE else it.episode }
        .thenComparator { a, b -> collator.compare(a.title, b.title) }

    data class Number(val season: Int, val episode: Int)

    private val sxe = Regex("""(?i)\bs(\d{1,2})\s*[ ._-]?\s*e(\d{1,3})\b""")          // S01E02, s1 e2
    private val nxn = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""")                        // 1x02
    private val tep = Regex("""(?i)\bt(?:emp(?:orada)?)?\s*(\d{1,2})\D{0,6}e(?:p(?:is[oó]dio)?)?\s*(\d{1,3})\b""") // T1E02, Temporada 1 Episódio 2
    private val ep = Regex("""(?i)\b(?:ep|epis[oó]dio|episode|cap[ií]tulo|aula)\s*[._-]?\s*(\d{1,3})\b""")
    private val seasonWord = Regex("""(?i)\b(?:temporada|season|temp|s|t)\s*[._-]?\s*(\d{1,2})\b""")
    private val leadingNumber = Regex("""^\s*(\d{1,3})(?:\D|$)""")

    /** Número da temporada a partir do nome da pasta ("Temporada 2", "Season 02", "S2"). */
    fun seasonFromFolder(name: String): Int = seasonWord.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Temporada/episódio a partir do nome do arquivo. [folderSeason] vale se o arquivo não disser. */
    fun parse(fileName: String, folderSeason: Int = 0): Number {
        val name = fileName.substringBeforeLast('.')
        sxe.find(name)?.let { return Number(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        nxn.find(name)?.let { return Number(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        tep.find(name)?.let { return Number(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        ep.find(name)?.let { return Number(folderSeason.coerceAtLeast(if (folderSeason == 0) 1 else 0), it.groupValues[1].toInt()) }
        leadingNumber.find(name)?.let { return Number(folderSeason.coerceAtLeast(1), it.groupValues[1].toInt()) }
        return Number(folderSeason, 0)
    }

    /**
     * Episódio para "Continuar": o começado mais recente; senão o seguinte ao último
     * terminado; senão o primeiro. Devolve o índice dentro de [episodes] (já ordenados).
     */
    fun nextIndex(episodes: List<Video>): Int {
        if (episodes.isEmpty()) return -1
        val inProgress = episodes.withIndex().filter { Resume.startAt(it.value) > 0 }.maxByOrNull { it.value.watchedAt }
        val lastDone = episodes.withIndex().filter { it.value.finished }.maxByOrNull { it.value.watchedAt }
        if (inProgress != null && (lastDone == null || inProgress.value.watchedAt >= lastDone.value.watchedAt)) return inProgress.index
        if (lastDone != null) {
            val after = (lastDone.index + 1 until episodes.size).firstOrNull { !episodes[it].finished }
            if (after != null) return after
            if (episodes.all { it.finished }) return 0
        }
        return episodes.indexOfFirst { !it.finished }.takeIf { it >= 0 } ?: 0
    }

    /** "3 de 10 assistidos". */
    fun progressLabel(episodes: List<Video>): String {
        val done = episodes.count { it.finished }
        return if (episodes.size == 1) "1 episódio" else "$done de ${episodes.size} assistidos"
    }
}
