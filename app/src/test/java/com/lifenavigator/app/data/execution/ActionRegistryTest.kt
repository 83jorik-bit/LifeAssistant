package com.lifenavigator.app.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRegistryTest {

    private val registry = LifeAssistantActionRegistry.instance

    @Test
    fun exposesCurrentBudgetTaskAndCalendarOperations() {
        assertTrue(registry.find(ActionId("budget.expense.create")) != null)
        assertTrue(registry.find(ActionId("budget.expense.delete")) != null)
        assertTrue(registry.find(ActionId("task.create")) != null)
        assertTrue(registry.find(ActionId("task.complete")) != null)
        assertTrue(registry.find(ActionId("calendar.event.create")) != null)
        assertTrue(registry.find(ActionId("places.saved.list")) != null)
    }

    @Test
    fun validatesExpenseCreationRequest() {
        val request = ActionRequest(
            operationId = OperationId("operation-1"),
            sessionId = SessionId("session-1"),
            actionId = ActionId("budget.expense.create"),
            contractVersion = 1,
            parameters = mapOf(
                "amountRub" to ActionParameterValue.Integer(2500),
                "categoryKey" to ActionParameterValue.Text("GROCERY")
            ),
            createdAtEpochMillis = 1L
        )

        val result = registry.validate(request)

        assertTrue(result is ActionValidationResult.Valid)
        val validResult = result as ActionValidationResult.Valid
        assertEquals(
            ConfirmationPolicy.REQUIRED,
            validResult.definition.confirmationPolicy
        )
        assertEquals(
            SecurityCategory.UNCLASSIFIED,
            validResult.definition.securityCategory
        )
    }

    @Test
    fun rejectsMissingRequiredParametersAndNonPositiveAmounts() {
        val request = ActionRequest(
            operationId = OperationId("operation-2"),
            sessionId = SessionId("session-1"),
            actionId = ActionId("budget.expense.create"),
            contractVersion = 1,
            parameters = mapOf(
                "amountRub" to ActionParameterValue.Integer(0)
            ),
            createdAtEpochMillis = 1L
        )

        val result = registry.validate(request)

        assertTrue(result is ActionValidationResult.Invalid)
        val errors = (result as ActionValidationResult.Invalid).errors
        assertTrue(errors.any { it.contains("categoryKey") })
        assertTrue(errors.any { it.contains("minimum") })
    }

    @Test
    fun rejectsUnknownParametersAndWrongTypes() {
        val request = ActionRequest(
            operationId = OperationId("operation-3"),
            sessionId = SessionId("session-1"),
            actionId = ActionId("budget.expense.create"),
            contractVersion = 1,
            parameters = mapOf(
                "amountRub" to ActionParameterValue.Text("2500"),
                "categoryKey" to ActionParameterValue.Text("GROCERY"),
                "unexpected" to ActionParameterValue.BooleanValue(true)
            ),
            createdAtEpochMillis = 1L
        )

        val result = registry.validate(request)

        assertTrue(result is ActionValidationResult.Invalid)
        val errors = (result as ActionValidationResult.Invalid).errors
        assertTrue(errors.any { it.contains("unexpected") })
        assertTrue(errors.any { it.contains("amountRub") && it.contains("INTEGER") })
    }

    @Test
    fun rejectsUnsupportedContractVersion() {
        val request = ActionRequest(
            operationId = OperationId("operation-4"),
            sessionId = SessionId("session-1"),
            actionId = ActionId("budget.summary.read"),
            contractVersion = 2,
            parameters = emptyMap(),
            createdAtEpochMillis = 1L
        )

        val result = registry.validate(request)

        assertTrue(result is ActionValidationResult.Invalid)
        assertTrue(
            (result as ActionValidationResult.Invalid).errors
                .any { it.contains("Unsupported contract version") }
        )
    }

    @Test
    fun unknownActionsAreRejected() {
        val request = ActionRequest(
            operationId = OperationId("operation-5"),
            sessionId = SessionId("session-1"),
            actionId = ActionId("profile.preferences.update"),
            contractVersion = 1,
            parameters = emptyMap(),
            createdAtEpochMillis = 1L
        )

        assertTrue(registry.validate(request) is ActionValidationResult.Invalid)
    }

    @Test
    fun onlyCompletedOperationIsSuccessful() {
        val completed = ActionExecutionResult(
            operationId = OperationId("operation-6"),
            status = OperationStatus.COMPLETED,
            message = "Расход сохранён."
        )
        val awaiting = ActionExecutionResult(
            operationId = OperationId("operation-7"),
            status = OperationStatus.AWAITING_CONFIRMATION,
            message = "Ожидается подтверждение."
        )
        val failed = ActionExecutionResult(
            operationId = OperationId("operation-8"),
            status = OperationStatus.FAILED,
            message = "Не удалось сохранить расход."
        )

        assertTrue(completed.success)
        assertFalse(awaiting.success)
        assertFalse(failed.success)
    }

    @Test
    fun rejectsDuplicateActionIdentifiers() {
        val definition = registry.find(ActionId("budget.summary.read"))!!
        val duplicateRejected = runCatching {
            ActionRegistry(listOf(definition, definition))
        }.isFailure

        assertTrue(duplicateRejected)
    }
}
