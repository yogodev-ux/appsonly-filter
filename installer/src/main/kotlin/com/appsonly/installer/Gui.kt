package com.appsonly.installer

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.WindowConstants

/** The setup window: customer details, Check phone -> Install -> done. */
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
        val nameField = JTextField(24)
        val phoneField = JTextField(24)
        val emailField = JTextField(24)
        val codeField = JPasswordField(24)
        val code2Field = JPasswordField(24)

        init {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            val top = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(16, 16, 8, 16)
                add(JLabel("Apps Only").apply { font = font.deriveFont(Font.BOLD, 22f) })
                add(JLabel("Turns this phone into an apps-only phone. The customer picks the allowed apps on the phone at the end."))
                add(JLabel(" "))
                add(details())
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
            minimumSize = Dimension(760, 640)
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

        /** The customer's details and the removal code they choose (needed for a new setup). */
        fun details(): JPanel = JPanel(GridBagLayout()).apply {
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder("Customer details")
            val rows = listOf(
                "Full name" to nameField,
                "Phone number" to phoneField,
                "Email" to emailField,
                "Removal code (customer picks it)" to codeField,
                "Removal code again" to code2Field,
            )
            rows.forEachIndexed { i, (label, field) ->
                add(JLabel(label), GridBagConstraints().apply { gridx = 0; gridy = i; anchor = GridBagConstraints.WEST; insets = Insets(3, 6, 3, 10) })
                add(field, GridBagConstraints().apply { gridx = 1; gridy = i; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(3, 0, 3, 6) })
            }
            add(JLabel("<html>Support can remove the filter only with this code. If it's forgotten, a reset link goes to the email above.</html>"),
                GridBagConstraints().apply { gridx = 0; gridy = rows.size; gridwidth = 2; anchor = GridBagConstraints.WEST; insets = Insets(6, 6, 3, 6) })
        }

        fun customer(): CustomerInfo? {
            val all = listOf(nameField.text, phoneField.text, emailField.text, String(codeField.password))
            if (all.all { it.isBlank() } && lastCheck?.alreadyInstalled == true) return null
            return CustomerInfo(nameField.text, phoneField.text, emailField.text, String(codeField.password))
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
            val customer = customer()
            val problems = customer?.problems(String(code2Field.password)) ?: emptyList()
            if (problems.isNotEmpty()) {
                JOptionPane.showMessageDialog(this, problems.joinToString("\n"), "Customer details", JOptionPane.WARNING_MESSAGE)
                return
            }
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
                val ok = engine().install(apk!!, customer)
                SwingUtilities.invokeLater {
                    status.foreground = if (ok) Color(0x1B5E20) else Color(0xB00020)
                    status.text = if (ok) "Done. Finish on the phone: pick the apps, then tap Lock."
                        else "Setup did not finish. See the messages below."
                    lastCheck = null
                    if (ok) { codeField.text = ""; code2Field.text = "" }
                    busy(false)
                    installBtn.isEnabled = false
                }
            }.start()
        }
    }
}
