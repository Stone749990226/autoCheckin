package com.personal.autocheckin

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

object RunLog {
    @Synchronized
    fun write(context: Context, runId: String?, event: String, detail: String = ""): Boolean = try {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val oldest = LocalDate.now().minusDays(29).toString()
        dir.listFiles()?.filter { it.name.endsWith(".jsonl") && it.name.substringBefore('.') < oldest }
            ?.forEach { it.delete() }
        val record = JSONObject().put("time", ZonedDateTime.now().toString())
            .put("runId", runId ?: "-").put("event", event).put("detail", detail.take(300))
        File(dir, "${LocalDate.now()}.jsonl").appendText("$record\n", Charsets.UTF_8)
        true
    } catch (e: Exception) {
        Log.e("AutoCheckin", "Cannot write local log", e)
        false
    }

    @Synchronized
    fun recent(context: Context): String = try {
        File(context.filesDir, "logs").listFiles()?.filter { it.extension == "jsonl" }
            ?.sortedByDescending { it.name }?.asSequence()
            ?.flatMap { it.readLines(Charsets.UTF_8).takeLast(500).asReversed().asSequence() }
            ?.take(500)?.joinToString("\n\n") { line ->
                try {
                    val item = JSONObject(line)
                    "${item.getString("time")}  [${item.getString("event")}]\n${item.getString("detail")}\n运行：${item.getString("runId")}"
                } catch (_: Exception) { "[日志行损坏]" }
            }?.ifEmpty { "暂无日志" } ?: "暂无日志"
    } catch (e: Exception) { "无法读取日志：${e.javaClass.simpleName}" }
}
