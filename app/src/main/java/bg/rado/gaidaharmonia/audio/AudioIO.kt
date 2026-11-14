package bg.rado.gaidaharmonia.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Process
import bg.rado.gaidaharmonia.engine.Analyzer
import bg.rado.gaidaharmonia.engine.Engine
import java.nio.ByteOrder

/** Родната честота и размер на буфера на телефона — така звукът минава по бързия път без преобразуване. */
object NativeAudio {
    fun sampleRate(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    }
    fun framesPerBuffer(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.coerceIn(64, 1024) ?: 192
    }
}

/** Изход: нишка, която пита Engine за семпли и ги пише в AudioTrack с минимален буфер. */
class AudioOut(private val engine: Engine, private val burst: Int) {
    @Volatile private var alive = false
    private var thread: Thread? = null
    /** Приблизително забавяне на изхода в секунди. */
    @Volatile var latencySec = 0.04; private set

    fun start() {
        if (alive) return
        alive = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val sr = engine.sr
            val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, burst * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            // свиваме буфера до 2 пакета (по-малко забавяне), ако устройството позволява
            try { track.setBufferSizeInFrames(burst * 2) } catch (_: Exception) {}
            latencySec = (track.bufferSizeInFrames + burst).toDouble() / sr + 0.01
            engine.outLatencySec = latencySec
            val buf = FloatArray(burst)
            track.play()
            try {
                while (alive) {
                    engine.read(buf, buf.size)
                    track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                }
            } finally {
                track.stop(); track.release()
            }
        }, "gaida-out").also { it.start() }
    }

    fun stop() {
        alive = false
        thread?.join(500); thread = null
    }
}

/** Вход от микрофона към Analyzer. Изисква разрешение RECORD_AUDIO. */
class MicIn(private val context: Context, private val analyzer: Analyzer, private val sr: Int) {
    @Volatile private var alive = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (alive) return true
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val src = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, 2048 * 4))
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        analyzer.setSampleRate(sr)
        alive = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = FloatArray(maxOf(64, sr / 200))   // ~5 ms пакети
            rec.startRecording()
            try {
                while (alive) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) analyzer.process(buf, 0, n)
                }
            } finally {
                rec.stop(); rec.release()
            }
        }, "gaida-mic").also { it.start() }
        return true
    }

    fun stop() {
        alive = false
        thread?.join(500); thread = null
    }
}

/** Декодира аудио файл (mp3, m4a, wav, ogg…) до моно float с честотата на двигателя. */
object FileDecoder {
    private const val MAX_SECONDS = 15 * 60

    fun decode(context: Context, uri: Uri, targetSr: Int, onProgress: (Int) -> Unit): FloatArray {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        require(track >= 0) { "Файлът няма аудио" }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var out = FloatArray(srcRate * 30)
        var len = 0
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        var lastP = -1
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val frames = info.size / ((if (floatPcm) 4 else 2) * channels)
                        if (len + frames > out.size) out = out.copyOf(maxOf(out.size * 2, len + frames))
                        if (floatPcm) {
                            val fb = ob.asFloatBuffer()
                            for (f in 0 until frames) { var s = 0f; for (c in 0 until channels) s += fb.get(); out[len++] = s / channels }
                        } else {
                            val sb = ob.asShortBuffer()
                            for (f in 0 until frames) { var s = 0f; for (c in 0 until channels) s += sb.get() / 32768f; out[len++] = s / channels }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                        if (durUs > 0) {
                            val p = ((info.presentationTimeUs * 100) / durUs).toInt().coerceIn(0, 100)
                            if (p != lastP) { lastP = p; onProgress(p) }
                        }
                        if (len > srcRate.toLong() * MAX_SECONDS) outDone = true
                    }
                }
            }
        } finally {
            codec.stop(); codec.release(); ex.release()
        }
        return resample(out, len, srcRate, targetSr)
    }

    private fun resample(src: FloatArray, len: Int, from: Int, to: Int): FloatArray {
        if (from == to) return src.copyOf(len)
        val outLen = (len.toLong() * to / from).toInt()
        val dst = FloatArray(outLen)
        val ratio = from.toDouble() / to
        for (i in 0 until outLen) {
            val p = i * ratio; val k = p.toInt(); val fr = (p - k).toFloat()
            val a = src[minOf(k, len - 1)]; val b = src[minOf(k + 1, len - 1)]
            dst[i] = a + (b - a) * fr
        }
        return dst
    }
}
