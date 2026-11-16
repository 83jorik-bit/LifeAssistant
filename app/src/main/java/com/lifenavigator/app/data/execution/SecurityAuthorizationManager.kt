package com.lifenavigator.app.data.execution

import java.util.UUID

enum class AuthenticationLevel {
    NONE,
    STANDARD,
    STRONG
}

data class SecurityPolicy(
    val userMinimumAuthByDomain: Map<ActionDomain, AuthenticationLevel> = emptyMap()
) {
    /**
     * User settings may raise protection, but never lower the mandatory minimum
     * associated with an explicitly classified critical operation.
     */
    fun requiredAuthentication(definition: ActionDefinition): AuthenticationLevel {
        val criticalMinimum = when (definition.securityCategory) {
            SecurityCategory.A -> AuthenticationLevel.STRONG
            SecurityCategory.B -> AuthenticationLevel.STANDARD
            SecurityCategory.UNCLASSIFIED -> AuthenticationLevel.NONE
        }
        val userMinimum = userMinimumAuthByDomain[definition.domain] ?: AuthenticationLevel.NONE
        return maxOf(criticalMinimum, userMinimum)
    }
}

data class SecurityContext(
    val authorizedDomains: Set<ActionDomain>,
    val authenticationLevel: AuthenticationLevel,
    /** Strong step-up evidence must be bound to the operation that requested it. */
    val authenticatedOperationId: OperationId? = null,
    val confirmedOperationId: OperationId? = null,
    val warningAcknowledgedOperationId: OperationId? = null
)

sealed interface SecurityDecision {
    data object Allowed : SecurityDecision
    data class Denied(val code: String, val reason: String) : SecurityDecision
    data class RequiresClassification(val actionId: ActionId) : SecurityDecision
    data class RequiresStepUp(
        val operationId: OperationId,
        val requiredLevel: AuthenticationLevel
    ) : SecurityDecision
    data class RequiresWarningAcknowledgement(
        val operationId: OperationId,
        val warning: String
    ) : SecurityDecision
    data class RequiresConfirmation(val operationId: OperationId) : SecurityDecision
}

data class SecurityEvent(
    val eventId: String,
    val operationId: OperationId,
    val sessionId: SessionId,
    val actionId: ActionId,
    val category: SecurityCategory,
    val decisionCode: String,
    val occurredAtEpochMillis: Long,
    val detail: String
)

fun interface SecurityEventSink {
    /** Returns false when the event could not be durably recorded. */
    fun append(event: SecurityEvent): Boolean
}

/**
 * Central policy evaluator for permissions, category-specific protection,
 * operation-bound step-up, explicit warnings and confirmation.
 *
 * It deliberately does not infer A/B classification. An unclassified mutation
 * is returned as RequiresClassification and cannot be authorized by this policy.
 */
class SecurityAuthorizationManager(
    private val registry: ActionRegistry = LifeAssistantActionRegistry.instance,
    private val policy: SecurityPolicy = SecurityPolicy(),
    private val eventSink: SecurityEventSink,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {

    fun evaluate(
        request: ActionRequest,
        context: SecurityContext
    ): SecurityDecision {
        val definition = registry.find(request.actionId)
        val category = definition?.securityCategory ?: SecurityCategory.UNCLASSIFIED

        val decision = when (val validation = registry.validate(request)) {
            is ActionValidationResult.Invalid ->
                SecurityDecision.Denied(
                    code = "INVALID_OPERATION",
                    reason = validation.errors.joinToString("; ")
                )
            is ActionValidationResult.Valid ->
                evaluateValidated(request, validation.definition, context)
        }

        val event = SecurityEvent(
            eventId = UUID.randomUUID().toString(),
            operationId = request.operationId,
            sessionId = request.sessionId,
            actionId = request.actionId,
            category = category,
            decisionCode = decision.code(),
            occurredAtEpochMillis = nowMillis(),
            detail = decision.detail()
        )

        return if (eventSink.append(event)) {
            decision
        } else {
            SecurityDecision.Denied(
                code = "AUDIT_UNAVAILABLE",
                reason = "Security event could not be durably recorded; operation is blocked"
            )
        }
    }

    private fun evaluateValidated(
        request: ActionRequest,
        definition: ActionDefinition,
        context: SecurityContext
    ): SecurityDecision {
        if (definition.domain !in context.authorizedDomains) {
            return SecurityDecision.Denied(
                code = "DOMAIN_PERMISSION_DENIED",
                reason = "No permission for domain ${definition.domain}"
            )
        }

        if (definition.kind == ActionKind.MUTATION &&
            definition.securityCategory == SecurityCategory.UNCLASSIFIED
        ) {
            return SecurityDecision.RequiresClassification(definition.id)
        }

        val requiredLevel = policy.requiredAuthentication(definition)
        val operationBoundStrongAuthMissing =
            requiredLevel == AuthenticationLevel.STRONG &&
                context.authenticatedOperationId != request.operationId

        if (context.authenticationLevel < requiredLevel || operationBoundStrongAuthMissing) {
            return SecurityDecision.RequiresStepUp(request.operationId, requiredLevel)
        }

        if (definition.securityCategory == SecurityCategory.A &&
            context.warningAcknowledgedOperationId != request.operationId
        ) {
            return SecurityDecision.RequiresWarningAcknowledgement(
                operationId = request.operationId,
                warning = "This operation is irreversible or difficult to reverse. Review its consequences."
            )
        }

        val confirmationRequired =
            definition.confirmationPolicy == ConfirmationPolicy.REQUIRED ||
                definition.securityCategory == SecurityCategory.A ||
                definition.securityCategory == SecurityCategory.B

        if (confirmationRequired && context.confirmedOperationId != request.operationId) {
            return SecurityDecision.RequiresConfirmation(request.operationId)
        }

        return SecurityDecision.Allowed
    }

    private fun SecurityDecision.code(): String = when (this) {
        SecurityDecision.Allowed -> "ALLOWED"
        is SecurityDecision.Denied -> code
        is SecurityDecision.RequiresClassification -> "REQUIRES_CLASSIFICATION"
        is SecurityDecision.RequiresStepUp -> "REQUIRES_STEP_UP_${requiredLevel.name}"
        is SecurityDecision.RequiresWarningAcknowledgement -> "REQUIRES_WARNING_ACK"
        is SecurityDecision.RequiresConfirmation -> "REQUIRES_CONFIRMATION"
    }

    private fun SecurityDecision.detail(): String = when (this) {
        SecurityDecision.Allowed -> "Policy evaluation allowed the operation"
        is SecurityDecision.Denied -> reason
        is SecurityDecision.RequiresClassification -> "Action requires explicit A/B classification"
        is SecurityDecision.RequiresStepUp -> "Operation-bound authentication level: ${requiredLevel.name}"
        is SecurityDecision.RequiresWarningAcknowledgement -> warning
        is SecurityDecision.RequiresConfirmation -> "Explicit confirmation required for this operation"
    }
}
