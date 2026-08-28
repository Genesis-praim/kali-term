package com.genesis.kaliterm

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import kotlin.concurrent.thread

/**
 * Kali Terminal - terminal Kali puro sobre proot, sin root del dispositivo.
 * Único binario nativo: proot (libproot.so). El resto (descarga/extracción) va
 * en Kotlin (BootstrapManager). Sin Claude Code: Kali Linux y nada más.
 */
class MainActivity : Activity() {

    private lateinit var terminal: TerminalView
    private var session: TerminalSession? = null
    private var overlay: TextView? = null

    // Modificadores sticky de la barra flotante (un solo disparo).
    private var stickyShift = false
    private var stickyAlt = false
    private lateinit var btnShift: Button
    private lateinit var btnAlt: Button

    private val files get() = applicationContext.filesDir.absolutePath
    private val prootPath get() = File(applicationInfo.nativeLibraryDir, "libproot.so").absolutePath

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminal = findViewById(R.id.terminal)
        terminal.setTextSize(28)
        terminal.setTerminalViewClient(viewClient)
        terminal.keepScreenOn = true

        wireExtraKeys()
        KaliService.start(this) // segundo plano

        if (BootstrapManager.isInstalled(this)) {
            startSession()
        } else {
            runFirstInstall()
        }
    }

    // --- Primer arranque: descarga + extrae (Kotlin), luego entra a Kali ---
    private fun runFirstInstall() {
        overlay = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#F00A0E0A"))
            setTextColor(Color.parseColor("#FF00FF41"))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 15f
            gravity = Gravity.CENTER
            text = "Preparando Kali Linux…"
        }
        (findViewById<FrameLayout>(android.R.id.content) ?: (terminal.parent as FrameLayout))
            .let { (terminal.parent as FrameLayout).addView(overlay) }

        thread(name = "kali-bootstrap") {
            runCatching {
                BootstrapManager.install(this) { msg, pct ->
                    runOnUiThread { overlay?.text = "[*] $msg\n\n$pct%" }
                }
            }.onSuccess {
                runOnUiThread {
                    (terminal.parent as FrameLayout).removeView(overlay); overlay = null
                    startSession()
                }
            }.onFailure { e ->
                runOnUiThread { overlay?.text = "[!] Error:\n${e.message}\n\nReabre la app para reintentar." }
            }
        }
    }

    private fun startSession() {
        val rootfs = BootstrapManager.rootfsDir(this).absolutePath
        val tmp = File(files, "tmp").apply { mkdirs() }.absolutePath
        // Instala herramientas la 1ª vez y abre bash login.
        val entry = "test -f /root/.tools-installed || bash /root/install-tools.sh; exec bash --login"
        val args = arrayOf(
            "--link2symlink", "-0", "-r", rootfs,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/storage",
            "-b", "$files:/root/host",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "-c", entry
        )
        val env = arrayOf("PROOT_TMP_DIR=$tmp", "HOME=$files/home", "TERM=xterm-256color")
        session = TerminalSession(prootPath, files, args, env, 2000, sessionClient)
        terminal.attachSession(session)
        terminal.requestFocus()
    }

    // ---------- Barra flotante de teclas ----------
    private fun wireExtraKeys() {
        btnShift = findViewById(R.id.key_shift)
        btnAlt = findViewById(R.id.key_alt)

        findViewById<Button>(R.id.key_esc).setOnClickListener { sendKey(KeyEvent.KEYCODE_ESCAPE) }
        findViewById<Button>(R.id.key_tab).setOnClickListener { sendKey(KeyEvent.KEYCODE_TAB) }
        findViewById<Button>(R.id.key_end).setOnClickListener { sendKey(KeyEvent.KEYCODE_MOVE_END) }
        findViewById<Button>(R.id.key_left).setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        findViewById<Button>(R.id.key_up).setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        findViewById<Button>(R.id.key_down).setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        findViewById<Button>(R.id.key_right).setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }

        btnShift.setOnClickListener { stickyShift = !stickyShift; it.isSelected = stickyShift }
        btnAlt.setOnClickListener { stickyAlt = !stickyAlt; it.isSelected = stickyAlt }
    }

    /** Envía una tecla al terminal respetando modificadores sticky, y los limpia. */
    private fun sendKey(keyCode: Int) {
        var meta = 0
        if (stickyShift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (stickyAlt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        val now = System.currentTimeMillis()
        terminal.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        terminal.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        clearStickies()
    }

    private fun clearStickies() {
        if (stickyShift) { stickyShift = false; btnShift.isSelected = false }
        if (stickyAlt) { stickyAlt = false; btnAlt.isSelected = false }
    }

    // ---------- Clients de terminal-view ----------
    // NOTA: alinear firmas a la versión de terminal-view fijada en Gradle.
    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float) = scale
        override fun onSingleTapUp(e: android.view.MotionEvent) { terminal.requestFocus() }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, s: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(e: android.view.MotionEvent?) = false
        override fun readControlKey() = false
        override fun readAltKey() = stickyAlt
        override fun readShiftKey() = stickyShift
        override fun readFnKey() = false
        override fun onCodePoint(cp: Int, ctrl: Boolean, s: TerminalSession?): Boolean { clearStickies(); return false }
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, msg: String?) {}
        override fun logWarn(tag: String?, msg: String?) {}
        override fun logInfo(tag: String?, msg: String?) {}
        override fun logDebug(tag: String?, msg: String?) {}
        override fun logVerbose(tag: String?, msg: String?) {}
        override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) { terminal.onScreenUpdated() }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle() = null
        override fun logError(tag: String?, msg: String?) {}
        override fun logWarn(tag: String?, msg: String?) {}
        override fun logInfo(tag: String?, msg: String?) {}
        override fun logDebug(tag: String?, msg: String?) {}
        override fun logVerbose(tag: String?, msg: String?) {}
        override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        super.onDestroy()
    }
}
