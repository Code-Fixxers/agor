package live.agor.jetbrains.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Test

class AgorThemeTest {
    @Test
    fun `uses connected chat shell dark palette tokens`() {
        assertEquals(0x090911, AgorTheme.SurfaceBase.rgb and 0xFFFFFF)
        assertEquals(0x101015, AgorTheme.SurfacePanel.rgb and 0xFFFFFF)
        assertEquals(0x27242C, AgorTheme.BorderSubtle.rgb and 0xFFFFFF)
        assertEquals(0xD6ADC4, AgorTheme.Accent.rgb and 0xFFFFFF)
    }
}
