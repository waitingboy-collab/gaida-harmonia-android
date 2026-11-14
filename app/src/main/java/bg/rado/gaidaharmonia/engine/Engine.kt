package bg.rado.gaidaharmonia.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class SourceKind { MIC, FILE, DEMO }
internal enum class Bus { CHORD, BASS, DRUM, SRC }

/* ------------------------- DSP helpers ------------------------- */
internal class Biquad {
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    fun lp(f: Double, q: Double, sr: Int): Biquad {
        val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
        b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - al) / a0; return this
    }
    fun hp(f: Double, q: Double, sr: Int): Biquad {
        val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
        b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = b0; a1 = -2 * c / a0; a2 = (1 - al) / a0; return this
    }
    fun bp(f: Double, q: Double, sr: Int): Biquad {
        val w = 2 * PI * f / sr; val c = cos(w); val al = sin(w) / (2 * q); val a0 = 1 + al
        b0 = al / a0; b1 = 0.0; b2 = -al / a0; a1 = -2 * c / a0; a2 = (1 - al) / a0; return this
    }
    fun run(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y; return y
    }
}

internal abstract class Voice(protected val sr: Int, val bus: Bus) {
    protected var t = 0L
    var toAnalyzer = false
    var done = false
    abstract fun next(): Double
    protected val sec: Double get() = t.toDouble() / sr

    companion object {
        fun noise() = Random.nextDouble() * 2 - 1
        fun cents(c: Double) = 2.0.pow(c / 1200.0)
        fun expEnv(t: Double, attack: Double, decayTo0: Double, peak: Double): Double {
            if (t < attack) return peak * t / attack
            val d = t - attack
            if (d >= decayTo0) return 0.0
            return peak * 0.0001.pow(d / decayTo0)
        }
    }
}

/** Осцилатор с фаза 0..1. */
internal class Osc(var inc: Double, var ph: Double = Random.nextDouble()) {
    private fun step() { ph += inc; if (ph >= 1) ph -= 1 }
    fun saw(): Double { step(); return 2 * ph - 1 }
    fun tri(): Double { step(); return 4 * abs(ph - 0.5) - 1 }
    fun sine(): Double { step(); return sin(2 * PI * ph) }
}

/** Кратък акорд „па“ — 4 гласа × 2 разстроени триона. */
internal class StabVoice(sr: Int, midis: IntArray, private val vel: Double) : Voice(sr, Bus.CHORD) {
    private val oscs = midis.flatMap { m ->
        val f = Theory.midiToFreq(m.toDouble())
        listOf(Osc(f * cents(-6.0) / sr), Osc(f * cents(6.0) / sr))
    }
    private val lp = Biquad().lp(2600.0, 0.8, sr)
    override fun next(): Double {
        val s0 = sec
        if (s0 > 0.26) { done = true; return 0.0 }
        if ((t and 63L) == 0L) lp.lp(2600 * (900 / 2600.0).pow(minOf(1.0, s0 / 0.2)), 0.8, sr)
        var s = 0.0
        for (o in oscs) s += o.saw()
        t++
        return lp.run(s) * expEnv(s0, 0.006, 0.24, 0.07 * vel)
    }
}

/** Задържан акорд: сменя се веднага с кратко преливане. */
internal class PadVoice(sr: Int, midis: IntArray, levelScale: Double) : Voice(sr, Bus.CHORD) {
    private val saws = midis.map { Osc(Theory.midiToFreq(it.toDouble()) * cents(-8.0) / sr) }
    private val tris = midis.map { Osc(Theory.midiToFreq(it.toDouble()) * cents(7.0) / sr) }
    private val lp = Biquad().lp(1500.0, 0.7, sr)
    private val peak = 0.05 * levelScale
    private var released = false
    private var level = 0.0
    private var relLevel = 0.0
    private var relTau = 0.25
    fun release(tau: Double) { if (!released) { released = true; relLevel = level; relTau = tau; t = 0 } }
    override fun next(): Double {
        var s = 0.0
        for (o in saws) s += o.saw()
        for (o in tris) s += o.tri()
        val s0 = sec; t++
        if (!released) level = peak * minOf(1.0, s0 / 0.025)
        else { level = relLevel * exp(-s0 / relTau); if (level < 1e-5) done = true }
        return lp.run(s) * level
    }
}

