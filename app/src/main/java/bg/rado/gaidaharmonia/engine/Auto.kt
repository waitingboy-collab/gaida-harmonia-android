package bg.rado.gaidaharmonia.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Засича тоника и лад от свирените тонове и от ручилото. */
class KeyTracker {
    var tonic = 2; private set
    var modeIdx = 0; private set
    var known = false; private set
    private var candT = -1; private var candM = -1; private var candN = 0

    fun reset() { known = false; candN = 0; candT = -1 }

    /** @return true ако тоналността се е сменила. */
    fun update(lw: FloatArray, dronePc: Int): Boolean {
        val total = lw.sum()
        if (total < 0.6f) {
            // още няма достатъчно мелодия — ручилото казва тониката
            if (!known && dronePc >= 0 && dronePc != tonic) { tonic = dronePc; return true }
            return false
        }
        var bt = tonic; var bm = modeIdx; var bs = Float.NEGATIVE_INFINITY
        for (t in 0 until 12) for (m in Theory.MODES.indices) {
            var inS = 0f
            for (s in Theory.MODES[m].steps) inS += lw[(t + s) % 12]
            var sc = inS - 1.6f * (total - inS) + 0.35f * lw[t] + 0.12f * lw[(t + 7) % 12]
            if (t == dronePc) sc += 0.3f * total
            if (t == tonic && m == modeIdx) sc += 0.04f * total
            if (sc > bs) { bs = sc; bt = t; bm = m }
        }
        if (bt == tonic && bm == modeIdx) { candN = 0; known = true; return false }
        if (bt == candT && bm == candM) candN++ else { candT = bt; candM = bm; candN = 1 }
        if (!known || candN >= 3) { tonic = bt; modeIdx = bm; known = true; candN = 0; return true }
        return false
    }
}

/**
 * Засича темпото (най-късия удар), размера (2+2, 2+3, 2+2+3…) и къде е първото време,
 * само от началата на тоновете. Ако ритъмът не е равен — приема бавна песен (без тъпан).
 */
class RhythmTracker {
    var locked = false; private set
    var unit = 0.2; private set
    var phase = 0.0; private set
    var groups: IntArray = intArrayOf(2, 2); private set
    var fit = 0.0; private set

    private val cands = listOf(intArrayOf(2, 2), intArrayOf(2, 3), intArrayOf(2, 2, 3), intArrayOf(2, 2, 2, 3), intArrayOf(2, 2, 3, 2, 2))
    private var pending: IntArray? = null; private var pendingN = 0; private var weak = 0; private var had = false

    fun reset() { locked = false; pending = null; pendingN = 0; weak = 0; had = false }

