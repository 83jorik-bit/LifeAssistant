package com.lifenavigator.app.data.execution

data class PendingOperation(
    val request: ActionRequest,
    val status: OperationStatus,
    val confirmationPrompt: String,
    val updatedAtEpochMillis: Long
) {
    init {
        require(confirmationPrompt.isNotBlank()) {
            "confirmationPrompt must not be blank"
        }
        require(updatedAtEpochMillis >= 0L) {
            "updatedAtEpochMillis must not be negative"
        }
    }
}

sealed interface LegacyPendingImport {
    data object None : LegacyPendingImport
    data class Ready(
        val operation: PendingOperation,
        val source: String
    ) : LegacyPendingImport
    data class Corrupt(
        val source: String
    ) : LegacyPendingImport
}

/**
 * Persistence boundary for the single active operation and the last terminal one.
 * Implementations must make each individual write durable before returning true.
 */
interface OperationStateStore {
    fun readActive(): PendingOperation?
    fun writeActive(operation: PendingOperation): Boolean
    fun clearActive(): Boolean
    fun readLast(): PendingOperation?
    fun writeLast(operation: PendingOperation): Boolean

    /** Imports one legacy pending action without deleting its source. */
    fun readNextLegacy(): LegacyPendingImport

    /** Deletes a legacy source only after the imported operation is durable. */
    fun clearLegacy(source: String): Boolean
}

sealed interface OperationManagerResult {
    data class Success(val operation: PendingOperation) : OperationManagerResult
    data class Busy(val activeOperation: PendingOperation) : OperationManagerResult
    data class Invalid(val errors: List<String>) : OperationManagerResult
    data object NoActiveOperation : OperationManagerResult
    data class WrongOperation(val expected: OperationId, val received: OperationId) : OperationManagerResult
    data class InvalidState(val operation: PendingOperation, val expected: String) : OperationManagerResult
    data class StorageFailure(val message: String) : OperationManagerResult
}

/**
 * Owns the confirmation lifecycle, but never performs domain mutations.
 *
 * The manager serializes access to one active operation. The executor is called
 * only after a transition from AWAITING_CONFIRMATION to CONFIRMED and then
 * EXECUTING. A process restart during EXECUTING is converted to RESULT_UNKNOWN;
 * it is never silently retried.
 */