internal class BassVoice(sr: Int, midi: Int, private val dur: Double) : Voice(sr, Bus.BASS) {
    private val o1 = Osc(Theory.midiToFreq(midi.toDouble()) / sr, 0.0)
    private val o2 = Osc(Theory.midiToFreq(midi.toDouble()) / sr, 0.0)
    private val lp = Biquad().lp(700.0, 0.7, sr)
    override fun next(): Double {
        val s0 = sec
        if (s0 > dur + 0.01) { done = true; return 0.0 }
        val s = o1.tri() + o2.sine(); t++
        return lp.run(s) * expEnv(s0, 0.008, dur, 0.28)
    }
}

/** Тъпан: кукудата (голямата страна) — бум. */
internal class BumVoice(sr: Int, private val strong: Boolean) : Voice(sr, Bus.DRUM) {
    private val o = Osc(0.0, 0.0)
    private val bp = Biquad().bp(900.0, 0.8, sr)
    override fun next(): Double {
        val s0 = sec
        if (s0 > 0.4) { done = true; return 0.0 }
        val f0 = if (strong) 120.0 else 105.0
        o.inc = f0 * (42 / f0).pow(minOf(1.0, s0 / 0.16)) / sr
        val v = if (strong) 0.95 else 0.6
        var s = o.sine() * expEnv(s0, 0.004, 0.38, v)
        if (s0 < 0.05) s += bp.run(noise()) * expEnv(s0, 0.0005, 0.05, 0.6 * v)
        t++; return s
    }
}

/** Тъпан: пръчката (прачката) от тънката страна. */
internal class StickVoice(sr: Int) : Voice(sr, Bus.DRUM) {
    private val hp = Biquad().hp(2800.0, 0.7, sr)
    override fun next(): Double {
        val s0 = sec
        if (s0 > 0.05) { done = true; return 0.0 }
        t++; return hp.run(noise()) * expEnv(s0, 0.0005, 0.045, 0.45)
    }
}

/** Синтезирана гайда за демо режима. */
internal class DemoNoteVoice(sr: Int, midi: Int, private val dur: Double) : Voice(sr, Bus.SRC) {
    private val f = Theory.midiToFreq(midi.toDouble())
    private val o1 = Osc(0.0); private val o2 = Osc(0.0); private val vib = Osc(5.5 / sr, 0.0)
    private val lp = Biquad().lp(3200.0, 0.7, sr)
    init { toAnalyzer = true }
    override fun next(): Double {
        val s0 = sec
        if (s0 > dur + 0.005) { done = true; return 0.0 }
        val v = cents(6 * vib.sine())
        o1.inc = f * v * cents(-4.0) / sr; o2.inc = f * v * cents(4.0) / sr
        val s = o1.saw() + o2.saw()
        val env = when {
            s0 < 0.004 -> s0 / 0.004
            s0 > dur - 0.004 -> maxOf(0.0, (dur - s0) / 0.004)
            else -> 1.0
        }
        t++; return lp.run(s) * env * 0.14
    }
}

internal class DroneVoice(sr: Int, midi: Int) : Voice(sr, Bus.SRC) {
    private val o = Osc(Theory.midiToFreq(midi.toDouble()) / sr)
    private val lp = Biquad().lp(600.0, 0.7, sr)
    @Volatile var stop = false
    init { toAnalyzer = true }
    override fun next(): Double { if (stop) { done = true; return 0.0 }; return lp.run(o.saw()) * 0.12 }
}

/* ------------------------- Engine ------------------------- */
/**
 * Акомпанимент, който следва гайдата в реално време:
 *  • акордът се сменя веднага щом се задържи тон извън текущия акорд (~0.05–0.08 s);
 *  • тоника, лад, темпо и размер се засичат сами;
 *  • тъпанът влиза, когато ритъмът е равен, и спира, когато гайдата спре.
 * Всички публични методи са thread-safe.
 */
class Engine(val analyzer: Analyzer) {
    private val sync = Any()
    val key = KeyTracker()
    val rhythm = RhythmTracker()

    var sr = 48000; private set
    private val voices = ArrayList<Voice>()
    private var pad: PadVoice? = null
    private var drone: DroneVoice? = null
    private var anBuf = FloatArray(0)
    private var cands: List<Chord> = Theory.modeChords(key.tonic, Theory.MODES[key.modeIdx])

    @Volatile var volChord = 0.55f
    @Volatile var volBass = 0.7f
    @Volatile var volDrum = 0.7f
    @Volatile var volSrc = 0.8f
    @Volatile var auto = true; private set
    var manual: Chord? = null; private set
    /** -1 = засичай сам; иначе индекс в Theory.METERS. */
    @Volatile var meterOverride = -1
    @Volatile var outLatencySec = 0.04

