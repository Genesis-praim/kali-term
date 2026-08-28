#!/data/data/com.genesis.kaliterm/files/usr/bin/sh
# bootstrap.sh - primer arranque: descarga, verifica y extrae el rootfs de Kali.
# Se ejecuta UNA sola vez. Idempotente: si ya está instalado, sale al instante.
set -eu

# --- Rutas (dir privado de la app) ---
FILES="${FILES:-/data/data/com.genesis.kaliterm/files}"
PREFIX="$FILES/usr"
ROOTFS="$FILES/kali-rootfs"
STAMP="$ROOTFS/.installed"
TARBALL="$FILES/rootfs.tar.xz"

# --- Origen del rootfs (Kali NetHunter minimal arm64, ~131 MB) ---
ROOTFS_URL="https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz"
# sha256 oficial (se rellena en build/CI desde el fichero .sha256sum del mirror)
ROOTFS_SHA256="${ROOTFS_SHA256:-}"

log() { printf '\033[1;32m[*]\033[0m %s\n' "$1"; }
err() { printf '\033[1;31m[!]\033[0m %s\n' "$1" >&2; }

# --- Ya instalado ---
if [ -f "$STAMP" ]; then
  log "Kali ya instalado. Arrancando..."
  exit 0
fi

log "Primer arranque: preparando Kali Linux (minimal)."
mkdir -p "$ROOTFS"

# --- Descarga con reintentos y reanudación ---
if [ ! -f "$TARBALL" ] || [ ! -s "$TARBALL" ]; then
  log "Descargando rootfs (~131 MB)..."
  # -C - reanuda si se cortó; --retry para redes móviles inestables
  if ! curl -L --fail --retry 5 --retry-delay 3 -C - -o "$TARBALL" "$ROOTFS_URL"; then
    err "Descarga fallida. Revisa tu conexión y reintenta."
    exit 1
  fi
fi

# --- Verificación de integridad (si hay hash) ---
if [ -n "$ROOTFS_SHA256" ]; then
  log "Verificando integridad (sha256)..."
  echo "$ROOTFS_SHA256  $TARBALL" | sha256sum -c - || {
    err "Checksum inválido: descarga corrupta. Borrando y abortando."
    rm -f "$TARBALL"
    exit 1
  }
fi

# --- Extracción ---
log "Extrayendo rootfs..."
# --delay-directory-restore evita errores de permisos con proot/tar en Android
if ! tar -xJf "$TARBALL" -C "$ROOTFS" --delay-directory-restore 2>/dev/null; then
  # Fallback sin -J por si xz va aparte
  xz -dc "$TARBALL" | tar -x -C "$ROOTFS" --delay-directory-restore
fi

# El tarball puede traer un subdirectorio (kali-arm64-*). Normaliza.
if [ ! -d "$ROOTFS/etc" ]; then
  inner=$(find "$ROOTFS" -maxdepth 1 -type d -name 'kali*' | head -n1)
  if [ -n "${inner:-}" ]; then
    mv "$inner"/* "$ROOTFS"/ 2>/dev/null || true
    rmdir "$inner" 2>/dev/null || true
  fi
fi

# --- Config DNS y hosts dentro del rootfs ---
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf"
printf '127.0.0.1 localhost\n' > "$ROOTFS/etc/hosts"

# --- Limpieza y sello de instalación ---
rm -f "$TARBALL"
touch "$STAMP"
log "Kali instalado correctamente."
