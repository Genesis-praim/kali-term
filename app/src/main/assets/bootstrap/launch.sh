#!/data/data/com.genesis.kaliterm/files/usr/bin/sh
# launch.sh - entra al entorno Kali vía proot (SIN root del dispositivo).
set -eu

FILES="${FILES:-/data/data/com.genesis.kaliterm/files}"
PREFIX="$FILES/usr"
ROOTFS="$FILES/kali-rootfs"

export PROOT_TMP_DIR="$FILES/tmp"
export PROOT_LOADER="$PREFIX/libexec/proot/loader"
mkdir -p "$PROOT_TMP_DIR"

# Comando a ejecutar dentro de Kali (por defecto login shell)
CMD="${1:-/bin/bash --login}"

exec "$PREFIX/bin/proot" \
  --link2symlink \
  --kill-on-exit \
  -0 \
  -r "$ROOTFS" \
  -b /dev \
  -b /proc \
  -b /sys \
  -b "$FILES:/root/host" \
  -b /storage \
  -b /data/data/com.genesis.kaliterm/files/home:/root \
  -w /root \
  /usr/bin/env -i \
    HOME=/root \
    USER=root \
    TERM="${TERM:-xterm-256color}" \
    LANG=C.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    $CMD
