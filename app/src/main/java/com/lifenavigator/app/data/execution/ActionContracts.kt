package com.lifenavigator.app.data.execution

import java.util.UUID

/**
 * Stable identifier of one requested operation.
 *
 * It is created before confirmation or execution and must be reused by every
 * subsequent state transition for that operation.
 */
@JvmInline
value class OperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "OperationId must not be blank" }
    }

    companion object {
        fun new(): OperationId = OperationId(UUID.randomUUID().toString())
    }
}

/** Identifier of the current assistant conversation/session. */
@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId must not be blank" }
    }
}

/** Stable, versioned identifier of an action registered by the application. */
@JvmInline
value class ActionId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9_]*)+"))) {
            "ActionId must use a namespaced lower-case identifier"
        }
    }
}

enum class ActionDomain {
    TASKS,
    CALENDAR,
    BUDGET,
    PLACES,
    SUBSCRIPTIONS,
    PROFILE
}

enum class ActionKind {
    READ,
    MUTATION
}

enum class ActionParameterType {
    TEXT,
    INTEGER,
    BOOLEAN
}

enum class ConfirmationPolicy {
    NOT_REQUIRED,
    REQUIRED
}

/**
 * UNCLASSIFIED is deliberate: the project has approved categories A and B,
 * but the exact mapping of every action to a category remains an open decision.
 * SAM must not infer a category from the action name.
 */
enum class SecurityCategory {
    UNCLASSIFIED,
    A,
    B
}

enum class OperationStatus {
    PREPARED,
    AWAITING_CONFIRMATION,
    CONFIRMED,
    REJECTED,
    EXPIRED,
    EXECUTING,
    RESULT_UNKNOWN,
    COMPLETED,
    FAILED,
    CANCELLED
}

sealed interface ActionParameterValue {
    data class Text(val value: String) : ActionParameterValue
    data class Integer(val value: Long) : ActionParameterValue
    data class BooleanValue(val value: Boolean) : ActionParameterValue
}

data class ActionParameterDefinition(
    val name: String,
    val type: ActionParameterType,
    val required: Boolean = true,
    val allowBlank: Boolean = false,
    val minimumInteger: Long? = null,
    val maximumInteger: Long? = null,
    val allowedTextValues: Set<String> = emptySet()
) {
    init {
        require(name.matches(Regex("[a-z][A-Za-z0-9]*"))) {
            "Parameter name must be a non-empty identifier"
        }
        require(minimumInteger == null || type == ActionParameterType.INTEGER) {
            "Integer bounds can only be used with INTEGER parameters"
        }
        require(maximumInteger == null || type == ActionParameterType.INTEGER) {
            "Integer bounds can only be used with INTEGER parameters"
        }
        require(
            minimumInteger == null || maximumInteger == null ||
                minimumInteger <= maximumInteger
        ) { "minimumInteger must not exceed maximumInteger" }
        require(allowedTextValues.isEmpty() || type == ActionParameterType.TEXT) {
            "allowedTextValues can only be used with TEXT parameters"
        }
    }
}

data class ActionDefinition(
    val id: ActionId,
    val contractVersion: Int = 1,
    val domain: ActionDomain,
    val kind: ActionKind,
    val parameters: List<ActionParameterDefinition>,
    val confirmationPolicy: ConfirmationPolicy,
    val securityCategory: SecurityCategory = SecurityCategory.UNCLASSIFIED,
    /** Logical executor key; the executor is connected in a later implementation stage. */
    val executorKey: String
) {
    init {
        require(contractVersion > 0) { "contractVersion must be positive" }
        require(parameters.map { it.name }.distinct().size == parameters.size) {
            "Action parameter names must be unique"
        }
        require(executorKey.isNotBlank()) { "executorKey must not be blank" }
    }
}

/**
 * Immutable request envelope. The idempotency key defaults to the operation ID,
 * but can later be supplied by a trusted caller when retry semantics require it.
 */
data class ActionRequest(
    val operationId: OperationId,
    val sessionId: SessionId,
    val actionId: ActionId,
    val contractVersion: Int,
    val parameters: Map<String, ActionParameterValue>,
    val createdAtEpochMillis: Long,
    val idempotencyKey: String = operationId.value
) {
    init {
        require(contractVersion > 0) { "contractVersion must be positive" }
        require(createdAtEpochMillis >= 0L) { "createdAtEpochMillis must not be negative" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
    }
}

/**
 * Canonical result envelope for every future executor.
 *
 * A successful result is possible only for COMPLETED. Confirmation, rejection,
 * cancellation and failure are explicit non-success states.
 */
data class ActionExecutionResult(
    val operationId: OperationId,
    val status: OperationStatus,
    val message: String,
    val entityId: String? = null,
    val errorCode: String? = null,
    val completedAtEpochMillis: Long? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    val success: Boolean
        get() = status == OperationStatus.COMPLETED

    init {
        require(message.isNotBlank()) { "Result message must not be blank" }
        require(completedAtEpochMillis == null || completedAtEpochMillis >= 0L) {
            "completedAtEpochMillis must not be negative"
        }
    }
}