    var source = SourceKind.MIC; private set
    private var fileData: FloatArray? = null
    private var filePos = 0

    private var pos = 0L; private var nextKey = 0L; private var nextRhythm = 0L
    private var nextUnit = 0.0; private var unitSamples = 0.0; private var clockOffset = 0.0
    private var grid = false; private var offsetInit = false
    private var unitIdx = 0
    private var gridGroups: IntArray? = null
    private var pattern = Theory.buildPattern(intArrayOf(2, 2))

    @Volatile var currentUnit = -1; private set
    @Volatile var current: Chord = tonicChord(); private set
    @Volatile var running = false; private set
    /** Колко ms след началото на тона е прозвучал новият акорд. */
    @Volatile var lastReactionMs = -1; private set
    @Volatile var rhythmLocked = false; private set
    @Volatile var groups: IntArray? = null; private set

    // за тестове
    var onChordChange: ((Long, Chord, Double) -> Unit)? = null
    var onDemoNote: ((Long, Int) -> Unit)? = null
    var onBeat: ((Long, Int) -> Unit)? = null
    var demoOrigin = 0.0; private set

    val mode: Mode get() = Theory.MODES[key.modeIdx]
    val tonic: Int get() = key.tonic
    val keyKnown: Boolean get() = key.known
    val unitSec: Double get() = rhythm.unit
    val hasFile: Boolean get() = fileData != null
    fun candidates(): List<Chord> = synchronized(sync) { cands.toList() }
    private fun tonicChord(): Chord = cands.firstOrNull { it.deg == 0 } ?: cands[0]

    fun configure(rate: Int) = synchronized(sync) {
        sr = rate; voices.clear(); pad = null; drone = null
        if (source != SourceKind.MIC) analyzer.setSampleRate(rate)
    }

    fun setSource(s: SourceKind) = synchronized(sync) {
        source = s
        drone?.stop = true; drone = null
        filePos = 0
        if (s != SourceKind.MIC) analyzer.setSampleRate(sr)
        if (running) restartAnalysis()
    }

    fun setFile(data: FloatArray?) = synchronized(sync) { fileData = data; filePos = 0 }

    fun followMelody(on: Boolean) = synchronized(sync) { auto = on; if (on) manual = null }

    fun forceChord(c: Chord) = synchronized(sync) { manual = c; auto = false; change(c, -1.0) }

    fun start() = synchronized(sync) { running = true; filePos = 0; restartAnalysis() }

    private fun restartAnalysis() {
        analyzer.reset(); key.reset(); rhythm.reset()
        cands = Theory.modeChords(key.tonic, mode)
        if (auto) current = tonicChord()
        setGrid(false); offsetInit = false
        nextKey = pos; nextRhythm = pos
        releasePad(0.1)
        if (source == SourceKind.DEMO) startDemo()
    }

    fun stop() = synchronized(sync) {
        running = false; setGrid(false)
        releasePad(0.25)
        drone?.stop = true; drone = null
    }

    private fun setGrid(on: Boolean) {
        grid = on; rhythmLocked = on
        groups = if (on) gridGroups else null
        if (!on) currentUnit = -1
    }

    /* ---------- акорди ---------- */
    private fun releasePad(tau: Double) { pad?.release(tau); pad = null }

    private fun newPad() {
        releasePad(0.04)
        pad = PadVoice(sr, Theory.voiceChord(current), if (grid) 0.5 else 1.0).also { voices += it }
    }

    private fun change(c: Chord, heldSec: Double) {
        current = c
        if (heldSec >= 0) lastReactionMs = Math.round((heldSec + outLatencySec) * 1000).toInt()
        newPad()
        voices += BassVoice(sr, Theory.fitRange(c.root, 36, 47), if (grid) 1.6 * rhythm.unit else 2.2)
        onChordChange?.invoke(pos, c, heldSec)
    }

    private fun score(c: Chord, w: FloatArray, total: Float): Float {
        val p = c.pcs; var s = 0f
        for (pc in 0 until 12) {
            val wi = w[pc]; if (wi <= 0f) continue
            s += wi * when (pc) { p[0] -> 1f; p[1] -> 0.85f; p[2] -> 0.7f; else -> -0.6f }
        }
        // народна музика: главно I, IV, V и ♭VII; минорните акорди — по-рядко
        if (c.deg == 0) s += 0.15f * total
        else if (!c.minor && (c.deg == 3 || c.deg == 4 || c.deg == 6)) s += 0.08f * total
        else if (c.minor) s -= 0.05f * total
        return s
    }

