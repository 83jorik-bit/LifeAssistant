package com.lifenavigator.app.data.execution

import android.content.Context

/**
 * Thread-safe, process-scoped lazy instance holder.
 *
 * A ConfirmationManager performs interrupted-execution recovery in its
 * constructor. Recreating it for every user message could misclassify a live
 * operation in EXECUTING as an interrupted operation. One manager instance per
 * application process makes recovery a process-start action, not a message action.
 */
internal class ProcessScopedLazy<T : Any> {
    @Volatile
    private var instance: T? = null

    fun get(factory: () -> T): T {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: factory().also { instance = it }
        }
    }
}

/** Single confirmation lifecycle owner for the current single-process app. */
object ConfirmationManagerProvider {
    private val holder = ProcessScopedLazy<ConfirmationManager>()

    fun get(context: Context): ConfirmationManager =
        holder.get {
            ConfirmationManager(
                SharedPreferencesOperationStateStore(context.applicationContext)
            )
        }
}
