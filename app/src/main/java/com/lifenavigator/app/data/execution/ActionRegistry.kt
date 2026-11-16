package com.lifenavigator.app.data.execution

/**
 * Immutable registry of the operations the application knows how to describe.
 *
 * This first-stage registry validates contracts only. It does not execute actions
 * and is intentionally not wired into the legacy dispatcher yet.
 */
class ActionRegistry(definitions: Collection<ActionDefinition>) {

    private val definitionsById: Map<ActionId, ActionDefinition>

    init {
        val duplicateIds = definitions
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Duplicate action identifiers: ${duplicateIds.joinToString { it.value }}"
        }
        definitionsById = definitions.associateBy { it.id }
    }

    fun find(actionId: ActionId): ActionDefinition? = definitionsById[actionId]

    fun all(): List<ActionDefinition> = definitionsById.values.sortedBy { it.id.value }

    fun validate(request: ActionRequest): ActionValidationResult {
        val definition = find(request.actionId)
            ?: return ActionValidationResult.Invalid(
                listOf("Action '${request.actionId.value}' is not registered")
            )

        val errors = mutableListOf<String>()

        if (request.contractVersion != definition.contractVersion) {
            errors += "Unsupported contract version ${request.contractVersion}; expected ${definition.contractVersion}"
        }

        val parameterDefinitions = definition.parameters.associateBy { it.name }
        val unknownParameters = request.parameters.keys - parameterDefinitions.keys
        unknownParameters.sorted().forEach { name ->
            errors += "Unknown parameter '$name'"
        }

        definition.parameters.forEach { parameter ->
            val value = request.parameters[parameter.name]
            if (value == null) {
                if (parameter.required) errors += "Missing required parameter '${parameter.name}'"
                return@forEach
            }

            when (parameter.type) {
                ActionParameterType.TEXT -> {
                    if (value !is ActionParameterValue.Text) {
                        errors += "Parameter '${parameter.name}' must be TEXT"
                    } else {
                        if (!parameter.allowBlank && value.value.isBlank()) {
                            errors += "Parameter '${parameter.name}' must not be blank"
                        }
                        if (
                            parameter.allowedTextValues.isNotEmpty() &&
                            value.value !in parameter.allowedTextValues
                        ) {
                            errors += "Parameter '${parameter.name}' has an unsupported value"
                        }
                    }
                }

                ActionParameterType.INTEGER -> {
                    if (value !is ActionParameterValue.Integer) {
                        errors += "Parameter '${parameter.name}' must be INTEGER"
                    } else {
                        if (
                            parameter.minimumInteger != null &&
                            value.value < parameter.minimumInteger
                        ) {
                            errors += "Parameter '${parameter.name}' is below its minimum"
                        }
                        if (
                            parameter.maximumInteger != null &&
                            value.value > parameter.maximumInteger
                        ) {
                            errors += "Parameter '${parameter.name}' exceeds its maximum"
                        }
                    }
                }

                ActionParameterType.BOOLEAN -> {
                    if (value !is ActionParameterValue.BooleanValue) {
                        errors += "Parameter '${parameter.name}' must be BOOLEAN"
                    }
                }
            }
        }

        return if (errors.isEmpty()) {
            ActionValidationResult.Valid(definition)
        } else {
            ActionValidationResult.Invalid(errors)
        }
    }
}

sealed interface ActionValidationResult {
    data class Valid(val definition: ActionDefinition) : ActionValidationResult
    data class Invalid(val errors: List<String>) : ActionValidationResult
}

/**
 * Initial catalog of currently identifiable LifeAssistant operations.
 *
 * Every registered operation has an explicit security classification. Routine
 * mutations do not require a second confirmation; category A and B mutations do.
 */
object LifeAssistantActionRegistry {