class ConfirmationManager(
    private val store: OperationStateStore,
    private val registry: ActionRegistry = LifeAssistantActionRegistry.instance,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    init {
        recoverInterruptedExecution()
    }

    @Synchronized
    fun activeOperation(): PendingOperation? {
        val active = runCatching { store.readActive() }.getOrElse {
            throw IllegalStateException("Unable to read active operation", it)
        }
        if (active != null) {
            if (isTerminal(active.status)) {
                if (!store.writeLast(active) || !store.clearActive()) {
                    throw IllegalStateException("Unable to finalize stored operation")
                }
                return null
            }
            return active
        }

        return when (val legacy = store.readNextLegacy()) {
            LegacyPendingImport.None -> null
            is LegacyPendingImport.Corrupt -> throw IllegalStateException(
                "Legacy pending operation is unreadable in ${legacy.source}; it was preserved"
            )
            is LegacyPendingImport.Ready -> {
                val validation = registry.validate(legacy.operation.request)
                if (validation !is ActionValidationResult.Valid ||
                    validation.definition.confirmationPolicy != ConfirmationPolicy.REQUIRED
                ) {
                    throw IllegalStateException(
                        "Legacy pending operation failed contract validation; it was preserved"
                    )
                }
                if (!store.writeActive(legacy.operation)) {
                    throw IllegalStateException("Unable to migrate legacy pending operation")
                }
                if (!store.clearLegacy(legacy.source)) {
                    throw IllegalStateException("Migrated operation is saved, but legacy cleanup failed")
                }
                legacy.operation
            }
        }
    }

    @Synchronized
    fun prepare(
        sessionId: SessionId,
        actionId: ActionId,
        parameters: Map<String, ActionParameterValue>,
        confirmationPrompt: String,
        idempotencyKey: String? = null
    ): OperationManagerResult {
        if (confirmationPrompt.isBlank()) {
            return OperationManagerResult.Invalid(listOf("Confirmation prompt must not be blank"))
        }

        val active = try {
            activeOperation()
        } catch (error: Exception) {
            return OperationManagerResult.StorageFailure(
                error.message ?: "Unable to read operation state"
            )
        }
        if (active != null) return OperationManagerResult.Busy(active)

        val definition = registry.find(actionId)
            ?: return OperationManagerResult.Invalid(
                listOf("Action '${actionId.value}' is not registered")
            )

        val operationId = OperationId.new()
        val now = nowMillis()
        val request = ActionRequest(
            operationId = operationId,
            sessionId = sessionId,
            actionId = actionId,
            contractVersion = definition.contractVersion,
            parameters = parameters,
            createdAtEpochMillis = now,
            idempotencyKey = idempotencyKey ?: operationId.value
        )

        when (val validation = registry.validate(request)) {
            is ActionValidationResult.Invalid ->
                return OperationManagerResult.Invalid(validation.errors)
            is ActionValidationResult.Valid -> Unit
        }

        if (definition.confirmationPolicy != ConfirmationPolicy.REQUIRED) {
            return OperationManagerResult.Invalid(
                listOf("Action does not require confirmation and must not enter ConfirmationManager")
            )
        }

        val operation = PendingOperation(
            request = request,
            status = OperationStatus.AWAITING_CONFIRMATION,
            confirmationPrompt = confirmationPrompt,
            updatedAtEpochMillis = now
        )
        return if (store.writeActive(operation)) {
            OperationManagerResult.Success(operation)
        } else {
            OperationManagerResult.StorageFailure("Unable to persist pending operation")
        }
    }

    @Synchronized
    fun confirm(operationId: OperationId): OperationManagerResult =
        transition(operationId, OperationStatus.AWAITING_CONFIRMATION, OperationStatus.CONFIRMED)

    @Synchronized
    fun beginExecution(operationId: OperationId): OperationManagerResult =
        transition(operationId, OperationStatus.CONFIRMED, OperationStatus.EXECUTING)

    @Synchronized
    fun complete(operationId: OperationId): OperationManagerResult =
        finish(operationId, OperationStatus.COMPLETED)

    @Synchronized
    fun fail(operationId: OperationId): OperationManagerResult =
        finish(operationId, OperationStatus.FAILED)

    @Synchronized
    fun cancel(operationId: OperationId): OperationManagerResult {
        val operation = findOperation(operationId) ?: return OperationManagerResult.NoActiveOperation
        if (operation.request.operationId != operationId) {
            return OperationManagerResult.WrongOperation(operation.request.operationId, operationId)
        }
        if (operation.status != OperationStatus.AWAITING_CONFIRMATION &&
            operation.status != OperationStatus.CONFIRMED
        ) {
            return OperationManagerResult.InvalidState(
                operation,
                "AWAITING_CONFIRMATION or CONFIRMED"
            )
        }
        return finish(operationId, OperationStatus.CANCELLED)
    }

    @Synchronized
    fun markResultUnknown(operationId: OperationId): OperationManagerResult =
        transition(operationId, OperationStatus.EXECUTING, OperationStatus.RESULT_UNKNOWN)

    @Synchronized
    fun resolveUnknown(
        operationId: OperationId,
        resolvedStatus: OperationStatus
    ): OperationManagerResult {
        require(
            resolvedStatus == OperationStatus.COMPLETED ||
                resolvedStatus == OperationStatus.FAILED
        ) { "Unknown result can only be resolved as COMPLETED or FAILED" }
        val operation = findOperation(operationId) ?: return OperationManagerResult.NoActiveOperation
        if (operation.request.operationId != operationId) {
            return OperationManagerResult.WrongOperation(operation.request.operationId, operationId)
        }
        if (operation.status != OperationStatus.RESULT_UNKNOWN) {
            return OperationManagerResult.InvalidState(operation, "RESULT_UNKNOWN")
        }
        return finish(operationId, resolvedStatus)
    }

    private fun transition(
        operationId: OperationId,
        expected: OperationStatus,
        target: OperationStatus
    ): OperationManagerResult {
        val operation = findOperation(operationId) ?: return OperationManagerResult.NoActiveOperation
        if (operation.request.operationId != operationId) {
            return OperationManagerResult.WrongOperation(
                operation.request.operationId,
                operationId
            )
        }
        if (operation.status != expected) {
            return OperationManagerResult.InvalidState(operation, expected.name)
        }
        val updated = operation.copy(
            status = target,
            updatedAtEpochMillis = nowMillis()
        )
        return if (store.writeActive(updated)) {
            OperationManagerResult.Success(updated)
        } else {
            OperationManagerResult.StorageFailure("Unable to persist operation state $target")
        }
    }

    private fun finish(
        operationId: OperationId,
        target: OperationStatus
    ): OperationManagerResult {
        val operation = findOperation(operationId) ?: return OperationManagerResult.NoActiveOperation
        if (operation.request.operationId != operationId) {
            return OperationManagerResult.WrongOperation(operation.request.operationId, operationId)
        }
        val allowed = when (target) {
            OperationStatus.COMPLETED, OperationStatus.FAILED ->
                operation.status == OperationStatus.EXECUTING ||
                    operation.status == OperationStatus.RESULT_UNKNOWN
            OperationStatus.CANCELLED ->
                operation.status == OperationStatus.AWAITING_CONFIRMATION ||
                    operation.status == OperationStatus.CONFIRMED
            else -> false
        }
        if (!allowed) {
            return OperationManagerResult.InvalidState(operation, "valid predecessor for $target")
        }

        val updated = operation.copy(
            status = target,
            updatedAtEpochMillis = nowMillis()
        )
        if (!store.writeLast(updated)) {
            return OperationManagerResult.StorageFailure("Unable to persist terminal operation")
        }
        if (!store.clearActive()) {
            return OperationManagerResult.StorageFailure("Terminal operation saved, but active state could not be cleared")
        }
        return OperationManagerResult.Success(updated)
    }

    private fun findOperation(operationId: OperationId): PendingOperation? {
        val operation = try {
            activeOperation()
        } catch (error: Exception) {
            throw IllegalStateException("Unable to access operation state", error)
        } ?: return null
        return operation
    }

    private fun recoverInterruptedExecution() {
        val active = runCatching { store.readActive() }.getOrNull() ?: return
        if (active.status == OperationStatus.EXECUTING) {
            store.writeActive(
                active.copy(
                    status = OperationStatus.RESULT_UNKNOWN,
                    updatedAtEpochMillis = nowMillis()
                )
            )
        }
    }

    private fun isTerminal(status: OperationStatus): Boolean =
        status == OperationStatus.COMPLETED ||
            status == OperationStatus.FAILED ||
            status == OperationStatus.REJECTED ||
            status == OperationStatus.EXPIRED ||
            status == OperationStatus.CANCELLED
}
