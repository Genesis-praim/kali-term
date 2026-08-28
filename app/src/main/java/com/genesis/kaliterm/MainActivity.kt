package com.genesis.kaliterm

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * Kali Terminal - terminal Kali puro sobre proot, sin root del dispositivo.
 * Sin Claude Code ni integraciones: solo Kali Linux funcional.
 */
class MainActivity : Activity() {

    private lateinit var terminal: TerminalView
    private var session: TerminalSession? = null

    // Modificadores sticky de la barra flotante (un solo disparo).
    private var stickyShift = false
    private var stickyAlt = false
    private lateinit var btnShift: Button
    private lateinit var btnAlt: Button

    private val filesDir get() = applicationContext.filesDir.absolutePath

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        terminal = findViewById(R.id.terminal)
        terminal.setTextSize(28)
        terminal.setTerminalViewClient(viewClient)
        terminal.keepScreenOn = true

        installAssets()
        wireExtraKeys()

        // Segundo plano: mantiene viva la sesión con la app minimizada.
        KaliService.start(this)

        startSession()
    }

    // --- Copia scripts + binario proot desde assets al dir privado y da permisos ---
    private fun installAssets() {
        val usrBin = File(filesDir, "usr/bin").apply { mkdirs() }
        File(filesDir, "home").mkdirs()
        File(filesDir, "tmp").mkdirs()

        // Scripts de bootstrap
        for (name in listOf("bootstrap.sh", "launch.sh", "install-tools.sh")) {
            val out = File(filesDir, "usr/bin/$name")
            assets.open("bootstrap/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out.setExecutable(true, false)
        }
        // El binario 'proot' y 'sh'/'curl'/'tar'/'xz' arm64 se empaquetan en
        // jniLibs y AndroidManifest.extractNativeLibs=true los deja en nativeLibraryDir.
        // Symlink a usr/bin para el shebang de los scripts.
        val nativeDir = applicationInfo.nativeLibraryDir
        for ((lib, bin) in mapOf(
            "libproot.so" to "proot",
            "libsh.so" to "sh",
            "libcurl.so" to "curl",
            "libtar.so" to "tar",
            "libxz.so" to "xz",
            "libsha256sum.so" to "sha256sum"
        )) {
            val src = File(nativeDir, lib)
            val dst = File(usrBin, bin)
            if (src.exists() && !dst.exists()) {
                try { android.system.Os.symlink(src.absolutePath, dst.absolutePath) }
                catch (e: Exception) { src.copyTo(dst, overwrite = true).setExecutable(true, false) }
            }
        }
    }

    private fun startSession() {
        val shell = File(filesDir, "usr/bin/sh").absolutePath
        // Primer arranque: bootstrap (descarga/extrae) -> auto-tools -> shell Kali.
        val entry = "sh usr/bin/bootstrap.sh && " +
            "usr/bin/launch.sh 'bash /root/host/usr/bin/install-tools.sh; exec bash --login'"
        val env = arrayOf(
            "FILES=$filesDir",
            "HOME=$filesDir/home",
            "PREFIX=$filesDir/usr",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=$filesDir/usr/bin:/system/bin"
        )
        session = TerminalSession(
            shell, filesDir, arrayOf("-c", entry), env, 2000, sessionClient
        )
        terminal.attachSession(session)
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

        // Shift / Alt: modificadores sticky (se aplican a la siguiente tecla).
        btnShift.setOnClickListener {
            stickyShift = !stickyShift
            it.isSelected = stickyShift
        }
        btnAlt.setOnClickListener {
            stickyAlt = !stickyAlt
            it.isSelected = stickyAlt
        }
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
    // NOTA: las firmas exactas deben alinearse con la versión de terminal-view
    // fijada en Gradle. readShiftKey/readAltKey conectan la barra flotante con
    // el teclado software para que Shift/Alt afecten a la siguiente pulsación.
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
        override fun onCodePoint(cp: Int, ctrl: Boolean, s: TerminalSession?): Boolean {
            clearStickies(); return false
        }
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
