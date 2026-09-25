package ai.wakey.android.tasks

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Stores tasks as JSON in app-private storage. Writes happen in order on one background thread and
 * replace the file atomically, so a crash mid-write keeps the previous version.
 */
class FileTaskStore(private val file: File) : TaskStore {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "wakey-tasks") }

    override fun load(): StoredTasks = try {
        if (file.isFile) TaskJson.decode(file.readText()) else StoredTasks()
    } catch (e: IOException) {
        Log.w(TAG, "Could not read saved tasks", e)
        StoredTasks()
    }

    override fun save(tasks: List<WakeyTask>, nextId: Long) {
        writer.execute {
            val json = TaskJson.encode(tasks, nextId)
            val partial = File(file.path + ".tmp")
            try {
                partial.writeText(json)
                if (!partial.renameTo(file)) Log.w(TAG, "Could not replace the saved tasks")
            } catch (e: IOException) {
                Log.w(TAG, "Could not save tasks", e)
            }
        }
    }

    private companion object {
        const val TAG = "WakeyTasks"
    }
}

/** The stored form of [WakeyTask]s. Unknown or broken entries are skipped rather than failing the load. */
internal object TaskJson {
    private const val VERSION = 1

    fun encode(tasks: List<WakeyTask>, nextId: Long): String {
        val items = JSONArray()
        for (task in tasks) {
            items.put(
                JSONObject()
                    .put("id", task.id)
                    .put("text", task.text)
                    .put("kind", task.kind.name)
                    .put("status", task.status.name)
                    .put("created", task.createdAtMs)
                    .putOpt("due", task.dueAtMs)
                    .putOpt("started", task.startedAtMs)
                    .putOpt("finished", task.finishedAtMs)
                    .putOpt("result", task.result)
                    .put("deferred", task.deferred),
            )
        }
        return JSONObject().put("version", VERSION).put("nextId", nextId).put("tasks", items).toString()
    }

    fun decode(json: String): StoredTasks {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return StoredTasks()
        }
        val items = root.optJSONArray("tasks") ?: return StoredTasks(nextId = root.optLong("nextId", 1))
        val tasks = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val kind = TaskKind.entries.firstOrNull { it.name == item.optString("kind") } ?: return@mapNotNull null
            val status = TaskStatus.entries.firstOrNull { it.name == item.optString("status") } ?: return@mapNotNull null
            if (!item.has("id") || !item.has("text")) return@mapNotNull null
            WakeyTask(
                id = item.optLong("id"),
                text = item.optString("text"),
                kind = kind,
                status = status,
                createdAtMs = item.optLong("created"),
                dueAtMs = item.optLongOrNull("due"),
                startedAtMs = item.optLongOrNull("started"),
                finishedAtMs = item.optLongOrNull("finished"),
                result = item.optStringOrNull("result"),
                deferred = item.optBoolean("deferred"),
            )
        }
        return StoredTasks(tasks, root.optLong("nextId", 1))
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
}
