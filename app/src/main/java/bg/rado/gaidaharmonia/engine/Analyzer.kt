package bg.rado.gaidaharmonia.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class LiveNote(val pc: Int, val cents: Int, val freq: Double)

/** Начало на нов тон в мелодията (секунди по часовника на анализатора). */
data class Onset(val time: Double, val pc: Int)

/** Метод на McLeod (NSDF) за височина на тона. */
object PitchDetector {
    class Result(val freq: Double, val clarity: Float)

    fun detect(buf: FloatArray, n: Int, sr: Int, minF: Double, maxF: Double, nsdf: FloatArray, peaks: IntArray): Result? {
        val maxLag = minOf(n / 2, (sr / minF).toInt())
        val minLag = maxOf(2, (sr / maxF).toInt())
        for (tau in 0..maxLag) {
            var acf = 0.0; var m = 0.0
            val lim = n - tau
            for (i in 0 until lim) { val a = buf[i]; val b = buf[i + tau]; acf += a * b; m += a * a + b * b }
            nsdf[tau] = if (m > 0) (2 * acf / m).toFloat() else 0f
        }
        var pk = 0; var t = 1
        while (t < maxLag && nsdf[t] > 0) t++
        while (t < maxLag && pk < peaks.size) {
            while (t < maxLag && nsdf[t] <= 0) t++
            var bi = -1; var bv = Float.NEGATIVE_INFINITY
            while (t < maxLag && nsdf[t] > 0) { if (nsdf[t] > bv) { bv = nsdf[t]; bi = t }; t++ }
            if (bi > 0) peaks[pk++] = bi
        }
        if (pk == 0) return null
        var mx = 0f
        for (i in 0 until pk) if (nsdf[peaks[i]] > mx) mx = nsdf[peaks[i]]
        val thr = 0.9f * mx; var pick = -1
        for (i in 0 until pk) if (peaks[i] >= minLag && nsdf[peaks[i]] >= thr) { pick = peaks[i]; break }
        if (pick < 1) return null
        val a0 = nsdf[pick - 1]; val b0 = nsdf[pick]; val c0 = if (pick + 1 <= maxLag) nsdf[pick + 1] else b0
        val den = a0 - 2 * b0 + c0
        val shift = if (den != 0f) 0.5 * (a0 - c0) / den else 0.0
        return Result(sr / (pick + shift), b0)
    }

    fun toNote(f: Double): LiveNote {
        val midi = 69 + 12 * ln(f / 440.0) / ln(2.0)
        val r = midi.roundToInt()
        return LiveNote(((r % 12) + 12) % 12, ((midi - r) * 100).roundToInt(), f)
    }
}

/**
 * Слуша гайдата: мелодия (прозорец ~23 ms, анализ на всеки 5 ms), ручилото (тоника)
 * и началата на тоновете (ритъм). Праговете се нагласят сами спрямо силата на звука.
 */
class Analyzer {
    private val n = 1024
    private val dn = 1024
    private val lock = Any()
    private val ring = FloatArray(n); private val frame = FloatArray(n); private val nsdf = FloatArray(n / 2 + 2)
    private val peaks = IntArray(64)
    private val short = FloatArray(12); private val long = FloatArray(12)
    private val dRing = FloatArray(dn); private val dFrame = FloatArray(dn); private val dNsdf = FloatArray(dn / 2 + 2)
    private val onsets = ArrayList<Onset>()

    private var ringPos = 0; private var sinceHop = 0; private var sr = 0; private var hop = 220
    private var dPos = 0; private var dDecim = 8; private var dCount = 0; private var dSince = 0; private var dSr = 5512
    private var samples = 0L
    private lateinit var hp1: Biquad; private lateinit var hp2: Biquad
    private lateinit var lp1: Biquad; private lateinit var lp2: Biquad
    private var peak = 0.01f

    private var stablePc = -1; private var stableN = 0; private var curPc = -1
    private var curStart = 0L; private var lastVoiced = Long.MIN_VALUE / 2
    private var droneCand = -1; private var droneHits = 0; private var droneMiss = 0
    private var departAt = -1L

    @Volatile var live: LiveNote? = null; private set
    @Volatile var rms = 0f; private set
    @Volatile var dronePc = -1; private set

    init { setSampleRate(44100) }

    val sampleRate: Int get() = synchronized(lock) { sr }

    fun setSampleRate(rate: Int) = synchronized(lock) {
        if (rate == sr) return@synchronized
        sr = rate; hop = maxOf(64, rate / 200)
        // Бътъруърт 4-ти ред: махаме ручилото от мелодията / оставяме само него за тониката
        hp1 = Biquad().hp(130.0, 0.5412, rate); hp2 = Biquad().hp(130.0, 1.3066, rate)
        lp1 = Biquad().lp(170.0, 0.5412, rate); lp2 = Biquad().lp(170.0, 1.3066, rate)
        dDecim = maxOf(1, Math.round(rate / 5500.0).toInt())
        dSr = Math.round(rate / dDecim.toDouble()).toInt()
    }