    val instance: ActionRegistry by lazy {
        ActionRegistry(
            listOf(
                ActionDefinition(
                    id = ActionId("budget.expense.create"),
                    domain = ActionDomain.BUDGET,
                    kind = ActionKind.MUTATION,
                    parameters = listOf(
                        ActionParameterDefinition(
                            name = "amountRub",
                            type = ActionParameterType.INTEGER,
                            minimumInteger = 1
                        ),
                        ActionParameterDefinition(
                            name = "categoryKey",
                            type = ActionParameterType.TEXT
                        ),
                        ActionParameterDefinition(
                            name = "note",
                            type = ActionParameterType.TEXT,
                            required = false
                        )
                    ),
                    securityCategory = SecurityCategory.B,
                    confirmationPolicy = ConfirmationPolicy.REQUIRED,
                    executorKey = "budget"
                ),
                ActionDefinition(
                    id = ActionId("budget.expense.delete"),
                    domain = ActionDomain.BUDGET,
                    kind = ActionKind.MUTATION,
                    parameters = listOf(
                        ActionParameterDefinition(
                            name = "expenseId",
                            type = ActionParameterType.TEXT
                        )
                    ),
                    securityCategory = SecurityCategory.A,
                    confirmationPolicy = ConfirmationPolicy.REQUIRED,
                    executorKey = "budget"
                ),
                ActionDefinition(
                    id = ActionId("budget.income.set"),
                    domain = ActionDomain.BUDGET,
                    kind = ActionKind.MUTATION,
                    parameters = listOf(
                        ActionParameterDefinition(
                            name = "amountRub",
                            type = ActionParameterType.INTEGER,
                            minimumInteger = 1
                        )
                    ),
                    securityCategory = SecurityCategory.B,
                    confirmationPolicy = ConfirmationPolicy.REQUIRED,
                    executorKey = "budget"
                ),
                ActionDefinition(
                    id = ActionId("budget.limit.set"),
                    domain = ActionDomain.BUDGET,
                    kind = ActionKind.MUTATION,
                    parameters = listOf(
                        ActionParameterDefinition(
                            name = "categoryKey",
                            type = ActionParameterType.TEXT
                        ),
                        ActionParameterDefinition(
                            name = "amountRub",
                            type = ActionParameterType.INTEGER,
                            minimumInteger = 1
                        )
                    ),
                    securityCategory = SecurityCategory.B,
                    confirmationPolicy = ConfirmationPolicy.REQUIRED,
                    executorKey = "budget"
                ),
                ActionDefinition(
                    id = ActionId("task.create"),
                    domain = ActionDomain.TASKS,
                    kind = ActionKind.MUTATION,
                    parameters = listOf(
                        ActionParameterDefinition(
                            name = "title",
                            type = ActionParameterType.TEXT
                        ),
                        ActionParameterDefinition(
                            name = "dueAtEpochMillis",
                            type = ActionParameterType.INTEGER,
                            required = false,
                            minimumInteger = 1
                        ),
                        ActionParameterDefinition(
                            name = "reminderAtEpochMillis",
                            type = ActionParameterType.INTEGER,
                            required = false,
                            minimumInteger = 1
                        ),
                        ActionParameterDefinition(
                            name = "priority",
                            type = ActionParameterType.TEXT,
                            required = false,
                            allowedTextValues = setOf("NORMAL", "HIGH")
                        )
                    ),
                    securityCategory = SecurityCategory.STANDARD,
                    confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                    executorKey = "tasks"
                ),
                ActionDefinition(
                    id = ActionId("task.complete"),
                    domain = ActionDomain.TASKS,
                    kind = ActionKind.MUTATION,
                    parameters = listOf(
                        ActionParameterDefinition(
                            name = "taskId",
                            type = ActionParameterType.TEXT
                        )
                    ),
                    securityCategory = SecurityCategory.STANDARD,
                    confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                    executorKey = "tasks"
                ),
                ActionDefinition(
                    id = ActionId("calendar.event.create"),
                    domain = ActionDomain.CALENDAR,
                    kind = ActionKind.MUTATION,
                    parameters = listOf(
                        ActionParameterDefinition(
                            name = "title",
                            type = ActionParameterType.TEXT
                        ),
                        ActionParameterDefinition(
                            name = "startsAtEpochMillis",
                            type = ActionParameterType.INTEGER,
                            minimumInteger = 1
                        ),
                        ActionParameterDefinition(
                            name = "endsAtEpochMillis",
                            type = ActionParameterType.INTEGER,
                            required = false,
                            minimumInteger = 1
                        ),
                        ActionParameterDefinition(
                            name = "notes",
                            type = ActionParameterType.TEXT,
                            required = false
                        ),
                        ActionParameterDefinition(
                            name = "reminderAtEpochMillis",
                            type = ActionParameterType.INTEGER,
                            required = false,
                            minimumInteger = 1
                        )
                    ),
                    securityCategory = SecurityCategory.STANDARD,
                    confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                    executorKey = "calendar"
                ),
                ActionDefinition(
                    id = ActionId("budget.summary.read"),
                    domain = ActionDomain.BUDGET,
                    kind = ActionKind.READ,
                    parameters = emptyList(),
                    securityCategory = SecurityCategory.STANDARD,
                    confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                    executorKey = "budget"
                ),
                ActionDefinition(
                    id = ActionId("budget.limits.read"),
                    domain = ActionDomain.BUDGET,
                    kind = ActionKind.READ,
                    parameters = emptyList(),
                    securityCategory = SecurityCategory.STANDARD,
                    confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                    executorKey = "budget"
                ),
                ActionDefinition(
                    id = ActionId("tasks.list.read"),
                    domain = ActionDomain.TASKS,
                    kind = ActionKind.READ,
                    parameters = emptyList(),
                    securityCategory = SecurityCategory.STANDARD,
                    confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                    executorKey = "tasks"
                ),
                ActionDefinition(
                    id = ActionId("calendar.events.list"),
                    domain = ActionDomain.CALENDAR,
                    kind = ActionKind.READ,
                    parameters = emptyList(),
                    securityCategory = SecurityCategory.STANDARD,
                    confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                    executorKey = "calendar"
                ),
                ActionDefinition(
                    id = ActionId("places.saved.list"),
                    domain = ActionDomain.PLACES,
                    kind = ActionKind.READ,
                    parameters = emptyList(),
                    securityCategory = SecurityCategory.STANDARD,
                    confirmationPolicy = ConfirmationPolicy.NOT_REQUIRED,
                    executorKey = "places"
                )
            )
        )
    }
}
