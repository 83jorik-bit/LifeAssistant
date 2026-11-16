package com.lifenavigator.app.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationManagerTest {

    private val session = SessionId("assistant.test")

    @Test
    fun preparesOnlyRegisteredAndValidMutations() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store, nowMillis = { 100L })

        val invalid = manager.prepare(
            sessionId = session,
            actionId = ActionId("budget.expense.create"),
            parameters = mapOf(
                "amountRub" to ActionParameterValue.Integer(0),
                "categoryKey" to ActionParameterValue.Text("GROCERY")
            ),
            confirmationPrompt = "Сохранить расход?"
        )
        assertTrue(invalid is OperationManagerResult.Invalid)
        assertNull(store.active)

        val valid = manager.prepare(
            sessionId = session,
            actionId = ActionId("budget.expense.create"),
            parameters = mapOf(
                "amountRub" to ActionParameterValue.Integer(2500),
                "categoryKey" to ActionParameterValue.Text("GROCERY")
            ),
            confirmationPrompt = "Сохранить расход?"
        )
        assertTrue(valid is OperationManagerResult.Success)
        assertEquals(OperationStatus.AWAITING_CONFIRMATION, store.active?.status)
        assertEquals(100L, store.active!!.request.createdAtEpochMillis)
    }

    @Test
    fun allowsOnlyOneActiveOperation() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)

        val first = prepareExpense(manager)
        assertTrue(first is OperationManagerResult.Success)

        val second = manager.prepare(
            sessionId = session,
            actionId = ActionId("budget.income.set"),
            parameters = mapOf("amountRub" to ActionParameterValue.Integer(100000)),
            confirmationPrompt = "Установить доход?"
        )

        assertTrue(second is OperationManagerResult.Busy)
        assertEquals(
            (first as OperationManagerResult.Success).operation.request.operationId,
            (second as OperationManagerResult.Busy).activeOperation.request.operationId
        )
    }

    @Test
    fun confirmationIsBoundToOperationAndSession() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val prepared = prepareExpense(manager) as OperationManagerResult.Success
        val operationId = prepared.operation.request.operationId

        val wrongSession = manager.confirm(SessionId("another.session"), operationId)
        assertTrue(wrongSession is OperationManagerResult.WrongSession)
        assertEquals(OperationStatus.AWAITING_CONFIRMATION, store.active?.status)

        val wrongOperation = manager.confirm(session, OperationId("different-operation"))
        assertTrue(wrongOperation is OperationManagerResult.WrongOperation)
        assertEquals(OperationStatus.AWAITING_CONFIRMATION, store.active?.status)

        val confirmed = manager.confirm(session, operationId)
        assertTrue(confirmed is OperationManagerResult.Success)
        assertEquals(OperationStatus.CONFIRMED, store.active?.status)
    }

    @Test
    fun enforcesLifecycleAndPersistsTerminalResult() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val prepared = prepareExpense(manager) as OperationManagerResult.Success
        val id = prepared.operation.request.operationId

        val prematureCompletion = manager.complete(session, id)
        assertTrue(prematureCompletion is OperationManagerResult.InvalidState)
        assertEquals(OperationStatus.AWAITING_CONFIRMATION, store.active?.status)

        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)
        assertTrue(manager.beginExecution(session, id) is OperationManagerResult.Success)
        assertEquals(OperationStatus.EXECUTING, store.active?.status)

        val completed = manager.complete(session, id)
        assertTrue(completed is OperationManagerResult.Success)
        assertEquals(OperationStatus.COMPLETED, store.last?.status)
        assertNull(store.active)
        assertNull(manager.activeOperation())
    }

    @Test
    fun cancellationDoesNotEnterExecution() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val prepared = prepareExpense(manager) as OperationManagerResult.Success
        val id = prepared.operation.request.operationId

        val cancelled = manager.cancel(session, id)

        assertTrue(cancelled is OperationManagerResult.Success)
        assertEquals(OperationStatus.CANCELLED, store.last?.status)
        assertNull(store.active)
    }

    @Test
    fun interruptedExecutionBecomesUnknownAndCannotBeRetried() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val prepared = prepareExpense(manager) as OperationManagerResult.Success
        val id = prepared.operation.request.operationId

        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)
        assertTrue(manager.beginExecution(session, id) is OperationManagerResult.Success)

        // A new manager instance represents a process restart.
        val restartedManager = ConfirmationManager(store)
        assertEquals(OperationStatus.RESULT_UNKNOWN, store.active?.status)

        val repeatedConfirmation = restartedManager.confirm(session, id)
        assertTrue(repeatedConfirmation is OperationManagerResult.InvalidState)
        assertEquals(OperationStatus.RESULT_UNKNOWN, store.active?.status)

        val resolved = restartedManager.resolveUnknown(
            session,
            id,
            OperationStatus.COMPLETED
        )
        assertTrue(resolved is OperationManagerResult.Success)
        assertEquals(OperationStatus.COMPLETED, store.last?.status)
        assertNull(store.active)
    }

    @Test
    fun importsLegacyOperationBeforeAcceptingNewOperation() {
        val store = InMemoryOperationStateStore()
        val legacyRequest = ActionRequest(
            operationId = OperationId("legacy-operation"),
            sessionId = session,
            actionId = ActionId("budget.income.set"),
            contractVersion = 1,
            parameters = mapOf("amountRub" to ActionParameterValue.Integer(50000)),
            createdAtEpochMillis = 50L
        )
        store.legacy = LegacyPendingImport.Ready(
            PendingOperation(
                request = legacyRequest,
                status = OperationStatus.AWAITING_CONFIRMATION,
                confirmationPrompt = "Подтвердить ранее подготовленный доход?",
                updatedAtEpochMillis = 50L
            ),
            source = "legacy-budget"
        )

        val manager = ConfirmationManager(store)
        val imported = manager.activeOperation()

        assertEquals("legacy-operation", imported?.request?.operationId?.value)
        assertTrue(store.legacyCleared)
        val blocked = manager.prepare(
            sessionId = session,
            actionId = ActionId("budget.expense.create"),
            parameters = mapOf(
                "amountRub" to ActionParameterValue.Integer(100),
                "categoryKey" to ActionParameterValue.Text("GROCERY")
            ),
            confirmationPrompt = "Сохранить?"
        )
        assertTrue(blocked is OperationManagerResult.Busy)
    }

    @Test
    fun unreadableLegacyOperationBlocksNewMutationsWithoutDeletingIt() {
        val store = InMemoryOperationStateStore().apply {
            legacy = LegacyPendingImport.Corrupt("legacy-budget")
        }
        val manager = ConfirmationManager(store)

        val result = manager.prepare(
            sessionId = session,
            actionId = ActionId("budget.expense.create"),
            parameters = mapOf(
                "amountRub" to ActionParameterValue.Integer(100),
                "categoryKey" to ActionParameterValue.Text("GROCERY")
            ),
            confirmationPrompt = "Сохранить?"
        )

        assertTrue(result is OperationManagerResult.StorageFailure)
        assertTrue(!store.legacyCleared)
        assertNull(store.active)
    }


    @Test
    fun rejectionIsDistinctFromCancellationAndNeverExecutes() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val prepared = prepareExpense(manager) as OperationManagerResult.Success
        val id = prepared.operation.request.operationId

        val rejected = manager.reject(session, id)

        assertTrue(rejected is OperationManagerResult.Success)
        assertEquals(OperationStatus.REJECTED, store.last?.status)
        assertNull(store.active)
    }

    @Test
    fun expiredOperationIsClosedBeforeItCanBeConfirmed() {
        var now = 100L
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store, nowMillis = { now })
        val prepared = prepareExpense(manager) as OperationManagerResult.Success
        val operation = prepared.operation.copy(expiresAtEpochMillis = 200L)
        store.active = operation

        now = 200L
        assertNull(manager.activeOperation())
        assertEquals(OperationStatus.EXPIRED, store.last?.status)
        assertNull(store.active)
        assertTrue(manager.confirm(session, operation.request.operationId) is OperationManagerResult.NoActiveOperation)
    }

    @Test
    fun terminalStateAndActiveSlotAreCommittedAtomically() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val prepared = prepareExpense(manager) as OperationManagerResult.Success
        val id = prepared.operation.request.operationId

        assertTrue(manager.confirm(session, id) is OperationManagerResult.Success)
        assertTrue(manager.beginExecution(session, id) is OperationManagerResult.Success)
        store.failFinishActive = true

        val failedCommit = manager.complete(session, id)

        assertTrue(failedCommit is OperationManagerResult.StorageFailure)
        assertEquals(OperationStatus.EXECUTING, store.active?.status)
        assertNull(store.last)
    }

    @Test
    fun expiredOperationIsPersistedBeforeActiveStateIsCleared() {
        var now = 100L
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store, nowMillis = { now })
        val prepared = prepareExpense(manager) as OperationManagerResult.Success
        store.active = prepared.operation.copy(expiresAtEpochMillis = 150L)
        store.failFinishActive = true

        now = 150L
        val failure = runCatching { manager.activeOperation() }

        assertTrue(failure.isFailure)
        assertEquals(OperationStatus.AWAITING_CONFIRMATION, store.active?.status)
    }

    @Test
    fun recoveryFailsClosedWhenUnknownResultCannotBePersisted() {
        val store = InMemoryOperationStateStore()
        val manager = ConfirmationManager(store)
        val prepared = prepareExpense(manager) as OperationManagerResult.Success
        store.active = prepared.operation.copy(status = OperationStatus.EXECUTING)
        store.failWriteActive = true

        val restarted = runCatching { ConfirmationManager(store) }

        assertTrue(restarted.isFailure)
        assertEquals(OperationStatus.EXECUTING, store.active?.status)
    }

    private fun prepareExpense(manager: ConfirmationManager): OperationManagerResult =
        manager.prepare(
            sessionId = session,
            actionId = ActionId("budget.expense.create"),
            parameters = mapOf(
                "amountRub" to ActionParameterValue.Integer(2500),
                "categoryKey" to ActionParameterValue.Text("GROCERY")
            ),
            confirmationPrompt = "Сохранить расход?"
        )

    private class InMemoryOperationStateStore : OperationStateStore {
        var active: PendingOperation? = null
        var last: PendingOperation? = null
        var legacy: LegacyPendingImport = LegacyPendingImport.None
        var legacyCleared: Boolean = false
        var failWriteActive: Boolean = false
        var failWriteLast: Boolean = false
        var failFinishActive: Boolean = false

        override fun readActive(): PendingOperation? = active
        override fun createActiveIfAbsent(operation: PendingOperation): Boolean {
            if (active != null) return false
            active = operation
            return true
        }
        override fun writeActive(operation: PendingOperation): Boolean {
            if (failWriteActive) return false
            active = operation
            return true
        }
        override fun clearActive(): Boolean {
            active = null
            return true
        }
        override fun readLast(): PendingOperation? = last
        override fun writeLast(operation: PendingOperation): Boolean {
            if (failWriteLast) return false
            last = operation
            return true
        }
        override fun finishActive(operation: PendingOperation): Boolean {
            if (failFinishActive) return false
            last = operation
            active = null
            return true
        }
        override fun readNextLegacy(): LegacyPendingImport = legacy
        override fun clearLegacy(source: String): Boolean {
            legacyCleared = true
            legacy = LegacyPendingImport.None
            return true
        }
    }
}
