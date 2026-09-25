package ai.wakey.android.wake

import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * openWakeWord's ready-made "Hey Jarvis" model (github.com/dscripka/openWakeWord), run with LiteRT.
 * A model trained for one phrase catches it far more reliably than spotting an arbitrary phrase:
 * on synthetic clips (5 voices, speech from -50 to -26 dBFS over room noise) it found 46 to 51 of 51
 * "Hey Jarvis" in every condition, where the phrase spotter found 16 of 51 quiet "Hey Wakey". The
 * wake word model is licensed CC BY-NC-SA 4.0: non-commercial use only.
 *
 * Scores from [JARVIS_CHECK_FLOOR] up to the threshold are borderline, so they are reported as
 * needing a check: speech recognition confirms "Jarvis" was said first. About 2% of everyday
 * sentences scored there on the same clips; only near-homophones like "hey Travis" scored higher.
 *
 * Not thread-safe: use it from the audio thread after [load].
 */
class OpenWakeWordDetector internal constructor(private val pipeline: OwwPipeline) : WakeWordDetector {
    private var sure = thresholdFor(DEFAULT_SENSITIVITY)
    private var check = checkThresholdFor(DEFAULT_SENSITIVITY)

    /** The highest borderline score of the current rise, reported once the score falls again. */
    private var peak = 0f

    override fun setKeyword(keyword: EncodedKeyword, sensitivity: Float) {
        sure = thresholdFor(sensitivity)
        check = checkThresholdFor(sensitivity)
        restart()
    }

    override fun restart() {
        pipeline.reset()
        peak = 0f
    }

    override fun accept(samples: ShortArray, count: Int): WakeDetection? {
        var detection: WakeDetection? = null
        pipeline.accept(samples, count) { score -> if (detection == null) detection = judge(score) }
        return detection
    }

    private fun judge(score: Float): WakeDetection? = when {
        score >= sure -> detection(needsCheck = false)
        score >= check && score >= peak -> {
            peak = score
            null
        }
        // A borderline rise has peaked.
        peak > 0f -> detection(needsCheck = true)
        else -> null
    }

    private fun detection(needsCheck: Boolean): WakeDetection {
        peak = 0f
        // The model gives no word timings; the listener falls back to its full pre-roll.
        return WakeDetection(PHRASE, keywordStartLag = null, keywordEndLag = null, needsCheck = needsCheck)
    }

    override fun close() = pipeline.close()

    companion object {
        const val PHRASE = "HEY JARVIS"

        /** What the audio engine hands this detector in place of an encoded phrase. */
        val KEYWORD = EncodedKeyword(PHRASE, emptyList())

        private const val DEFAULT_SENSITIVITY = 0.5f

        /** Borderline scores below this are ignored at every sensitivity (0.3 would catch 7% of everyday sentences). */
        const val JARVIS_CHECK_FLOOR = 0.4f

        /** openWakeWord's recommended 0.5 at the middle sensitivity, 0.3 to 0.7 across the slider. */
        fun thresholdFor(sensitivity: Float): Float {
            val s = if (sensitivity.isNaN()) DEFAULT_SENSITIVITY else sensitivity.coerceIn(0f, 1f)
            return 0.7f - 0.4f * s
        }

        /** Borderline scores, confirmed by speech recognition before Wakey responds. */
        fun checkThresholdFor(sensitivity: Float): Float = minOf(JARVIS_CHECK_FLOOR, thresholdFor(sensitivity))

        /**
         * Loads the three models from the APK assets. Takes a few hundred milliseconds.
         * @throws IllegalStateException with a readable message if they can't be loaded.
         */
        fun load(assets: AssetManager): OpenWakeWordDetector = try {
            OpenWakeWordDetector(OwwPipeline(LiteRtOwwModels(assets)))
        } catch (e: IOException) {
            throw IllegalStateException("The “Hey Jarvis” model is missing from this build of Wakey.", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Could not load the “Hey Jarvis” model.", e)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("The “Hey Jarvis” engine does not support this device's processor.", e)
        }
    }
}

/** The three openWakeWord models, so the streaming logic can be tested without LiteRT. */
internal interface OwwModels : AutoCloseable {
    /** Mel frames for int16-valued [samples]: one row of [OwwPipeline.MEL_BINS] per 10 ms, scaled as openWakeWord does. */
    fun melspectrogram(samples: FloatArray, length: Int): FloatArray

