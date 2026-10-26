package org.fossify.voicerecorder.recorder

import org.junit.Assert.*
import org.junit.Test

class RecordingNameTest {
    private fun name(value: String) = RecordingPresetSettings.safeName(value, "Recording_123")
    @Test fun blankGetsAutomaticNameAndQoaExtensionIsNotDuplicated() {
        assertEquals("Recording_123.qoa", name(""))
        assertEquals("Recording_123.qoa", name("   ...  "))
        assertEquals("Scene 12.qoa", name("  Scene 12.qoa  "))
        assertEquals("Scene 12.qoa", name("Scene 12.QOA"))
    }
    @Test fun userInputIsAlwaysOneBoundedFilenameNotAPathOrUri() {
        for (input in listOf("../escape", "/absolute/path", "content://documents/tree/12", "C:\\files\\clip", "a\u0000b\nc", "file?x=1:2|*")) {
            val result = name(input)
            assertFalse(result.contains('/'))
            assertFalse(result.contains('\\'))
            assertFalse(result.any { it.isISOControl() })
            assertFalse(result.startsWith('.'))
            assertTrue(result.endsWith(".qoa"))
            assertTrue(result.length <= 124)
        }
        assertEquals(124, name("a".repeat(300)).length)
        assertEquals("音声 🎙.qoa", name("音声 🎙"))
    }
}
