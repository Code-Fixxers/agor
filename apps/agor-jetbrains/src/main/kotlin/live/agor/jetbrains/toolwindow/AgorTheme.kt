package live.agor.jetbrains.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.border.Border

internal object AgorTheme {
    val SurfaceBase = color(0x090911)
    val SurfacePanel = color(0x101015)
    val SurfaceRaised = color(0x17151B)
    val BorderSubtle = color(0x27242C)
    val BorderStrong = color(0x302D35)
    val TextPrimary = color(0xF5EEF3)
    val TextSecondary = color(0xB7AEB8)
    val TextMuted = color(0x8F8791)
    val Accent = color(0xD6ADC4)
    val AccentMuted = color(0x735D69)
    val Success = color(0x86C6A3)
    val Warning = color(0xD8BD75)
    val Error = color(0xD98A8A)

    val PanelBorder: Border = JBUI.Borders.customLine(BorderSubtle)
    val LeftBorder: Border = JBUI.Borders.customLine(BorderSubtle, 0, 1, 0, 0)
    val RightBorder: Border = JBUI.Borders.customLine(BorderSubtle, 0, 0, 0, 1)
    val TopBorder: Border = JBUI.Borders.customLine(BorderSubtle, 1, 0, 0, 0)

    fun panel(background: Color = SurfacePanel): JPanel =
        JPanel().apply {
            isOpaque = true
            this.background = background
            foreground = TextPrimary
        }

    fun label(text: String, size: Float = 12f, bold: Boolean = false, color: Color = TextSecondary): JLabel =
        JLabel(text).apply {
            foreground = color
            font = font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
        }

    fun styleInput(field: JTextField) {
        field.background = SurfaceRaised
        field.foreground = TextPrimary
        field.caretColor = Accent
        field.border = JBUI.Borders.compound(PanelBorder, JBUI.Borders.empty(6, 8))
    }

    fun styleIconButton(button: JButton, active: Boolean = false) {
        button.isOpaque = true
        button.background = if (active) AccentMuted else SurfaceBase
        button.foreground = if (active) TextPrimary else TextSecondary
        button.border = JBUI.Borders.empty(8)
        button.isFocusPainted = false
        button.hideActionText = true
    }

    fun styleActionButton(button: JButton, primary: Boolean = false) {
        button.isOpaque = true
        button.background = if (primary) AccentMuted else SurfaceRaised
        button.foreground = TextPrimary
        button.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(if (primary) Accent else BorderStrong),
            JBUI.Borders.empty(6, 10),
        )
        button.isFocusPainted = false
    }

    fun styleComponent(component: JComponent, background: Color = SurfacePanel) {
        component.isOpaque = true
        component.background = background
        component.foreground = TextPrimary
    }

    private fun color(rgb: Int): JBColor =
        JBColor(Color(rgb), Color(rgb))
}
