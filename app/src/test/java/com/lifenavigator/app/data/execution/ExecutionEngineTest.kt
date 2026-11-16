package com.lifenavigator.app.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ExecutionEngineTest {

    private val session = SessionId("assistant.execution-test")

    @Test
    fun doesNotExecuteBeforeConfirmation() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val pending = prepareExpense(manager)
        val calls = AtomicInteger()
        val engine = engine(manager, ActionExecutor { request ->
            calls.incrementAndGet()
            completed(request)
        })

        val result = engine.executeConfirmed(session, pending.request.operationId)

        assertEquals(OperationStatus.AWAITING_CONFIRMATION, result.status)
        assertEquals(0, calls.get())
        assertEquals(OperationStatus.AWAITING_CONFIRMATION, store.active?.status)
    }

    @Test
    fun executesConfirmedOperationAndPersistsTerminalState() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val pending = prepareExpense(manager)
        val id = pending.request.operationId
        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)

        val calls = AtomicInteger()
        val engine = engine(manager, ActionExecutor { request ->
            calls.incrementAndGet()
            completed(request)
        })

        val result = engine.executeConfirmed(session, id)

        assertEquals(OperationStatus.COMPLETED, result.status)
        assertTrue(result.success)
        assertEquals(1, calls.get())
        assertEquals(OperationStatus.COMPLETED, store.last?.status)
        assertNull(store.active)

        val replay = engine.executeConfirmed(session, id)
        assertEquals("NO_ACTIVE_OPERATION", replay.errorCode)
        assertEquals(1, calls.get())
    }

    @Test
    fun authorizationPrerequisiteKeepsOperationConfirmedWithoutCallingExecutor() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val pending = prepareExpense(manager)
        val id = pending.request.operationId
        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)

        val calls = AtomicInteger()
        val engine = engine(
            manager,
            ActionExecutor { request -> calls.incrementAndGet(); completed(request) },
            OperationAuthorizer { _, _ ->
                ExecutionAuthorizationResult.RequiresUserAction(
                    "REQUIRES_STEP_UP",
                    "Требуется дополнительная аутентификация."
                )
            }
        )

        val result = engine.executeConfirmed(session, id)

        assertEquals(OperationStatus.CONFIRMED, result.status)
        assertEquals("REQUIRES_STEP_UP", result.errorCode)
        assertEquals(OperationStatus.CONFIRMED, store.active?.status)
        assertEquals(0, calls.get())
    }

    @Test
    fun deniedOperationIsRejectedWithoutCallingExecutor() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val pending = prepareExpense(manager)
        val id = pending.request.operationId
        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)

        val calls = AtomicInteger()
        val engine = engine(
            manager,
            ActionExecutor { request -> calls.incrementAndGet(); completed(request) },
            OperationAuthorizer { _, _ ->
                ExecutionAuthorizationResult.Denied("PERMISSION_DENIED", "Нет разрешения.")
            }
        )

        val result = engine.executeConfirmed(session, id)

        assertEquals(OperationStatus.REJECTED, result.status)
        assertEquals("PERMISSION_DENIED", result.errorCode)
        assertEquals(OperationStatus.REJECTED, store.last?.status)
        assertNull(store.active)
        assertEquals(0, calls.get())
    }

    @Test
    fun rejectionPersistenceFailureDoesNotReportRejectionOrExecute() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val pending = prepareExpense(manager)
        val id = pending.request.operationId
        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)
        store.failFinishActive = true

        val calls = AtomicInteger()
        val engine = engine(
            manager,
            ActionExecutor { request -> calls.incrementAndGet(); completed(request) },
            OperationAuthorizer { _, _ ->
                ExecutionAuthorizationResult.Denied("PERMISSION_DENIED", "Нет разрешения.")
            }
        )

        val result = engine.executeConfirmed(session, id)

        assertEquals(OperationStatus.CONFIRMED, result.status)
        assertEquals("REJECTION_PERSISTENCE_FAILED", result.errorCode)
        assertEquals(OperationStatus.CONFIRMED, store.active?.status)
        assertNull(store.last)
        assertEquals(0, calls.get())
    }

    @Test
    fun executorExceptionLeavesResultUnknownAndCannotBeReplayed() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val pending = prepareExpense(manager)
        val id = pending.request.operationId
        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)

        val calls = AtomicInteger()
        val engine = engine(manager, ActionExecutor {
            calls.incrementAndGet()
            throw IllegalStateException("simulated interruption")
        })

        val first = engine.executeConfirmed(session, id)
        val second = engine.executeConfirmed(session, id)

        assertEquals(OperationStatus.RESULT_UNKNOWN, first.status)
        assertEquals(OperationStatus.RESULT_UNKNOWN, store.active?.status)
        assertEquals(OperationStatus.RESULT_UNKNOWN, second.status)
        assertEquals(1, calls.get())
    }

    @Test
    fun inconsistentExecutorResultBecomesUnknown() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val pending = prepareExpense(manager)
        val id = pending.request.operationId
        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)

        val engine = engine(manager, ActionExecutor {
            ActionExecutionResult(
                operationId = OperationId("different-operation"),
                status = OperationStatus.COMPLETED,
                message = "Несогласованный ответ."
            )
        })

        val result = engine.executeConfirmed(session, id)

        assertEquals(OperationStatus.RESULT_UNKNOWN, result.status)
        assertEquals(OperationStatus.RESULT_UNKNOWN, store.active?.status)
    }

    @Test
    fun missingExecutorKeepsOperationConfirmedAndDoesNotStartExecution() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val pending = prepareExpense(manager)
        val id = pending.request.operationId
        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)

        val engine = ExecutionEngine(
            confirmationManager = manager,
            executors = emptyMap(),
            authorizer = OperationAuthorizer { _, _ -> ExecutionAuthorizationResult.Allowed }
        )

        val result = engine.executeConfirmed(session, id)

        assertEquals(OperationStatus.CONFIRMED, result.status)
        assertEquals("EXECUTOR_UNAVAILABLE", result.errorCode)
        assertEquals(OperationStatus.CONFIRMED, store.active?.status)
    }

    @Test
    fun samAdapterDoesNotInferSecurityCategoryForUnclassifiedMutation() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val pending = prepareExpense(manager)
        val request = pending.request
        val sam = SecurityAuthorizationManager(
            eventSink = SecurityEventSink { true }
        )
        val authorizer = SamOperationAuthorizer(
            sam,
            SecurityContextProvider {
                SecurityContext(
                    authorizedDomains = setOf(ActionDomain.BUDGET),
                    authenticationLevel = AuthenticationLevel.STRONG,
                    authenticatedOperationId = request.operationId,
                    confirmedOperationId = request.operationId
                )
            }
        )
        val definition = LifeAssistantActionRegistry.instance.find(request.actionId)!!

        val result = authorizer.authorize(request, definition)

        assertTrue(result is ExecutionAuthorizationResult.RequiresUserAction)
        assertEquals("REQUIRES_CLASSIFICATION", (result as ExecutionAuthorizationResult.RequiresUserAction).code)
    }

    private fun engine(
        manager: ConfirmationManager,
        executor: ActionExecutor,
        authorizer: OperationAuthorizer = OperationAuthorizer { _, _ ->
            ExecutionAuthorizationResult.Allowed
        }
    ) = ExecutionEngine(
        confirmationManager = manager,
        executors = mapOf("budget" to executor),
        authorizer = authorizer,
        nowMillis = { 999L }
    )

    private fun prepareExpense(manager: ConfirmationManager): PendingOperation {
        val result = manager.prepare(
            sessionId = session,
            actionId = ActionId("budget.expense.create"),
            parameters = mapOf(
                "amountRub" to ActionParameterValue.Integer(2500),
                "categoryKey" to ActionParameterValue.Text("GROCERY")
            ),
            confirmationPrompt = "Сохранить расход?"
        )
        return (result as OperationManagerResult.Success).operation
    }

    private fun completed(request: ActionRequest) = ActionExecutionResult(
        operationId = request.operationId,
        status = OperationStatus.COMPLETED,
        message = "Операция сохранена.",
        entityId = "entity-1"
    )

    private class InMemoryOperationStateStore : OperationStateStore {
        var active: PendingOperation? = null
        var last: PendingOperation? = null
        var failFinishActive: Boolean = false

        override fun readActive(): PendingOperation? = active

        override fun createActiveIfAbsent(operation: PendingOperation): Boolean {
            if (active != null) return false
            active = operation
            return true
        }

        override fun writeActive(operation: PendingOperation): Boolean {
            active = operation
            return true
        }

        override fun clearActive(): Boolean {
            active = null
            return true
        }

        override fun readLast(): PendingOperation? = last

        override fun writeLast(operation: PendingOperation): Boolean {
            last = operation
            return true
        }

        override fun finishActive(operation: PendingOperation): Boolean {
            if (failFinishActive) return false
            last = operation
            active = null
            return true
        }

        override fun readNextLegacy(): LegacyPendingImport = LegacyPendingImport.None

        override fun clearLegacy(source: String): Boolean = true
    }
}
