package com.lifenavigator.app.data.execution

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * SharedPreferences persistence for the first local, single-process assistant
 * implementation. Every state transition uses commit() so confirmation state
 * is durable before the dispatcher proceeds.
 */
class SharedPreferencesOperationStateStore(context: Context) : OperationStateStore {

    private val appContext = context.applicationContext
    private val state: SharedPreferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun readActive(): PendingOperation? = synchronized(ACTIVE_OPERATION_LOCK) {
        state.getString(KEY_ACTIVE, null)?.let(::decode)
    }

    override fun createActiveIfAbsent(operation: PendingOperation): Boolean =
        synchronized(ACTIVE_OPERATION_LOCK) {
            if (state.contains(KEY_ACTIVE)) {
                false
            } else {
                state.edit().putString(KEY_ACTIVE, encode(operation)).commit()
            }
        }

    override fun writeActive(operation: PendingOperation): Boolean =
        synchronized(ACTIVE_OPERATION_LOCK) {
            state.edit().putString(KEY_ACTIVE, encode(operation)).commit()
        }

    override fun clearActive(): Boolean =
        synchronized(ACTIVE_OPERATION_LOCK) {
            state.edit().remove(KEY_ACTIVE).commit()
        }

    override fun readLast(): PendingOperation? =
        state.getString(KEY_LAST, null)?.let(::decode)

    override fun writeLast(operation: PendingOperation): Boolean =
        state.edit().putString(KEY_LAST, encode(operation)).commit()

    override fun readNextLegacy(): LegacyPendingImport {
        val actionRaw = appContext
            .getSharedPreferences(LEGACY_ACTION_PREFERENCES, Context.MODE_PRIVATE)
            .getString(LEGACY_PENDING_KEY, null)
        if (actionRaw != null) {
            return decodeLegacy(actionRaw, LEGACY_ACTION_PREFERENCES)
        }

        val budgetRaw = appContext
            .getSharedPreferences(LEGACY_BUDGET_PREFERENCES, Context.MODE_PRIVATE)
            .getString(LEGACY_PENDING_KEY, null)
        if (budgetRaw != null) {
            return decodeLegacy(budgetRaw, LEGACY_BUDGET_PREFERENCES)
        }

        return LegacyPendingImport.None
    }

    override fun clearLegacy(source: String): Boolean {
        val preferencesName = when (source) {
            LEGACY_ACTION_PREFERENCES -> LEGACY_ACTION_PREFERENCES
            LEGACY_BUDGET_PREFERENCES -> LEGACY_BUDGET_PREFERENCES
            else -> return false
        }
        return appContext
            .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .remove(LEGACY_PENDING_KEY)
            .commit()
    }

    private fun decodeLegacy(raw: String, source: String): LegacyPendingImport {
        return try {
            val json = JSONObject(raw)
            val type = json.optString("type")
            val parameters = linkedMapOf<String, ActionParameterValue>()
            val actionId = when {
                source == LEGACY_ACTION_PREFERENCES && type == "task" -> {
                    parameters["title"] = ActionParameterValue.Text(json.optString("title"))
                    json.optLong("dueAt", 0L).takeIf { it > 0L }?.let {
                        parameters["dueAtEpochMillis"] = ActionParameterValue.Integer(it)
                        parameters["reminderAtEpochMillis"] = ActionParameterValue.Integer(it)
                    }
                    json.optString("priority").takeIf { it.isNotBlank() }?.let {
                        parameters["priority"] = ActionParameterValue.Text(it)
                    }
                    ActionId("task.create")
                }

                source == LEGACY_ACTION_PREFERENCES && type == "calendar_event" -> {
                    parameters["title"] = ActionParameterValue.Text(json.optString("title"))
                    parameters["startsAtEpochMillis"] =
                        ActionParameterValue.Integer(json.optLong("startsAt", 0L))
                    ActionId("calendar.event.create")
                }

                source == LEGACY_BUDGET_PREFERENCES && type == "expense" -> {
                    parameters["amountRub"] =
                        ActionParameterValue.Integer(json.optInt("amount", 0).toLong())
                    parameters["categoryKey"] =
                        ActionParameterValue.Text(json.optString("category"))
                    ActionId("budget.expense.create")
                }

                source == LEGACY_BUDGET_PREFERENCES && type == "expense_delete" -> {
                    parameters["expenseId"] =
                        ActionParameterValue.Text(json.optString("expenseId"))
                    ActionId("budget.expense.delete")
                }

                source == LEGACY_BUDGET_PREFERENCES && type == "income" -> {
                    parameters["amountRub"] =
                        ActionParameterValue.Integer(json.optInt("amount", 0).toLong())
                    ActionId("budget.income.set")
                }

                source == LEGACY_BUDGET_PREFERENCES && type == "limit" -> {
                    parameters["amountRub"] =
                        ActionParameterValue.Integer(json.optInt("amount", 0).toLong())
                    parameters["categoryKey"] =
                        ActionParameterValue.Text(json.optString("category"))
                    ActionId("budget.limit.set")
                }

                else -> return LegacyPendingImport.Corrupt(source)
            }

            val now = System.currentTimeMillis()
            val operationId = OperationId.new()
            val request = ActionRequest(
                operationId = operationId,
                sessionId = SessionId(DEFAULT_SESSION_ID),
                actionId = actionId,
                contractVersion = 1,
                parameters = parameters,
                createdAtEpochMillis = now
            )
            LegacyPendingImport.Ready(
                operation = PendingOperation(
                    request = request,
                    status = OperationStatus.AWAITING_CONFIRMATION,
                    confirmationPrompt = "Найдено ранее подготовленное действие. Подтверди его или отмени.",
                    updatedAtEpochMillis = now,
                    legacySource = source
                ),
                source = source
            )
        } catch (_: Exception) {
            LegacyPendingImport.Corrupt(source)
        }
    }

