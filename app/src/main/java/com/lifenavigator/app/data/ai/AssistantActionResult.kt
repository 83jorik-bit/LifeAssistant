package com.lifenavigator.app.data.ai

/**
 * Typed result of an application action.
 *
 * A handled conversational reply is not automatically a successful mutation:
 * confirmation and clarification are represented explicitly.
 */
sealed interface AssistantActionResult {
    data class Completed(val message: String) : AssistantActionResult
    data class NeedsConfirmation(val message: String) : AssistantActionResult
    data class NeedsClarification(val message: String) : AssistantActionResult
    data class Failed(val message: String) : AssistantActionResult
    data object NotHandled : AssistantActionResult
}
