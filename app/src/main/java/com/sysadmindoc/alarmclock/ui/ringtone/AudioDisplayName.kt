package com.sysadmindoc.alarmclock.ui.ringtone

internal fun audioDisplayName(title: String?, displayName: String?, fallback: String): String =
    title?.trim()?.takeIf { it.isNotEmpty() }
        ?: displayName?.trim()?.takeIf { it.isNotEmpty() }?.replace(Regex("(?i)\\.(webm|m4a|mp4|mp3|ogg|opus|wav|aac|flac)$"), "")?.takeIf { it.isNotBlank() }
        ?: fallback
