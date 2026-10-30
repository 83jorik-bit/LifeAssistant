package com.lifenavigator.app.data.model

data class CalendarEvent(
    val id: String,
    val title: String,
    val startsAtEpochMillis: Long,
    val endsAtEpochMillis: Long? = null,
    val notes: String? = null,
    val reminderAtEpochMillis: Long? = null
)