    /** One embedding of [OwwPipeline.EMBEDDING] numbers from [OwwPipeline.WINDOW] mel frames. */
    fun embedding(window: FloatArray): FloatArray

    /** The wake word score, 0..1, from the last [OwwPipeline.FEATURES] embeddings. */
    fun score(features: FloatArray): Float

    override fun close()
}

/**
 * openWakeWord 0.6's streaming feature pipeline, step for step (checked against the Python library
 * on the same audio: identical scores). Every [STEP] samples (80 ms), the last [STEP] + [CONTEXT]
 * samples become mel frames, the last [WINDOW] frames one embedding, and the last [FEATURES]
 * embeddings (1.28 s) a score. The first [SKIPPED] scores after a reset are 0, as in openWakeWord.
 */
internal class OwwPipeline(private val models: OwwModels, seed: Int = 0) : AutoCloseable {
    private val pending = FloatArray(STEP)
    private var pendingCount = 0
    private val raw = FloatArray(STEP + CONTEXT)
    private var rawCount = 0
    private val mel = FloatArray(WINDOW * MEL_BINS)
    private val features = FloatArray(FEATURES * EMBEDDING)
    private var steps = 0

    /** openWakeWord starts from embeddings of 4 s of faint noise; computed once and reused on every reset. */
    private val initialFeatures: FloatArray = run {
        val random = Random(seed)
        val noise = FloatArray(NOISE_SAMPLES) { random.nextInt(-1_000, 1_000).toFloat() }
        val frames = models.melspectrogram(noise, noise.size)
        val count = frames.size / MEL_BINS
        val starts = (0 until count step MEL_STRIDE).filter { it + WINDOW <= count }.takeLast(FEATURES)
        FloatArray(FEATURES * EMBEDDING).also { out ->
            starts.forEachIndexed { i, start ->
                models.embedding(frames.copyOfRange(start * MEL_BINS, (start + WINDOW) * MEL_BINS)).copyInto(out, i * EMBEDDING)
            }
        }
    }

    init {
        reset()
    }

    fun reset() {
        pendingCount = 0
        rawCount = 0
        mel.fill(1f)
        initialFeatures.copyInto(features)
        steps = 0
    }

    /** Feeds 16 kHz PCM; [onScore] receives one score per completed step. */
    fun accept(samples: ShortArray, count: Int, onScore: (Float) -> Unit) {
        var i = 0
        while (i < count) {
            val take = minOf(STEP - pendingCount, count - i)
            for (k in 0 until take) pending[pendingCount + k] = samples[i + k].toFloat()
            pendingCount += take
            i += take
            if (pendingCount == STEP) {
                pendingCount = 0
                onScore(step())
            }
        }
    }

    private fun step(): Float {
        // The last STEP + CONTEXT samples; fewer only on the first step after a reset.
        if (rawCount + STEP > raw.size) {
            val keep = raw.size - STEP
            raw.copyInto(raw, 0, rawCount - keep, rawCount)
            rawCount = keep
        }
        pending.copyInto(raw, rawCount)
        rawCount += STEP
        val frames = models.melspectrogram(raw, rawCount)
        shiftIn(mel, frames)
        shiftIn(features, models.embedding(mel))
        val score = models.score(features)
        steps++
        return if (steps <= SKIPPED) 0f else score
    }

    /** Drops the oldest rows of [buffer] and appends [rows] at the end. */
    private fun shiftIn(buffer: FloatArray, rows: FloatArray) {
        val n = minOf(rows.size, buffer.size)
        buffer.copyInto(buffer, 0, n, buffer.size)
        rows.copyInto(buffer, buffer.size - n, rows.size - n, rows.size)
    }

    override fun close() = models.close()

