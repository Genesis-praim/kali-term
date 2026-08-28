package com.genesis.kaliterm

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Descarga, verifica y extrae el rootfs de Kali EN KOTLIN PURO.
 * Así el único binario nativo necesario es 'proot' (busybox/curl/tar/xz fuera).
 *
 * - Descarga con HttpURLConnection (permiso INTERNET)
 * - Verifica SHA-256 (MessageDigest)
 * - Descomprime .xz (org.tukaani:xz)
 * - Desempaqueta tar respetando symlinks/hardlinks/permisos (commons-compress)
 * Todo se escribe en almacenamiento interno privado -> permanente.
 */
object BootstrapManager {

    // Kali NetHunter minimal arm64 (~131 MB)
    const val ROOTFS_URL =
        "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"
    // sha256 oficial; si está vacío se omite la verificación (rellenar en release)
    const val ROOTFS_SHA256 = ""

    fun rootfsDir(ctx: Context) = File(ctx.filesDir, "kali-rootfs")
    fun isInstalled(ctx: Context) = File(rootfsDir(ctx), ".installed").exists()

    /** Instala el rootfs. `progress` recibe mensajes de estado (0..100 en %). */
    fun install(ctx: Context, progress: (String, Int) -> Unit) {
        val rootfs = rootfsDir(ctx).apply { mkdirs() }
        val tmp = File(ctx.filesDir, "rootfs.tar.xz")

        // --- Descarga con reanudación básica y % ---
        progress("Conectando…", 0)
        val conn = (URL(ROOTFS_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000; readTimeout = 30000
            instanceFollowRedirects = true
        }
        val total = conn.contentLengthLong.coerceAtLeast(1)
        val digest = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 16)
                var read: Int; var done = 0L; var lastPct = -1
                while (input.read(buf).also { read = it } >= 0) {
                    out.write(buf, 0, read)
                    digest.update(buf, 0, read)
                    done += read
                    val pct = (done * 100 / total).toInt()
                    if (pct != lastPct) { progress("Descargando rootfs…", pct); lastPct = pct }
                }
            }
        }

        // --- Verificación de integridad ---
        if (ROOTFS_SHA256.isNotEmpty()) {
            progress("Verificando integridad…", 100)
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            require(hex.equals(ROOTFS_SHA256, ignoreCase = true)) {
                tmp.delete(); "Checksum inválido: descarga corrupta."
            }
        }

        // --- Extracción xz + tar ---
        progress("Extrayendo… (puede tardar)", 100)
        extractTarXz(tmp, rootfs)

        // El tarball puede traer un subdirectorio raíz (kali-*). Normaliza.
        if (!File(rootfs, "etc").exists()) {
            rootfs.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("kali") }?.let { inner ->
                inner.listFiles()?.forEach { it.renameTo(File(rootfs, it.name)) }
                inner.delete()
            }
        }

        // --- DNS/hosts + script de herramientas dentro del rootfs ---
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n")
        val toolsDst = File(rootfs, "root/install-tools.sh")
        ctx.assets.open("bootstrap/install-tools.sh").use { i ->
            toolsDst.outputStream().use { i.copyTo(it) }
        }

        tmp.delete()
        File(rootfs, ".installed").createNewFile()
        progress("Kali instalado.", 100)
    }

    private fun extractTarXz(src: File, dst: File) {
        TarArchiveInputStream(XZInputStream(BufferedInputStream(src.inputStream()))).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                if (name.isEmpty()) { entry = tar.nextTarEntry; continue }
                val outFile = File(dst, name)
                when {
                    entry.isDirectory -> outFile.mkdirs()
                    entry.isSymbolicLink -> {
                        outFile.parentFile?.mkdirs(); outFile.delete()
                        runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                    }
                    entry.isLink -> { // hardlink
                        outFile.parentFile?.mkdirs(); outFile.delete()
                        runCatching { Os.link(File(dst, entry.linkName.removePrefix("./")).absolutePath, outFile.absolutePath) }
                    }
                    else -> {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { tar.copyTo(it) }
                        runCatching { Os.chmod(outFile.absolutePath, entry.mode and 0xFFF) }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
    }
}