    fun update(all: List<Onset>, now: Double, forced: IntArray?) {
        if (all.isEmpty() || now - all.last().time > 2.0) { locked = false; return }
        val on = all.filter { it.time > now - 12 }
        if (on.size < 8) { locked = false; return }
        val ioi = ArrayList<Double>()
        for (i in 1 until on.size) { val d = on[i].time - on[i - 1].time; if (d > 0.06 && d < 1.3) ioi += d }
        if (ioi.size < 6) { locked = false; return }

        // 1) Темпо
        val us = ArrayList<Double>(); val ss = ArrayList<Double>(); var best = 0.0
        var u = 0.12
        while (u <= 0.34) {
            var s = 0.0
            for (d in ioi) {
                val r = d / u; val k = Math.round(r).toDouble()
                if (k < 1) { val e = r - 0.5; s += 0.8 * exp(-e * e / (2 * 0.1 * 0.1)); continue }
                val e = r - k
                val g = exp(-e * e / (2 * 0.14 * 0.14))
                s += if (k > 4) 0.5 * g else g
            }
            s /= ioi.size
            us += u; ss += s
            if (s > best) best = s
            u += 0.002
        }
        if (best < (if (locked) 0.52 else 0.6)) { if (++weak >= 2) locked = false; fit = best; return }
        weak = 0

        // 2) Кандидатите се уточняват по фазова кохерентност: колко плътно началата лягат на мрежата.
        //    Устойчиво на пропуснати и излишни начала. При равенство — по-дългият удар.
        class Cand(val coh: Double, val u: Double, val ang: Double)
        val cand = ArrayList<Cand>()
        for (i in us.indices) {
            val s0 = ss[i]
            val peak = (i == 0 || s0 >= ss[i - 1]) && (i == ss.size - 1 || s0 >= ss[i + 1])
            if (!peak || s0 < 0.8 * best) continue
            var coh = -1.0; var uRef = us[i]; var ang0 = 0.0
            var uu = us[i] * 0.97
            while (uu <= us[i] * 1.03) {
                var cs = 0.0; var sn = 0.0
                for (o in on) { val ang = 2 * PI * o.time / uu; cs += cos(ang); sn += sin(ang) }
                val r = sqrt(cs * cs + sn * sn) / on.size
                if (r > coh) { coh = r; uRef = uu; ang0 = atan2(sn, cs) }
                uu += 0.0004
            }
            cand += Cand(coh, uRef, ang0)
        }
        val maxCoh = cand.maxOf { it.coh }
        val pick = cand.filter { it.coh >= 0.9 * maxCoh }.maxBy { it.u }
        val unitS = pick.u
        val a = pick.ang / (2 * PI) * unitS
        val n = LongArray(on.size); val ok = BooleanArray(on.size)
        for (i in on.indices) {
            val r = (on[i].time - a) / unitS
            n[i] = r.roundToLong()
            ok[i] = abs(r - n[i]) < 0.3      // извън мрежата = орнамент/шум
        }
        if (ok.count { it } < 6) { locked = false; return }

        // 3) Размер
        val w = DoubleArray(on.size) { i ->
            if (!ok[i]) 0.0 else if (i + 1 < on.size) minOf((on[i + 1].time - on[i].time) / unitS, 3.0) else 1.0
        }
        var nmin = Long.MAX_VALUE; var nmax = Long.MIN_VALUE
        for (i in on.indices) if (ok[i]) { nmin = minOf(nmin, n[i]); nmax = maxOf(nmax, n[i]) }
        val x = DoubleArray((nmax - nmin + 1).toInt())
        for (i in on.indices) if (ok[i]) x[(n[i] - nmin).toInt()] += w[i]

        var bestG = groups; var bestR = 0; var bestC = Double.NEGATIVE_INFINITY
        var curR = 0; var curC = Double.NEGATIVE_INFINITY
        for (g in if (forced != null) listOf(forced) else cands) {
            val l = g.sum()
            val per = acf(x, l); val per2 = if (2 * l < x.size / 2) acf(x, 2 * l) else per
            val periodic = (per + 0.5 * per2) / 1.5
            val tpl = DoubleArray(l); var p = 0
            for (v in g) { tpl[p] = 1.0; p += v }
            var rotBest = Double.NEGATIVE_INFINITY; var rBest = 0
            for (r in 0 until l) {
                val h = DoubleArray(l)
                for (i in on.indices) h[(((n[i] + r) % l + l) % l).toInt()] += w[i]
                val c = pearson(h, tpl)
                if (c > rotBest) { rotBest = c; rBest = r }
            }
            val sc = 0.55 * periodic + 0.45 * rotBest - 0.01 * l
            if (sc > bestC) { bestC = sc; bestG = g; bestR = rBest }
            if (g.contentEquals(groups)) { curC = sc; curR = rBest }
        }

        val useG: IntArray; val useR: Int
        if (forced != null || !had || bestG.contentEquals(groups)) { useG = bestG; useR = bestR; pendingN = 0 }
        else if (bestC < curC + 0.08) { useG = groups; useR = curR; pendingN = 0 }
        else {
            if (pending?.contentEquals(bestG) == true) pendingN++ else { pending = bestG; pendingN = 1 }
            if (pendingN >= 4) { useG = bestG; useR = bestR; pendingN = 0 } else { useG = groups; useR = curR }
        }
        val lu = useG.sum()
        groups = useG; unit = unitS
        phase = a + ((lu - useR) % lu) * unitS
        fit = best; locked = true; had = true
    }

    /** Следващият кратък удар след t и номерът му в такта. */
    fun nextUnitAfter(t: Double): Pair<Double, Int> {
        val l = groups.sum()
        val k = ceil((t - phase) / unit).toLong()
        return Pair(phase + k * unit, (((k % l) + l) % l).toInt())
    }

    private fun acf(x: DoubleArray, lag: Int): Double {
        val n = x.size - lag
        if (n < 4) return 0.0
        return pearson(x.copyOfRange(0, n), x.copyOfRange(lag, lag + n))
    }

    private fun pearson(x: DoubleArray, y: DoubleArray): Double {
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in x.indices) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx); syy += (y[i] - my) * (y[i] - my) }
        return if (sxx <= 1e-12 || syy <= 1e-12) 0.0 else sxy / sqrt(sxx * syy)
    }
}