    /** Вика се на всеки 64 семпъла. Акордът винаги съдържа задържания тон. */
    private fun updateHarmony() {
        if (!auto) { manual?.let { if (!it.same(current)) change(it, -1.0) }; return }
        val held = analyzer.heldPc
        if (held < 0) return
        val heldSec = analyzer.heldSec
        val th = if (grid) (0.3 * rhythm.unit).coerceIn(0.05, 0.08) else 0.065
        if (heldSec < th) return                       // орнамент или преход — още не е тон
        if (pad != null && current.pcs.contains(held) && heldSec > th + 0.05) return
        val w = analyzer.shortCopy(); val total = w.sum()
        var best: Chord? = null; var bs = Float.NEGATIVE_INFINITY
        for (c in cands) {
            if (!c.pcs.contains(held)) continue
            val s = score(c, w, total) + if (c.same(current)) 0.15f * total else 0f
            if (s > bs) { bs = s; best = c }
        }
        val b = best ?: return                          // тон извън лада — остави акорда
        if (!b.same(current)) change(b, heldSec) else if (pad == null) newPad()
    }

    private fun onKeyChanged() {
        cands = Theory.modeChords(key.tonic, mode)
        cands.firstOrNull { it.same(current) }?.let { current = it }
        manual = manual?.let { m -> cands.firstOrNull { it.same(m) } }
    }

    /* ---------- ритъм ---------- */
    private fun updateRhythm() {
        var forced: IntArray? = null
        val mo = meterOverride
        if (mo >= 0) {
            val m = Theory.METERS[mo]
            if (m.groups == null) { setGrid(false); return }
            forced = m.groups
        }
        val nowA = analyzer.timeSec
        rhythm.update(analyzer.onsetsCopy(), nowA, forced)
        if (!rhythm.locked) { if (grid) setGrid(false); return }

        val l = rhythm.groups.sum()
        val us = rhythm.unit * sr
        val comp = (if (source == SourceKind.MIC) outLatencySec + 0.012 else 0.0) + 0.01   // началото на тона се засича леко закъсняло
        var (tA, idx) = rhythm.nextUnitAfter(nowA + 0.03)
        var target = (tA + clockOffset - comp) * sr
        while (target < pos) { target += us; idx = (idx + 1) % l }

        val changed = gridGroups == null || !gridGroups!!.contentEquals(rhythm.groups)
        if (!grid || changed) {
            gridGroups = rhythm.groups; pattern = Theory.buildPattern(rhythm.groups)
            nextUnit = target; unitIdx = idx; unitSamples = us
            setGrid(true)
            return
        }
        unitSamples = us
        val k = Math.round((target - nextUnit) / us)
        val err = target - (nextUnit + k * us)
        if (kotlin.math.abs(err) > 0.35 * us) { nextUnit = target; unitIdx = idx }
        else {
            nextUnit += 0.5 * err      // плавно догонване на гайдаря
            val expect = (((unitIdx + k) % l + l) % l).toInt()
            if (expect != idx) unitIdx = (((idx - k) % l + l) % l).toInt()
        }
    }

    private fun onUnit() {
        val p = pattern[unitIdx]
        val c = current; val ud = unitSamples / sr
        voices += if (p.drum == 't') StickVoice(sr) else BumVoice(sr, p.drum == 'B')
        if (p.bass != ' ') {
            val m = if (p.bass == 'R') Theory.fitRange(c.root, 36, 47) else Theory.fitRange((c.root + 7) % 12, 31, 46)
            voices += BassVoice(sr, m, ud * 1.8)
        }
        if (p.chord) voices += StabVoice(sr, Theory.voiceChord(c), if (p.groupStart) 1.0 else 0.85)
        currentUnit = unitIdx
        onBeat?.invoke(pos, unitIdx)
        unitIdx = (unitIdx + 1) % pattern.size
    }

    private fun control() {
        if (pos >= nextKey) {
            nextKey = pos + sr / 4
            if (key.update(analyzer.longCopy(), analyzer.dronePc)) onKeyChanged()
        }
        if (pos >= nextRhythm) { nextRhythm = pos + sr / 2; updateRhythm() }
        if (pad != null && analyzer.silentSec > 0.5) releasePad(0.25)   // гайдата спря
        updateHarmony()
    }