    private fun encode(operation: PendingOperation): String {
        val parameters = JSONObject()
        operation.request.parameters.forEach { (name, value) ->
            val encoded = when (value) {
                is ActionParameterValue.Text ->
                    JSONObject().put("type", "TEXT").put("value", value.value)
                is ActionParameterValue.Integer ->
                    JSONObject().put("type", "INTEGER").put("value", value.value)
                is ActionParameterValue.BooleanValue ->
                    JSONObject().put("type", "BOOLEAN").put("value", value.value)
            }
            parameters.put(name, encoded)
        }

        val request = operation.request
        return JSONObject()
            .put("operationId", request.operationId.value)
            .put("sessionId", request.sessionId.value)
            .put("actionId", request.actionId.value)
            .put("contractVersion", request.contractVersion)
            .put("createdAtEpochMillis", request.createdAtEpochMillis)
            .put("idempotencyKey", request.idempotencyKey)
            .put("parameters", parameters)
            .put("status", operation.status.name)
            .put("confirmationPrompt", operation.confirmationPrompt)
            .put("updatedAtEpochMillis", operation.updatedAtEpochMillis)
            .put("legacySource", operation.legacySource)
            .put("expiresAtEpochMillis", operation.expiresAtEpochMillis)
            .toString()
    }

    private fun decode(raw: String): PendingOperation {
        val json = JSONObject(raw)
        val parameterJson = json.getJSONObject("parameters")
        val parameters = linkedMapOf<String, ActionParameterValue>()
        val keys = parameterJson.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val value = parameterJson.getJSONObject(name)
            parameters[name] = when (value.getString("type")) {
                "TEXT" -> ActionParameterValue.Text(value.getString("value"))
                "INTEGER" -> ActionParameterValue.Integer(value.getLong("value"))
                "BOOLEAN" -> ActionParameterValue.BooleanValue(value.getBoolean("value"))
                else -> error("Unsupported stored parameter type")
            }
        }

        val operationId = OperationId(json.getString("operationId"))
        val request = ActionRequest(
            operationId = operationId,
            sessionId = SessionId(json.getString("sessionId")),
            actionId = ActionId(json.getString("actionId")),
            contractVersion = json.getInt("contractVersion"),
            parameters = parameters,
            createdAtEpochMillis = json.getLong("createdAtEpochMillis"),
            idempotencyKey = json.optString("idempotencyKey", operationId.value)
        )
        return PendingOperation(
            request = request,
            status = OperationStatus.valueOf(json.getString("status")),
            confirmationPrompt = json.getString("confirmationPrompt"),
            updatedAtEpochMillis = json.getLong("updatedAtEpochMillis"),
            legacySource = json.optString("legacySource").takeIf { it.isNotBlank() },
            expiresAtEpochMillis = if (!json.has("expiresAtEpochMillis") ||
                json.isNull("expiresAtEpochMillis")) null
                else json.getLong("expiresAtEpochMillis")
        )
    }

    companion object {
        private const val PREFERENCES_NAME = "assistant_execution_state_v1"
        private const val KEY_ACTIVE = "active_operation"
        private const val KEY_LAST = "last_operation"
        private val ACTIVE_OPERATION_LOCK = Any()

        private const val LEGACY_ACTION_PREFERENCES = "voice_action_pending"
        private const val LEGACY_BUDGET_PREFERENCES = "voice_budget_pending"
        private const val LEGACY_PENDING_KEY = "pending"

        const val DEFAULT_SESSION_ID = "assistant.local"
    }
}
