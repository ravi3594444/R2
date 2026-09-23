package ai.wakey.android.tts

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Live check against Deepgram, skipped unless `DEEPGRAM_API_KEY` is set (two short requests). Uses the app's request
 * builders, reads each stream in odd-sized pieces through [Pcm16Decoder], and prints time to first byte, total time
 * and the pre-buffer playback would have needed to never underrun. With `WAKEY_AUDIO_OUT` set to a directory, each
 * reply is also saved there as a 24 kHz WAV.
 */
class DeepgramLiveTest {
    private class Result(
        val name: String,
        val samples: ShortArray,
        val contentType: String,
        val firstBytes: ByteArray,
        val headersMs: Long,
        val firstByteMs: Long,
        val totalMs: Long,
        val prebufferMs: Int,
    ) {
        val seconds get() = samples.size / DeepgramTtsApi.SAMPLE_RATE.toDouble()
        val rms get() = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
    }

    @Test
    fun meenaSpeaksRawPcmFromV2() {
        val key = System.getenv("DEEPGRAM_API_KEY").orEmpty()
        assumeTrue("DEEPGRAM_API_KEY not set", key.isNotBlank())
        val client = OkHttpClient()
        val flashlight = fetch(client, key, "meena-flashlight-on", "Flashlight is on.")
        val hinglish = fetch(client, key, "meena-hinglish-calculator", "Theek hai, calculator khol raha hoon.")
        val outDir = System.getenv("WAKEY_AUDIO_OUT")?.let(::File)
        for (r in listOf(flashlight, hinglish)) {
            println(
                "${r.name}: ${r.contentType}, %.2f s audio, rms %.0f, headers %d ms, first byte %d ms, total %d ms, "
                    .format(r.seconds, r.rms, r.headersMs, r.firstByteMs, r.totalMs) +
                    "underrun-free start needs ${r.prebufferMs} ms buffered",
            )
            assertTrue(r.contentType, r.contentType.startsWith("audio/l16"))
            assertTrue(r.contentType, "rate=24000" in r.contentType)
            assertNotEquals("no WAV header", "RIFF", String(r.firstBytes, Charsets.US_ASCII))
            assertTrue("not silent", r.rms > 300)
            outDir?.let { writeWav(File(it, "${r.name}-24k.wav"), r.samples) }
        }
        assertTrue("%.2f s".format(flashlight.seconds), flashlight.seconds in 0.6..4.0)
        assertTrue("%.2f s".format(hinglish.seconds), hinglish.seconds in 1.2..6.0)
    }

    private fun fetch(client: OkHttpClient, key: String, name: String, text: String): Result {
        val request = Request.Builder()
            .url(DeepgramTtsApi.speakUrl("flux-meena-en", speed = null))
            .header("Authorization", "Token $key")
            .post(DeepgramTtsApi.requestBody(text))
            .build()
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
        client.newCall(request).execute().use { response ->
            val headersMs = elapsedMs()
            assertEquals("HTTP ${response.code}: ${response.peekBody(512).string()}", 200, response.code)
            val source = response.body!!.source()
            val decoder = Pcm16Decoder()
            val samples = mutableListOf<Short>()
            val arrivals = mutableListOf<Pair<Long, Int>>() // (ms, samples so far)
            val firstBytes = ByteArray(4)
            var firstByteMs = -1L
            var total = 0
            val readSizes = intArrayOf(1_001, 777, 4_097)
            val buffer = ByteArray(readSizes.max())
            val out = ShortArray(buffer.size / 2 + 1)
            var reads = 0
            while (true) {
                val read = source.read(buffer, 0, readSizes[reads++ % readSizes.size])
                if (read < 0) break
                if (firstByteMs < 0) firstByteMs = elapsedMs()
                for (i in 0 until minOf(read, 4 - total).coerceAtLeast(0)) firstBytes[total + i] = buffer[i]
                total += read
                val count = decoder.decode(buffer, read, out)
                for (i in 0 until count) samples += out[i]
                arrivals += elapsedMs() to samples.size
            }
            assertEquals("whole samples", 0, total % 2)
            return Result(
                name, samples.toShortArray(), response.header("Content-Type").orEmpty(), firstBytes,
                headersMs, firstByteMs, elapsedMs(), prebufferMs(arrivals),
            )
        }
    }

    /** Smallest start buffer (10 ms steps) for which real-time playback never overtakes the download. */
    private fun prebufferMs(arrivals: List<Pair<Long, Int>>): Int {
        val perMs = DeepgramTtsApi.SAMPLE_RATE / 1000.0
        val total = arrivals.last().second
        return (0..5_000 step 10).first { bufferMs ->
            val threshold = minOf((bufferMs * perMs).toInt(), total)
            val startMs = arrivals.first { it.second >= threshold }.first
            // Before each arrival, playback must not have consumed more than had arrived by the previous one.
            arrivals.zipWithNext().all { (previous, next) ->
                next.first <= startMs || (next.first - startMs) * perMs <= previous.second || previous.second >= total
            }
        }
    }

    private fun writeWav(file: File, samples: ShortArray) {
        file.parentFile?.mkdirs()
        val dataBytes = samples.size * 2
        val rate = DeepgramTtsApi.SAMPLE_RATE
        val wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        wav.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(dataBytes)
        samples.forEach { wav.putShort(it) }
        file.writeBytes(wav.array())
    }
}
