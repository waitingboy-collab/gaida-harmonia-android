package bg.rado.gaidaharmonia.engine

import kotlin.math.pow

/** Акорд в лада: корен (pitch class 0–11), мажор/минор и римска цифра спрямо тониката. */
data class Chord(val deg: Int, val root: Int, val minor: Boolean, val roman: String) {
    val pcs: IntArray get() = intArrayOf(root, (root + if (minor) 3 else 4) % 12, (root + 7) % 12)
    val label: String get() = Theory.LETTERS[root] + if (minor) "m" else ""
    val long: String get() = Theory.BG[root] + if (minor) " минор" else " мажор"
    fun same(o: Chord?): Boolean = o != null && o.root == root && o.minor == minor
}

data class Mode(val key: String, val name: String, val steps: IntArray)

/** Размер: групи от осмини. groups == null означава свободен ритъм. */
data class Meter(val key: String, val name: String, val groups: IntArray?, val bpm: Int)

data class PatternUnit(
    val groupStart: Boolean,
    val group: Int,
    val drum: Char,   // 'B' силен бум, 'b' слаб бум, 't' пръчка
    val bass: Char,   // 'R' корен, 'F' квинта, ' ' нищо
    val chord: Boolean
)

object Theory {
    val LETTERS = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
    val BG = arrayOf("До", "До♯", "Ре", "Ми♭", "Ми", "Фа", "Фа♯", "Сол", "Ла♭", "Ла", "Си♭", "Си")
    private val MAJ = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val ROMAN = arrayOf("I", "II", "III", "IV", "V", "VI", "VII")

    val MODES = listOf(
        Mode("mixo", "Миксолидийски (VII понижена)", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
        Mode("dor", "Дорийски (минор с висока VI)", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
        Mode("maj", "Мажор", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
        Mode("min", "Натурален минор", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
        Mode("phr", "Фригийски (ниска II)", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
        Mode("hij", "Хиджаз (увеличена секунда)", intArrayOf(0, 1, 4, 5, 7, 8, 10)),
    )

    val METERS = listOf(
        Meter("pravo", "Право хоро — 2/4", intArrayOf(2, 2), 125),
        Meter("rach", "Ръченица — 7/8 (2+2+3)", intArrayOf(2, 2, 3), 150),
        Meter("payd", "Пайдушко — 5/8 (2+3)", intArrayOf(2, 3), 130),
        Meter("daych", "Дайчово — 9/8 (2+2+2+3)", intArrayOf(2, 2, 2, 3), 150),
        Meter("kop", "Копаница — 11/8 (2+2+3+2+2)", intArrayOf(2, 2, 3, 2, 2), 170),
        Meter("free", "Бавна песен — без тъпан", null, 70),
    )

    fun parseGroups(s: String): IntArray {
        val g = s.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }.filter { it in 1..4 }.take(8)
        return if (g.isEmpty()) intArrayOf(2, 2) else g.toIntArray()
    }

    fun modeChords(tonic: Int, mode: Mode): List<Chord> {
        val sc = mode.steps
        val out = ArrayList<Chord>()
        for (d in 0 until 7) {
            val r = sc[d]
            val th = sc[(d + 2) % 7] + if (d + 2 >= 7) 12 else 0
            val fi = sc[(d + 4) % 7] + if (d + 4 >= 7) 12 else 0
            val i3 = th - r; val i5 = fi - r
            val minor = when {
                i3 == 4 && i5 == 7 -> false
                i3 == 3 && i5 == 7 -> true
                else -> null
            } ?: continue
            val acc = r - MAJ[d]
            var rn = ROMAN[d]
            if (minor) rn = rn.lowercase()
            rn = (if (acc < 0) "♭" else if (acc > 0) "♯" else "") + rn
            out += Chord(d, (tonic + r) % 12, minor, rn)
        }
        // Мажорна доминанта: избира се само ако гайдата свири повишената VII.
        if (sc[4] == 7 && out.none { it.deg == 4 && !it.minor }) out += Chord(4, (tonic + 7) % 12, false, "V")
        return out
    }

    /** Оценява всеки акорд спрямо хистограмата на скоро свирените тонове. */
    fun pickChord(w: FloatArray, cands: List<Chord>, current: Chord?, tonicChord: Chord, stick: Float): Chord {
        val total = w.sum()
        if (total < 0.05f) return current ?: tonicChord
        var best = tonicChord; var bestS = Float.NEGATIVE_INFINITY
        for (c in cands) {
            val p = c.pcs; var s = 0f
            for (pc in 0 until 12) {
                val wi = w[pc]; if (wi <= 0f) continue
                s += wi * when (pc) { p[0] -> 1f; p[1] -> 0.85f; p[2] -> 0.7f; else -> -0.5f }
            }
            if (c.same(tonicChord)) s += 0.12f * total
            if (c.same(current)) s += stick * total
            if (s > bestS) { bestS = s; best = c }
        }
        return best
    }

    fun buildPattern(groups: IntArray): Array<PatternUnit> {
        val u = ArrayList<PatternUnit>()
        groups.forEachIndexed { gi, len ->
            for (k in 0 until len) u += PatternUnit(
                groupStart = k == 0, group = gi,
                drum = if (k == 0) (if (gi == 0) 'B' else 'b') else 't',
                bass = if (k == 0) (if (gi % 2 == 0) 'R' else 'F') else ' ',
                chord = k > 0
            )
        }
        return u.toTypedArray()
    }

    fun fitRange(pc: Int, lo: Int, hi: Int): Int {
        var m = lo + (((pc - lo) % 12) + 12) % 12
        while (m > hi) m -= 12
        return m
    }

    fun voiceChord(c: Chord): IntArray {
        val v = c.pcs.map { fitRange(it, 55, 66) }.sorted()
        return (v + (v[0] + 12)).toIntArray()
    }

    fun midiToFreq(m: Double): Double = 440.0 * 2.0.pow((m - 69) / 12.0)
}
