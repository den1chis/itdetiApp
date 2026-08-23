package com.itdeti.assistant

data class ScheduleEvent(
    val itemId: String,
    val itemType: String,
    val title: String,
    val studentName: String?,
    val startTime: Long,
    val endTime: Long?,
    val lessonKind: String?
)