package ai.wakey.android.wake

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterResult
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.IOException

/** sherpa-onnx scoring for one keyword line (`:boost #threshold`). */
data class KeywordScoring(val boost: Float, val threshold: Float)

/**
 * On-device wake-word spotting with the sherpa-onnx zipformer KWS model bundled under `kws/`.
 *
 * The model is loaded once in [load]. [setKeyword] only creates a new decoding stream with a new
 * keyword line, which takes microseconds, so changing the wake phrase never reloads the model.
 * Not thread-safe: use it from a single thread after [load].
 */
class SherpaWakeWordDetector private constructor(private val spotter: KeywordSpotter) : WakeWordDetector {
    private var keyword: EncodedKeyword? = null
    private var keywordLine = ""
    private var stream: OnlineStream? = null
    private var decodedChunks = 0L
    private var samplesAccepted = 0L
    private var floats = FloatArray(0)

    override fun setKeyword(keyword: EncodedKeyword, sensitivity: Float) {
        val scoring = scoringFor(sensitivity)
        this.keyword = keyword
        keywordLine = keyword.toSherpaKeywords(scoring.boost, scoring.threshold)
        restart()
    }

    override fun restart() {
        stream?.release()
        stream = null
        decodedChunks = 0
        samplesAccepted = 0
        val phrase = keyword?.phrase ?: return
        val next = spotter.createStream(keywordLine)
        // A null native stream means sherpa rejected the line; decoding it would crash the process.
        check(next.ptr != 0L) { "The wake-word detector could not use “$phrase”." }
        stream = next
    }

    override fun accept(samples: ShortArray, count: Int): WakeDetection? {
        val current = stream ?: return null
        if (floats.size != count) floats = FloatArray(count)
        for (i in 0 until count) floats[i] = samples[i] / 32_768f
        current.acceptWaveform(floats, SAMPLE_RATE)
        samplesAccepted += count
        var detection: WakeDetection? = null
        while (spotter.isReady(current)) {
            spotter.decode(current)
            decodedChunks++
            val result = spotter.getResult(current)
            if (result.keyword.isEmpty()) continue
            // Required after every detection, otherwise the same keyword is reported again.
            spotter.reset(current)
            if (detection == null) detection = toDetection(result)
        }
        return detection
    }

    override fun close() {
        stream?.release()
        stream = null
        spotter.release()
    }

    private fun toDetection(result: KeywordSpotterResult): WakeDetection {
        val span = KeywordTiming.locate(result.timestamps, decodedChunks, TRAILING_BLANKS)
            ?.takeIf { it.endSample <= samplesAccepted }
        return WakeDetection(
            phrase = reportedPhrase(result.keyword, keyword),
            keywordStartLag = span?.let { samplesAccepted - it.startSample },
            keywordEndLag = span?.let { samplesAccepted - it.endSample },
        )
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Blank frames that must follow a keyword before it fires (sherpa's `numTrailingBlanks`). */
        private const val TRAILING_BLANKS = 1

        private const val MODEL_DIR = KeywordEncoder.ASSET_DIR
        private const val ENCODER = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS = "$MODEL_DIR/tokens.txt"

        /**
         * Deliberately blank. sherpa requires `keywordsFile` to exist (a missing asset terminates the
         * process) and merges its keywords into every stream's own list, so any phrase in it would
         * keep triggering after the user changes the wake phrase.
         */
        private const val KEYWORDS_FILE = "wake/empty_keywords.txt"

        /** The user's phrase for a detected [tag]; every pronunciation variant carries the phrase's tag. */
        internal fun reportedPhrase(tag: String, keyword: EncodedKeyword?): String =
            if (keyword != null && tag == keyword.tag) keyword.phrase else tag.replace('_', ' ')

        /**
         * Maps the user's sensitivity (0 = fewest false wakes, 1 = most eager) to keyword scoring.
         * Chosen on desktop with the bundled model: detection saturated from threshold 0.25 down on
         * clean TTS speech, lower thresholds helped only in noise, and no setting in the range
         * produced a false alarm on the negative set. Default 0.5 gives boost 1.5, threshold 0.18.
         */
        fun scoringFor(sensitivity: Float): KeywordScoring {
            val s = if (sensitivity.isNaN()) 0.5f else sensitivity.coerceIn(0f, 1f)
            return KeywordScoring(boost = 1f + s, threshold = 0.30f - 0.24f * s)
        }

        /**
         * Loads the KWS model from the APK assets. Takes a few hundred milliseconds.
         * @throws IllegalStateException with a readable message if the model can't be loaded.
         */
        fun load(assets: AssetManager, numThreads: Int = 1): SherpaWakeWordDetector {
            // sherpa-onnx terminates the process when an asset is missing, so check first.
            for (path in listOf(ENCODER, DECODER, JOINER, TOKENS, KEYWORDS_FILE)) {
                try {
                    assets.open(path).close()
                } catch (e: IOException) {
                    throw IllegalStateException("The wake-word model is missing from this build of Wakey ($path).", e)
                }
            }
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = ENCODER, decoder = DECODER, joiner = JOINER),
                    tokens = TOKENS,
                    numThreads = numThreads,
                    provider = "cpu",
                    modelType = "zipformer2",
                ),
                maxActivePaths = 4,
                keywordsFile = KEYWORDS_FILE,
                numTrailingBlanks = TRAILING_BLANKS,
            )
            return try {
                SherpaWakeWordDetector(KeywordSpotter(assets, config))
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Could not load the wake-word model.", e)
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException("The wake-word engine does not support this device's processor.", e)
            }
        }
    }
}