    /* ---------- демо: ръченица 7/8 в Ре миксолидийски, с орнаменти ---------- */
    private class DemoEv(val t: Double, val midi: Int, val dur: Double, val main: Boolean)
    private var demoEv: List<DemoEv>? = null
    private var demoIdx = 0; private var demoT0 = 0.0; private var demoLoop = 0.0

    private fun startDemo() {
        if (demoEv == null) {
            val bars = arrayOf(
                arrayOf(4 to 2, 3 to 2, 2 to 3), arrayOf(3 to 1, 4 to 1, 3 to 2, 2 to 3),
                arrayOf(1 to 2, 2 to 2, 0 to 3), arrayOf(1 to 1, 2 to 1, 1 to 2, 0 to 3),
                arrayOf(2 to 2, 3 to 2, 4 to 3), arrayOf(5 to 1, 4 to 1, 3 to 2, 4 to 3),
                arrayOf(6 to 2, 5 to 2, 4 to 3), arrayOf(3 to 1, 4 to 1, 3 to 2, 2 to 3),
                arrayOf(1 to 2, -1 to 2, 0 to 3), arrayOf(0 to 2, 1 to 2, 0 to 3),
            )
            val sc = Theory.MODES[0].steps; val b = Theory.fitRange(DEMO_TONIC, 60, 71)
            val ev = ArrayList<DemoEv>(); var u = 0; var n = 0
            for (bar in bars) for ((deg, len) in bar) {
                val midi = if (deg < 0) b + sc[7 + deg] - 12 else b + sc[deg % 7]
                val t = u * DEMO_UNIT; val dur = len * DEMO_UNIT
                if (len >= 2 && (n++ % 2 == 0)) {
                    // орнамент: горна съседна за 30 ms на удара
                    val g = if (deg + 1 >= 7) b + 12 else b + sc[maxOf(0, deg + 1)]
                    ev += DemoEv(t, g, 0.03, false); ev += DemoEv(t + 0.03, midi, dur - 0.03, true)
                } else ev += DemoEv(t, midi, dur, true)
                u += len
            }
            demoEv = ev; demoLoop = u * DEMO_UNIT
        }
        drone?.stop = true
        drone = DroneVoice(sr, Theory.fitRange(DEMO_TONIC, 60, 71) - 24).also { voices += it }
        demoIdx = 0; demoT0 = pos + 0.3 * sr; demoOrigin = demoT0
    }

    private fun demoTick() {
        val ev = demoEv ?: return
        val e = ev[demoIdx]
        if (pos < demoT0 + e.t * sr) return
        voices += DemoNoteVoice(sr, e.midi, e.dur)
        if (e.main) onDemoNote?.invoke(pos, ((e.midi % 12) + 12) % 12)
        if (++demoIdx >= ev.size) { demoIdx = 0; demoT0 += demoLoop * sr }
    }

    /** Пълни [out] с моно семпли при честота [sr]. Вика се от аудио нишката. */
    fun read(out: FloatArray, count: Int) = synchronized(sync) {
        if (anBuf.size < count) anBuf = FloatArray(count)
        val fd = fileData
        val fileOn = running && source == SourceKind.FILE && fd != null
        if (running) {
            val est = pos.toDouble() / sr - analyzer.timeSec
            if (source != SourceKind.MIC || !offsetInit) { clockOffset = est; offsetInit = true }
            else clockOffset = 0.98 * clockOffset + 0.02 * est
        }
        for (i in 0 until count) {
            if (running) {
                if ((i and 63) == 0) control()
                if (source == SourceKind.DEMO) demoTick()
                if (grid && pos >= nextUnit) { onUnit(); nextUnit += unitSamples }
            }
            var ch = 0.0; var ba = 0.0; var dr = 0.0; var src = 0.0; var an = 0.0
            for (v in voices) {
                val s = v.next()
                when (v.bus) { Bus.CHORD -> ch += s; Bus.BASS -> ba += s; Bus.DRUM -> dr += s; Bus.SRC -> src += s }
                if (v.toAnalyzer) an += s
            }
            if (fileOn && filePos < fd!!.size) { val f = fd[filePos++].toDouble(); src += f; an += f }
            anBuf[i] = an.toFloat()
            out[i] = tanh((ch * volChord + ba * volBass + dr * volDrum + src * volSrc) * 0.9).toFloat()
            pos++
        }
        voices.removeAll { it.done }
        if (running && source != SourceKind.MIC) analyzer.process(anBuf, 0, count)
    }

    companion object {
        private const val DEMO_TONIC = 2
        private const val DEMO_UNIT = 0.2
    }
}
