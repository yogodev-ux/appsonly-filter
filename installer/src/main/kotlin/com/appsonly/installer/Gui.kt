package com.appsonly.installer

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants

/** The setup window: Check phone -> Install -> done. */
object Gui {
    fun show(adbPath: String?, apk: File?, logDir: File) = SwingUtilities.invokeLater {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
        Window(adbPath, apk, logDir).isVisible = true
    }

    private class Window(val adbPath: String?, val apk: File?, val logDir: File) : JFrame("Apps Only setup") {
        val status = JLabel("Connect the phone with a USB cable, then press Check phone.")
        val phoneLabel = JLabel(" ")
        val logArea = JTextArea(16, 60).apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
        val progress = JProgressBar().apply { isIndeterminate = false; isVisible = false }
        val checkBtn = JButton("Check phone")
        val installBtn = JButton("Install").apply { isEnabled = false }
        val logBtn = JButton("Open log folder")
        var lastCheck: PhoneCheck? = null

        init {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            val top = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(16, 16, 8, 16)
                add(JLabel("Apps Only").apply { font = font.deriveFont(Font.BOLD, 22f) })
                add(JLabel("Turns this phone into an apps-only phone. The customer picks the allowed apps on the phone at the end."))
                add(JLabel(" "))
                add(status.apply { font = font.deriveFont(Font.BOLD) })
                add(phoneLabel)
            }
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                add(progress); add(logBtn); add(checkBtn); add(installBtn)
            }
            contentPane.layout = BorderLayout()
            contentPane.add(top, BorderLayout.NORTH)
            contentPane.add(JScrollPane(logArea).apply {
                border = BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16), border)
            }, BorderLayout.CENTER)
            contentPane.add(buttons, BorderLayout.SOUTH)
            minimumSize = Dimension(720, 480)
            pack()
            setLocationRelativeTo(null)

            checkBtn.addActionListener { runCheck() }
            installBtn.addActionListener { runInstall() }
            logBtn.addActionListener {
                logDir.mkdirs()
                try { Desktop.getDesktop().open(logDir) } catch (_: Exception) {}
            }
            if (adbPath == null) fatal("The installer is incomplete: the platform-tools folder is missing.")
            else if (apk == null) fatal("The installer is incomplete: apps-only.apk is missing.")
        }

        fun fatal(msg: String) {
            status.text = msg; status.foreground = Color(0xB00020)
            checkBtn.isEnabled = false; installBtn.isEnabled = false
        }

        fun append(prefix: String, t: String) = SwingUtilities.invokeLater {
            logArea.append("$prefix$t\n"); logArea.caretPosition = logArea.document.length
        }

        val listener = object : SetupListener {
            override fun step(text: String) { append("", text); SwingUtilities.invokeLater { status.text = "$text..." } }
            override fun info(text: String) = append("    ", text)
            override fun warn(text: String) = append("  ! ", text)
            override fun error(text: String) = append("  X ", text)
        }

        fun engine() = SetupEngine(Adb(adbPath!!), log = SetupLog(logDir), stateDir = logDir.parentFile ?: logDir, listener = listener)

        fun busy(on: Boolean) {
            progress.isVisible = on; progress.isIndeterminate = on
            checkBtn.isEnabled = !on
            installBtn.isEnabled = !on && lastCheck?.canInstall == true
        }

        fun runCheck() {
            busy(true)
            Thread {
                val c = engine().check()
                SwingUtilities.invokeLater {
                    lastCheck = c
                    phoneLabel.text = if (c.serial.isNotEmpty()) "Phone: ${c.name}" else " "
                    status.foreground = if (c.canInstall) Color(0x1B5E20) else Color(0xB00020)
                    status.text = when {
                        !c.canInstall -> c.blockers.first()
                        c.alreadyInstalled -> "Apps Only is already on this phone. Press Install to update it."
                        c.warnings.isNotEmpty() -> "Ready, with a warning: ${c.warnings.first()}"
                        else -> "Ready. Press Install."
                    }
                    busy(false)
                }
            }.start()
        }

        fun runInstall() {
            val c = lastCheck ?: return
            if (!c.alreadyInstalled) {
                val answer = JOptionPane.showConfirmDialog(
                    this,
                    "Set up Apps Only on ${c.name}?\n\nAfter the customer picks the allowed apps on the phone,\n" +
                        "they can't be changed, and the phone can't be reset to undo it.",
                    "Apps Only setup", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE)
                if (answer != JOptionPane.OK_OPTION) return
            }
            busy(true)
            Thread {
                val ok = engine().install(apk!!)
                SwingUtilities.invokeLater {
                    status.foreground = if (ok) Color(0x1B5E20) else Color(0xB00020)
                    status.text = if (ok) "Done. Finish on the phone: pick the apps, then tap Lock."
                        else "Setup did not finish. See the messages below."
                    lastCheck = null
                    busy(false)
                    installBtn.isEnabled = false
                }
            }.start()
        }
    }
}
