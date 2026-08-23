package com.itdeti.assistant

data class NotificationLog(
    val source: String,
    val sender: String,
    val message: String,
    val timestamp: Long
)