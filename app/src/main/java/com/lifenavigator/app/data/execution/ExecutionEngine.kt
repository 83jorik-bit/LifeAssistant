package com.lifenavigator.app.data.execution

/**
 * Domain executors receive only a validated, confirmed operation request.
 * They must verify the actual persisted result before returning COMPLETED.
 */
fun interface ActionExecutor {
    fun execute(request: ActionRequest): ActionExecutionResult
}

sealed interface ExecutionAuthorizationResult {
    data object Allowed : ExecutionAuthorizationResult

    /**
     * A temporary or configuration-dependent prerequisite is missing.
     * The confirmed operation remains active and may be retried after the
     * prerequisite is satisfied, or cancelled by the user.
     */
    data class RequiresUserAction(
        val code: String,
        val message: String
    ) : ExecutionAuthorizationResult

    /** A terminal policy denial. The operation is rejected and not executed. */
    data class Denied(
        val code: String,
        val message: String
    ) : ExecutionAuthorizationResult
}

fun interface OperationAuthorizer {
    fun authorize(
        request: ActionRequest,
        definition: ActionDefinition
    ): ExecutionAuthorizationResult
}

fun interface SecurityContextProvider {
    fun currentFor(request: ActionRequest): SecurityContext
}

/**
 * Adapter from the central SAM policy evaluator to the execution contract.
 * It never infers A/B classification and maps all additional requirements
 * to an explicit prerequisite rather than silently allowing the operation.
 */
class SamOperationAuthorizer(
    private val sam: SecurityAuthorizationManager,
    private val contextProvider: SecurityContextProvider
) : OperationAuthorizer {

    override fun authorize(
        request: ActionRequest,
        definition: ActionDefinition
    ): ExecutionAuthorizationResult {
        return when (val decision = sam.evaluate(request, contextProvider.currentFor(request))) {
            SecurityDecision.Allowed -> ExecutionAuthorizationResult.Allowed
            is SecurityDecision.Denied -> ExecutionAuthorizationResult.Denied(
                decision.code,
                decision.reason
            )
            is SecurityDecision.RequiresClassification ->
                ExecutionAuthorizationResult.RequiresUserAction(
                    "REQUIRES_CLASSIFICATION",
                    "Для действия ещё не утверждена категория безопасности А/Б."
                )
            is SecurityDecision.RequiresStepUp ->
                ExecutionAuthorizationResult.RequiresUserAction(
                    "REQUIRES_STEP_UP",
                    "Для этого действия требуется дополнительная аутентификация: ${decision.requiredLevel}."
                )
            is SecurityDecision.RequiresWarningAcknowledgement ->
                ExecutionAuthorizationResult.RequiresUserAction(
                    "REQUIRES_WARNING_ACKNOWLEDGEMENT",
                    decision.warning
                )
            is SecurityDecision.RequiresConfirmation ->
                ExecutionAuthorizationResult.RequiresUserAction(
                    "REQUIRES_CONFIRMATION",
                    "Требуется подтверждение именно этой операции."
                )
        }
    }
}

/**
 * The only component allowed to cross from a confirmed operation to a
 * Functional Executor. It coordinates contract validation, authorization,
 * lifecycle transitions and result verification.
 *
 * The engine is deliberately separate from the legacy dispatcher until the
 * current action-to-security-category matrix and authentication evidence
 * provider are approved. It cannot be constructed without an authorizer.
 */
