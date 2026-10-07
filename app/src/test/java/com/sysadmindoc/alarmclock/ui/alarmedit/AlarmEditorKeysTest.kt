package com.sysadmindoc.alarmclock.ui.alarmedit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AlarmEditorKeysTest {
    @Test fun everyLazySectionReferenceIsUniqueAcrossPages() {
        val directory = File("src/main/java/com/sysadmindoc/alarmclock/ui/alarmedit")
            .takeIf { it.exists() } ?: File("app/src/main/java/com/sysadmindoc/alarmclock/ui/alarmedit")
        val sections = directory.listFiles()!!.filter { it.name.endsWith("Sections.kt") || it.name == "AlarmEditAdvancedSection.kt" }
            .flatMap { Regex("SettingsSection\\(editorPage, AlarmEditorSection\\.(\\w+)\\)")
                .findAll(it.readText()).map { match -> match.groupValues[1] }.toList() }
        assertEquals(AlarmEditorSection.entries.size, sections.size)
        assertEquals("Duplicate LazyColumn section key", sections.size, sections.toSet().size)
    }
}