    /** Часовник на анализатора: колко секунди звук е обработил. */
    val timeSec: Double get() = synchronized(lock) { samples.toDouble() / sr }
    /** Тонът, който се държи в момента (-1 ако няма). */
    val heldPc: Int get() = synchronized(lock) { curPc }
    /** Откога се държи текущият тон (от оценката за реалното му начало). */
    val heldSec: Double get() = synchronized(lock) { if (curPc < 0) 0.0 else (samples - curStart).toDouble() / sr }
    /** Откога няма ясен тон. */
    val silentSec: Double get() = synchronized(lock) { (samples - lastVoiced).toDouble() / sr }

    fun reset() = synchronized(lock) {
        short.fill(0f); long.fill(0f); onsets.clear()
        live = null; rms = 0f; stablePc = -1; stableN = 0; curPc = -1; peak = 0.01f
        lastVoiced = samples - sr * 10L; departAt = -1; dronePc = -1; droneCand = -1; droneHits = 0
    }

    fun shortCopy(): FloatArray = synchronized(lock) { short.copyOf() }
    fun longCopy(): FloatArray = synchronized(lock) { long.copyOf() }
    fun onsetsCopy(): List<Onset> = synchronized(lock) { ArrayList(onsets) }

    fun process(x: FloatArray, offset: Int, count: Int) = synchronized(lock) {
        for (i in 0 until count) {
            val v = x[offset + i].toDouble()
            ring[ringPos] = hp2.run(hp1.run(v)).toFloat()
            ringPos = (ringPos + 1) % n
            val d = lp2.run(lp1.run(v))
            if (++dCount >= dDecim) {
                dCount = 0; dRing[dPos] = d.toFloat(); dPos = (dPos + 1) % dn
                if (++dSince >= 256) { dSince = 0; analyzeDrone() }
            }
            samples++
            if (++sinceHop >= hop) { sinceHop = 0; analyze() }
        }
    }

    private fun analyze() {
        for (i in 0 until n) frame[i] = ring[(ringPos + i) % n]
        var sum = 0.0
        for (v in frame) sum += v * v
        val r = sqrt(sum / n).toFloat(); rms = r
        val dt = hop.toFloat() / sr
        peak = maxOf(r, peak * exp(-dt / 6.0).toFloat())
        val gate = maxOf(0.0015f, peak * 0.08f)   // ~22 dB под най-силното от последните секунди

        val ds = exp(-dt / 0.35).toFloat(); val dl = exp(-dt / 25.0).toFloat()
        for (i in 0 until 12) { short[i] *= ds; long[i] *= dl }

        var note: LiveNote? = null; var clar = 0f
        if (r > gate) {
            val p = PitchDetector.detect(frame, n, sr, 150.0, 1600.0, nsdf, peaks)
            if (p != null && p.clarity > 0.8f) { note = PitchDetector.toNote(p.freq); clar = p.clarity }
        }
        // момент, в който височината за пръв път се отклони от задържания тон (орнамент, преход)
        if (note != null && note.pc != curPc && departAt < 0) departAt = samples
        if (note != null) {
            lastVoiced = samples
            if (note.pc == stablePc) stableN++ else { stablePc = note.pc; stableN = 1 }
            val recent = departAt >= 0 && samples - departAt < sr * 0.09
            if (stableN == 2 && note.pc != curPc) {
                // нов тон: началото е там, където височината се е отклонила (вкл. орнамента преди него)
                curPc = note.pc
                curStart = if (recent) departAt - n / 2 else samples - n / 2 - 2 * hop
                addOnset(curStart.toDouble() / sr, note.pc)
                departAt = -1
            } else if (note.pc == curPc && stableN >= 2 && departAt >= 0) {
                // същият тон, повторен след орнамент — ново ритмично начало
                if (recent) addOnset((departAt - n / 2).toDouble() / sr, note.pc)
                departAt = -1
            }
            if (stableN >= 2) {
                short[note.pc] += dt * clar; long[note.pc] += dt * clar
                live = note
            }
        } else {
            stablePc = -1; stableN = 0
            if (samples - lastVoiced > sr * 0.08) { curPc = -1; live = null }
        }
        if (departAt >= 0 && samples - departAt > sr * 0.3) departAt = -1
    }

    private fun addOnset(t: Double, pc: Int) {
        // орнамент + основен тон в рамките на 60 ms = едно начало
        if (onsets.isNotEmpty() && t - onsets.last().time < 0.06) return
        onsets += Onset(t, pc)
        val cut = t - 16
        while (onsets.isNotEmpty() && onsets[0].time < cut) onsets.removeAt(0)
    }

    private fun analyzeDrone() {
        for (i in 0 until dn) dFrame[i] = dRing[(dPos + i) % dn]
        var s = 0.0
        for (v in dFrame) s += v * v
        val p = if (sqrt(s / dn) > 0.002) PitchDetector.detect(dFrame, dn, dSr, 40.0, 180.0, dNsdf, peaks) else null
        if (p != null && p.clarity > 0.85f) {
            val pc = PitchDetector.toNote(p.freq).pc
            if (pc == droneCand) droneHits++ else { droneCand = pc; droneHits = 1 }
            if (droneHits >= 4) { dronePc = pc; droneMiss = 0 }
        } else if (++droneMiss > 60) dronePc = -1   // ~3 s без ручило
    }
}
