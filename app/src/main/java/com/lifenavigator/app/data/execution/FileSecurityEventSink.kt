package com.lifenavigator.app.data.execution

import android.content.Context
import org.json.JSONObject
import java.io.FileOutputStream

/**
 * Append-only security event journal in the app-private files directory.
 *
 * The sink exposes no update/delete operation. Each line is committed and
 * fsynced before append() reports success. Retention, export and tamper-evidence
 * are separate policy decisions and are not silently invented here.
 */
class FileSecurityEventSink(context: Context) : SecurityEventSink {

    private val appContext = context.applicationContext

    @Synchronized
    override fun append(event: SecurityEvent): Boolean {
        return try {
            val line = JSONObject()
                .put("eventId", event.eventId)
                .put("operationId", event.operationId.value)
                .put("sessionId", event.sessionId.value)
                .put("actionId", event.actionId.value)
                .put("category", event.category.name)
                .put("decisionCode", event.decisionCode)
                .put("occurredAtEpochMillis", event.occurredAtEpochMillis)
                .put("detail", event.detail)
                .toString() + "\n"

            FileOutputStream(appContext.getFileStreamPath(FILE_NAME), true).use { output ->
                output.write(line.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val FILE_NAME = "security_events_v1.jsonl"
    }
}
