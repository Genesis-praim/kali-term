# Componentes de terceros

| Componente | Uso | Licencia |
|---|---|---|
| **proot** (libproot.so, arm64) | Ejecutar el rootfs Kali sin root del dispositivo | **GPL-2.0** (build portable basado en el proot de Termux) |
| **Termux terminal-view / terminal-emulator / termux-shared** | Render del terminal | Apache-2.0 |
| **org.tukaani:xz** | Descompresión .xz del rootfs | Dominio público |
| **Apache Commons Compress** | Desempaquetado tar del rootfs | Apache-2.0 |
| **Kali NetHunter rootfs** (descargado en runtime) | Sistema Kali Linux | GPL y otras (paquetes Debian/Kali) |

proot binario portable: https://github.com/skirsten/proot-portable-android-binaries
Al distribuir el APK con proot (GPL-2.0), publica también el código fuente correspondiente.
