package com.lifenavigator.app.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuthorizationManagerTest {

    @Test
    fun unclassifiedMutationCannotBeSilentlyAuthorized() {
        val actionId = ActionId("budget.expense.import")
        val registry = registryFor(
            ActionDefinition(
                id = actionId,
                domain = ActionDomain.BUDGET,
                kind = ActionKind.MUTATION,
                parameters = emptyList(),
                confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                securityCategory = SecurityCategory.UNCLASSIFIED,
                executorKey = "budget"
            )
        )
        val request = request(actionId, emptyMap())
        val events = mutableListOf<SecurityEvent>()
        val manager = SecurityAuthorizationManager(
            registry = registry,
            eventSink = SecurityEventSink { events += it; true }
        )
        val context = SecurityContext(
            authorizedDomains = setOf(ActionDomain.BUDGET),
            authenticationLevel = AuthenticationLevel.STRONG,
            authenticatedOperationId = request.operationId,
            confirmedOperationId = request.operationId
        )

        val decision = manager.evaluate(request, context)

        assertTrue(decision is SecurityDecision.RequiresClassification)
        assertEquals("REQUIRES_CLASSIFICATION", events.single().decisionCode)
    }

    @Test
    fun deniesOperationsOutsideGrantedDomain() {
        val request = request(
            actionId = ActionId("task.create"),
            parameters = mapOf(
                "title" to ActionParameterValue.Text("Позвонить")
            )
        )
        val manager = SecurityAuthorizationManager(
            eventSink = SecurityEventSink { true }
        )

        val decision = manager.evaluate(
            request,
            SecurityContext(
                authorizedDomains = setOf(ActionDomain.BUDGET),
                authenticationLevel = AuthenticationLevel.STANDARD
            )
        )

        assertEquals("DOMAIN_PERMISSION_DENIED", (decision as SecurityDecision.Denied).code)
    }

    @Test
    fun categoryARequiresStrongOperationBoundAuthenticationWarningAndConfirmation() {
        val actionId = ActionId("profile.account.delete")
        val registry = registryFor(
            ActionDefinition(
                id = actionId,
                domain = ActionDomain.PROFILE,
                kind = ActionKind.MUTATION,
                parameters = emptyList(),
                confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                securityCategory = SecurityCategory.A,
                executorKey = "profile"
            )
        )
        val manager = SecurityAuthorizationManager(
            registry = registry,
            eventSink = SecurityEventSink { true }
        )
        val request = request(actionId, emptyMap())
        val baseContext = SecurityContext(
            authorizedDomains = setOf(ActionDomain.PROFILE),
            authenticationLevel = AuthenticationLevel.STANDARD
        )

        val stepUp = manager.evaluate(request, baseContext)
        assertTrue(stepUp is SecurityDecision.RequiresStepUp)
        assertEquals(AuthenticationLevel.STRONG, (stepUp as SecurityDecision.RequiresStepUp).requiredLevel)

        val operationBoundStrong = baseContext.copy(
            authenticationLevel = AuthenticationLevel.STRONG,
            authenticatedOperationId = request.operationId
        )
        val warning = manager.evaluate(request, operationBoundStrong)
        assertTrue(warning is SecurityDecision.RequiresWarningAcknowledgement)

        val acknowledged = operationBoundStrong.copy(
            warningAcknowledgedOperationId = request.operationId
        )
        val confirmation = manager.evaluate(request, acknowledged)
        assertTrue(confirmation is SecurityDecision.RequiresConfirmation)

        val fullyApproved = acknowledged.copy(
            confirmedOperationId = request.operationId
        )
        assertEquals(SecurityDecision.Allowed, manager.evaluate(request, fullyApproved))
    }

    @Test
    fun categoryBHasMandatoryMinimumThatUserCannotLower() {
        val actionId = ActionId("profile.sensitive.change")
        val registry = registryFor(
            ActionDefinition(
                id = actionId,
                domain = ActionDomain.PROFILE,
                kind = ActionKind.MUTATION,
                parameters = emptyList(),
                confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                securityCategory = SecurityCategory.B,
                executorKey = "profile"
            )
        )
        val manager = SecurityAuthorizationManager(
            registry = registry,
            policy = SecurityPolicy(
                userMinimumAuthByDomain = mapOf(
                    ActionDomain.PROFILE to AuthenticationLevel.NONE
                )
            ),
            eventSink = SecurityEventSink { true }
        )
        val request = request(actionId, emptyMap())

        val decision = manager.evaluate(
            request,
            SecurityContext(
                authorizedDomains = setOf(ActionDomain.PROFILE),
                authenticationLevel = AuthenticationLevel.NONE
            )
        )

        assertTrue(decision is SecurityDecision.RequiresStepUp)
        assertEquals(AuthenticationLevel.STANDARD, (decision as SecurityDecision.RequiresStepUp).requiredLevel)
    }

    @Test
    fun userMayRaiseButNotLowerTheCategoryMinimum() {
        val actionId = ActionId("profile.sensitive.change")
        val registry = registryFor(
            ActionDefinition(
                id = actionId,
                domain = ActionDomain.PROFILE,
                kind = ActionKind.MUTATION,
                parameters = emptyList(),
                confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                securityCategory = SecurityCategory.B,
                executorKey = "profile"
            )
        )
        val manager = SecurityAuthorizationManager(
            registry = registry,
            policy = SecurityPolicy(
                userMinimumAuthByDomain = mapOf(
                    ActionDomain.PROFILE to AuthenticationLevel.STRONG
                )
            ),
            eventSink = SecurityEventSink { true }
        )
        val request = request(actionId, emptyMap())

        val decision = manager.evaluate(
            request,
            SecurityContext(
                authorizedDomains = setOf(ActionDomain.PROFILE),
                authenticationLevel = AuthenticationLevel.STANDARD
            )
        )

        assertTrue(decision is SecurityDecision.RequiresStepUp)
        assertEquals(AuthenticationLevel.STRONG, (decision as SecurityDecision.RequiresStepUp).requiredLevel)
    }


    @Test
    fun standardMutationNeedsNeitherStepUpNorExplicitConfirmation() {
        val actionId = ActionId("tasks.quick.create")
        val registry = registryFor(
            ActionDefinition(
                id = actionId,
                domain = ActionDomain.TASKS,
                kind = ActionKind.MUTATION,
                parameters = emptyList(),
                confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                securityCategory = SecurityCategory.STANDARD,
                executorKey = "tasks"
            )
        )
        val manager = SecurityAuthorizationManager(
            registry = registry,
            eventSink = SecurityEventSink { true }
        )

        val decision = manager.evaluate(
            request(actionId, emptyMap()),
            SecurityContext(
                authorizedDomains = setOf(ActionDomain.TASKS),
                authenticationLevel = AuthenticationLevel.NONE
            )
        )

        assertEquals(SecurityDecision.Allowed, decision)
    }

    @Test
    fun readOperationDoesNotRequireMutationConfirmation() {
        val actionId = ActionId("places.saved.list")
        val manager = SecurityAuthorizationManager(
            eventSink = SecurityEventSink { true }
        )
        val request = request(actionId, emptyMap())

        val decision = manager.evaluate(
            request,
            SecurityContext(
                authorizedDomains = setOf(ActionDomain.PLACES),
                authenticationLevel = AuthenticationLevel.NONE
            )
        )

        assertEquals(SecurityDecision.Allowed, decision)
    }

    @Test
    fun auditFailureBlocksOtherwiseAllowedOperation() {
        val actionId = ActionId("places.saved.list")
        val manager = SecurityAuthorizationManager(
            eventSink = SecurityEventSink { false }
        )
        val request = request(actionId, emptyMap())

        val decision = manager.evaluate(
            request,
            SecurityContext(
                authorizedDomains = setOf(ActionDomain.PLACES),
                authenticationLevel = AuthenticationLevel.NONE
            )
        )

        assertEquals("AUDIT_UNAVAILABLE", (decision as SecurityDecision.Denied).code)
    }

    private fun request(
        actionId: ActionId,
        parameters: Map<String, ActionParameterValue>
    ) = ActionRequest(
        operationId = OperationId("security-operation"),
        sessionId = SessionId("security-session"),
        actionId = actionId,
        contractVersion = 1,
        parameters = parameters,
        createdAtEpochMillis = 1L
    )

    private fun registryFor(definition: ActionDefinition) =
        ActionRegistry(listOf(definition))
}
