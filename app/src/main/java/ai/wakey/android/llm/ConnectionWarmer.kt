package ai.wakey.android.llm

import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Opens the connection to an API host before the first real request needs it: a GET whose answer is
 * dropped leaves the TLS connection in OkHttp's pool, so the request that follows skips the
 * handshake (several round trips on a mobile network). At most once per [intervalMs], since the
 * pool keeps idle connections for minutes. Never throws.
 */
internal class ConnectionWarmer(
    private val client: OkHttpClient,
    private val intervalMs: Long = INTERVAL_MS,
    private val nowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    private val last = AtomicLong(NEVER)

    /** True, once per interval, when a warm-up should go out now. */
    internal fun due(): Boolean {
        val now = nowMs()
        val previous = last.get()
        return (previous == NEVER || now - previous >= intervalMs) && last.compareAndSet(previous, now)
    }

    fun warm(url: HttpUrl?, headers: Map<String, String>) {
        if (url == null || !due()) return
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }

    private companion object {
        const val INTERVAL_MS = 60_000L
        const val NEVER = Long.MIN_VALUE
    }
}