class ExecutionEngine(
    private val confirmationManager: ConfirmationManager,
    private val registry: ActionRegistry = LifeAssistantActionRegistry.instance,
    executors: Map<String, ActionExecutor>,
    private val authorizer: OperationAuthorizer,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val executors = executors.toMap()

    fun executeConfirmed(
        sessionId: SessionId,
        operationId: OperationId
    ): ActionExecutionResult {
        val pending = try {
            confirmationManager.activeOperation()
        } catch (error: Exception) {
            return result(
                operationId,
                OperationStatus.FAILED,
                "Не удалось безопасно прочитать состояние операции.",
                "OPERATION_STATE_UNAVAILABLE"
            )
        } ?: return result(
            operationId,
            OperationStatus.FAILED,
            "Нет активной операции для выполнения.",
            "NO_ACTIVE_OPERATION"
        )

        val request = pending.request
        if (request.sessionId != sessionId) {
            return result(
                operationId,
                OperationStatus.FAILED,
                "Операция не принадлежит этой диалоговой сессии.",
                "WRONG_SESSION"
            )
        }
        if (request.operationId != operationId) {
            return result(
                operationId,
                OperationStatus.FAILED,
                "Идентификатор операции не совпадает с ожидающим действием.",
                "WRONG_OPERATION"
            )
        }

        when (pending.status) {
            OperationStatus.AWAITING_CONFIRMATION ->
                return result(
                    operationId,
                    OperationStatus.AWAITING_CONFIRMATION,
                    pending.confirmationPrompt,
                    "CONFIRMATION_REQUIRED"
                )
            OperationStatus.RESULT_UNKNOWN ->
                return result(
                    operationId,
                    OperationStatus.RESULT_UNKNOWN,
                    "Результат предыдущего выполнения неизвестен. Повторный запуск запрещён.",
                    "RESULT_UNKNOWN"
                )
            OperationStatus.EXECUTING ->
                return result(
                    operationId,
                    OperationStatus.EXECUTING,
                    "Операция уже выполняется. Повторный запуск запрещён.",
                    "ALREADY_EXECUTING"
                )
            OperationStatus.CONFIRMED -> Unit
            else -> return result(
                operationId,
                pending.status,
                "Операция не находится в состоянии, допускающем выполнение.",
                "INVALID_OPERATION_STATE"
            )
        }

        val definition = when (val validation = registry.validate(request)) {
            is ActionValidationResult.Valid -> validation.definition
            is ActionValidationResult.Invalid -> {
                return rejectBeforeExecution(
                    sessionId,
                    operationId,
                    "Параметры операции больше не проходят проверку: ${validation.errors.joinToString("; ")}",
                    "INVALID_OPERATION"
                )
            }
        }

        if (definition.kind != ActionKind.MUTATION ||
            definition.confirmationPolicy != ConfirmationPolicy.REQUIRED
        ) {
            return rejectBeforeExecution(
                sessionId,
                operationId,
                "Confirmation Manager может передавать на исполнение только подтверждённые операции изменения данных.",
                "UNEXPECTED_ACTION_POLICY"
            )
        }

        val authorization = try {
            authorizer.authorize(request, definition)
        } catch (_: Exception) {
            ExecutionAuthorizationResult.Denied(
                "AUTHORIZATION_ERROR",
                "Не удалось проверить разрешения. Выполнение заблокировано."
            )
        }

        when (authorization) {
            ExecutionAuthorizationResult.Allowed -> Unit
            is ExecutionAuthorizationResult.RequiresUserAction ->
                return result(
                    operationId,
                    OperationStatus.CONFIRMED,
                    authorization.message,
                    authorization.code
                )
            is ExecutionAuthorizationResult.Denied -> {
                return rejectBeforeExecution(
                    sessionId,
                    operationId,
                    authorization.message,
                    authorization.code
                )
            }
        }

        val executor = executors[definition.executorKey]
            ?: return result(
                operationId,
                OperationStatus.CONFIRMED,
                "Для действия пока не подключён исполнитель '${definition.executorKey}'. Операция не запускалась.",
                "EXECUTOR_UNAVAILABLE"
            )

        val started = confirmationManager.beginExecution(sessionId, operationId)
        if (started !is OperationManagerResult.Success) {
            return result(
                operationId,
                OperationStatus.FAILED,
                "Не удалось зафиксировать начало выполнения. Исполнитель не вызывался.",
                "EXECUTION_START_FAILED"
            )
        }

        val executorResult = try {
            executor.execute(request)
        } catch (error: Exception) {
            confirmationManager.markResultUnknown(sessionId, operationId)
            return result(
                operationId,
                OperationStatus.RESULT_UNKNOWN,
                "Исполнение прервано: ${error.message ?: "неизвестная ошибка"}. Сначала необходимо проверить фактическое состояние данных.",
                "EXECUTOR_RESULT_UNKNOWN"
            )
        }

        if (executorResult.operationId != operationId ||
            executorResult.status !in setOf(OperationStatus.COMPLETED, OperationStatus.FAILED)
        ) {
            confirmationManager.markResultUnknown(sessionId, operationId)
            return result(
                operationId,
                OperationStatus.RESULT_UNKNOWN,
                "Исполнитель вернул несогласованный результат. Повторный запуск заблокирован до проверки данных.",
                "INVALID_EXECUTOR_RESULT"
            )
        }

        val terminal = if (executorResult.status == OperationStatus.COMPLETED) {
            confirmationManager.complete(sessionId, operationId)
        } else {
            confirmationManager.fail(sessionId, operationId)
        }

        if (terminal !is OperationManagerResult.Success) {
            // If terminal persistence failed, try to leave a durable marker that
            // prevents replay. If that write also fails, process-start recovery
            // converts EXECUTING to RESULT_UNKNOWN.
            confirmationManager.markResultUnknown(sessionId, operationId)
            return result(
                operationId,
                OperationStatus.RESULT_UNKNOWN,
                "Исполнитель завершил работу, но итог операции не удалось надёжно зафиксировать. Повторный запуск запрещён.",
                "TERMINAL_STATE_PERSISTENCE_FAILED"
            )
        }

        return executorResult.copy(
            completedAtEpochMillis = executorResult.completedAtEpochMillis ?: nowMillis()
        )
    }

    private fun rejectBeforeExecution(
        sessionId: SessionId,
        operationId: OperationId,
        message: String,
        errorCode: String
    ): ActionExecutionResult {
        return when (confirmationManager.reject(sessionId, operationId)) {
            is OperationManagerResult.Success ->
                result(operationId, OperationStatus.REJECTED, message, errorCode)
            else ->
                result(
                    operationId,
                    OperationStatus.CONFIRMED,
                    "Отказ не удалось надёжно зафиксировать. Исполнитель не вызывался; операция остаётся заблокированной.",
                    "REJECTION_PERSISTENCE_FAILED"
                )
        }
    }

    private fun result(
        operationId: OperationId,
        status: OperationStatus,
        message: String,
        errorCode: String
    ) = ActionExecutionResult(
        operationId = operationId,
        status = status,
        message = message,
        errorCode = errorCode,
        completedAtEpochMillis = if (
            status == OperationStatus.COMPLETED ||
            status == OperationStatus.FAILED ||
            status == OperationStatus.REJECTED ||
            status == OperationStatus.CANCELLED
        ) nowMillis() else null
    )
}