    companion object {
        const val STEP = 1_280
        const val CONTEXT = 480
        const val MEL_BINS = 32
        const val MEL_STRIDE = 8
        const val WINDOW = 76
        const val EMBEDDING = 96
        const val FEATURES = 16
        const val SKIPPED = 5
        const val NOISE_SAMPLES = 16_000 * 4
    }
}

/** The models in LiteRT (TensorFlow Lite) interpreters, read from the APK's `oww/` assets. */
private class LiteRtOwwModels(assets: AssetManager) : OwwModels {
    private val options = Interpreter.Options().setNumThreads(1)
    private val melModel = assets.direct("$ASSET_DIR/melspectrogram.tflite")

    /** Sized for the two input lengths a stream uses, so switching between them never reallocates. */
    private val melFirst = melInterpreter(OwwPipeline.STEP)
    private val melNext = melInterpreter(OwwPipeline.STEP + OwwPipeline.CONTEXT)
    private val embedder = Interpreter(assets.direct("$ASSET_DIR/embedding_model.tflite"), options)
    private val wakeWord = Interpreter(assets.direct("$ASSET_DIR/$WAKE_WORD_MODEL"), options)

    private val melBuffers = mutableMapOf<Int, Pair<ByteBuffer, ByteBuffer>>()
    private val embedIn = floats(OwwPipeline.WINDOW * OwwPipeline.MEL_BINS)
    private val embedOut = floats(OwwPipeline.EMBEDDING)
    private val scoreIn = floats(OwwPipeline.FEATURES * OwwPipeline.EMBEDDING)
    private val scoreOut = floats(1)

    private fun melInterpreter(length: Int) = Interpreter(melModel, options).apply {
        resizeInput(0, intArrayOf(1, length))
        allocateTensors()
    }

    override fun melspectrogram(samples: FloatArray, length: Int): FloatArray {
        val interpreter = when (length) {
            OwwPipeline.STEP -> melFirst
            OwwPipeline.STEP + OwwPipeline.CONTEXT -> melNext
            // Only for the one-off noise clip that seeds the pipeline.
            else -> Interpreter(melModel, options).apply {
                resizeInput(0, intArrayOf(1, length))
                allocateTensors()
            }
        }
        val (input, output) = melBuffers.getOrPut(length) { floats(length) to floats(interpreter.getOutputTensor(0).numElements()) }
        input.rewind()
        input.asFloatBuffer().put(samples, 0, length)
        output.rewind()
        interpreter.run(input, output)
        if (interpreter !== melFirst && interpreter !== melNext) {
            interpreter.close()
            melBuffers.remove(length)
        }
        val out = FloatArray(output.capacity() / 4)
        output.rewind()
        output.asFloatBuffer().get(out)
        // openWakeWord's transform, which brings the model's output close to Google's speech_embedding front end.
        for (i in out.indices) out[i] = out[i] / 10f + 2f
        return out
    }

    override fun embedding(window: FloatArray): FloatArray {
        embedIn.rewind()
        embedIn.asFloatBuffer().put(window)
        embedOut.rewind()
        embedder.run(embedIn, embedOut)
        return FloatArray(OwwPipeline.EMBEDDING).also { embedOut.rewind(); embedOut.asFloatBuffer().get(it) }
    }

    override fun score(features: FloatArray): Float {
        scoreIn.rewind()
        scoreIn.asFloatBuffer().put(features)
        scoreOut.rewind()
        wakeWord.run(scoreIn, scoreOut)
        scoreOut.rewind()
        return scoreOut.asFloatBuffer().get(0)
    }

    override fun close() {
        listOf(melFirst, melNext, embedder, wakeWord).forEach { it.close() }
    }

    private companion object {
        const val ASSET_DIR = "oww"
        const val WAKE_WORD_MODEL = "hey_jarvis_v0.1.tflite"

        fun floats(count: Int): ByteBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())

        /** The whole asset in a direct buffer, which LiteRT reads the model from. */
        fun AssetManager.direct(path: String): ByteBuffer {
            val bytes = open(path).use { it.readBytes() }
            return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
        }
    }
}
