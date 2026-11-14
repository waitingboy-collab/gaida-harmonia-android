package bg.rado.gaidaharmonia

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bg.rado.gaidaharmonia.audio.AudioOut
import bg.rado.gaidaharmonia.audio.FileDecoder
import bg.rado.gaidaharmonia.audio.MicIn
import bg.rado.gaidaharmonia.audio.NativeAudio
import bg.rado.gaidaharmonia.engine.Analyzer
import bg.rado.gaidaharmonia.engine.Chord
import bg.rado.gaidaharmonia.engine.Engine
import bg.rado.gaidaharmonia.engine.LiveNote
import bg.rado.gaidaharmonia.engine.SourceKind
import bg.rado.gaidaharmonia.engine.Theory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GaidaViewModel(app: Application) : AndroidViewModel(app) {
    private val nativeSr = NativeAudio.sampleRate(app)
    val analyzer = Analyzer()
    val engine = Engine(analyzer).also { it.configure(nativeSr) }
    private val out = AudioOut(engine, NativeAudio.framesPerBuffer(app))
    private val mic = MicIn(app, analyzer, nativeSr)

    // избори на потребителя (само най-необходимото)
    var running by mutableStateOf(false); private set
    var source by mutableStateOf(SourceKind.MIC); private set
    var meterChoice by mutableIntStateOf(-1); private set    // -1 = авто
    var volChord by mutableFloatStateOf(0.55f); private set
    var volBass by mutableFloatStateOf(0.7f); private set
    var volDrum by mutableFloatStateOf(0.7f); private set
    var volSrc by mutableFloatStateOf(0.8f); private set

    var status by mutableStateOf("Натисни Старт и засвири."); private set
    var statusErr by mutableStateOf(false); private set
    var fileName by mutableStateOf<String?>(null); private set
    var decoding by mutableIntStateOf(-1); private set

    // засечено автоматично (обновява се от poll())
    var chord by mutableStateOf(engine.current); private set
    var auto by mutableStateOf(true); private set
    var live by mutableStateOf<LiveNote?>(null); private set
    var rms by mutableFloatStateOf(0f); private set
    var weights by mutableStateOf(FloatArray(12)); private set
    var unit by mutableIntStateOf(-1); private set
    var tonic by mutableIntStateOf(engine.tonic); private set
    var modeIdx by mutableIntStateOf(0); private set
    var keyKnown by mutableStateOf(false); private set
    var groups by mutableStateOf<IntArray?>(null); private set
    var bpm by mutableIntStateOf(0); private set
    var reactionMs by mutableIntStateOf(-1); private set
    var candidates by mutableStateOf(engine.candidates()); private set

    private fun say(t: String, err: Boolean = false) { status = t; statusErr = err }

    fun start(micGranted: Boolean) {
        if (running) return
        if (source == SourceKind.MIC && !micGranted) { say("Няма разрешение за микрофона. Разреши го от Настройки → Приложения.", true); return }
        engine.start()
        out.start()
        if (source == SourceKind.MIC && !mic.start()) {
            stop(); say("Не мога да отворя микрофона. Затвори други приложения, които го ползват.", true); return
        }
        running = true
        say(when (source) {
            SourceKind.MIC -> "Слушам. Тоналността и ритъмът се засичат за няколко секунди."
            SourceKind.FILE -> if (engine.hasFile) "Пускам файла." else "Избери аудио файл."
            SourceKind.DEMO -> "Демо: синтезирана гайда минава през същото разпознаване."
        })
    }

    fun stop() {
        engine.stop(); mic.stop(); out.stop()
        running = false; unit = -1
        say("Спряно.")
    }

    fun chooseSource(s: SourceKind, micGranted: Boolean) {
        if (s == source) return
        source = s
        if (s != SourceKind.MIC) mic.stop()
        engine.setSource(s)
        if (running && s == SourceKind.MIC) {
            if (!micGranted || !mic.start()) say("Няма достъп до микрофона.", true) else say("Слушам микрофона.")
        }
    }

    fun loadFile(uri: Uri) {
        val ctx = getApplication<Application>()
        fileName = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "аудио файл"
        decoding = 0
        say("Зареждам „$fileName“…")
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.Default) {
                    FileDecoder.decode(ctx, uri, engine.sr) { p -> viewModelScope.launch { decoding = p } }
                }
                engine.setFile(data)
                say(if (running) "Пускам „$fileName“." else "Файлът е зареден (${data.size / engine.sr} сек). Натисни Старт.")
            } catch (e: Exception) {
                fileName = null; engine.setFile(null)
                say("Не мога да прочета файла: ${e.message ?: e.javaClass.simpleName}", true)
            } finally { decoding = -1 }
        }
    }

    fun chooseMeter(i: Int) { meterChoice = i; engine.meterOverride = i }

    fun chooseAuto(on: Boolean) { auto = on; engine.followMelody(on) }
    fun forceChord(c: Chord) { engine.forceChord(c); auto = false }

    fun changeVol(which: Int, v: Float) {
        when (which) {
            0 -> { volChord = v; engine.volChord = v }
            1 -> { volBass = v; engine.volBass = v }
            2 -> { volDrum = v; engine.volDrum = v }
            else -> { volSrc = v; engine.volSrc = v }
        }
    }

    /** Вика се ~60 пъти в секунда от UI. */
    fun poll() {
        chord = engine.current
        auto = engine.auto
        live = if (running) analyzer.live else null
        rms = if (running) analyzer.rms else 0f
        weights = analyzer.shortCopy()
        unit = engine.currentUnit
        if (engine.tonic != tonic || engine.key.modeIdx != modeIdx) {
            tonic = engine.tonic; modeIdx = engine.key.modeIdx; candidates = engine.candidates()
        }
        keyKnown = engine.keyKnown
        val g = engine.groups
        if (!(g contentEquals groups)) groups = g
        bpm = if (g != null) Math.round(30 / engine.unitSec).toInt() else 0
        reactionMs = engine.lastReactionMs
    }

    override fun onCleared() { engine.stop(); mic.stop(); out.stop() }
}
