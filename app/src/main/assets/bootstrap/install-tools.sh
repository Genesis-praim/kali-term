#!/bin/bash
# install-tools.sh - se ejecuta DENTRO de Kali, una sola vez tras instalar el rootfs.
# Instala un set de pentesting sobre la base minimal. Persiste en el rootfs de la app.
set -e

STAMP="/root/.tools-installed"
if [ -f "$STAMP" ]; then
  echo "[*] Herramientas ya instaladas."
  exit 0
fi

echo "[*] Actualizando repos de Kali..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y

echo "[*] Instalando set de pentesting (esto tarda la primera vez)..."
# Set base sensato para pruebas reales de red/web. Ajusta a gusto.
apt-get install -y --no-install-recommends \
  nmap \
  metasploit-framework \
  sqlmap \
  hydra \
  nikto \
  gobuster \
  ffuf \
  netcat-traditional \
  dnsutils \
  whois \
  curl wget git \
  python3 python3-pip \
  iproute2 iputils-ping \
  openssh-client \
  tmux nano vim

echo "[*] Limpieza de cache apt..."
apt-get clean
rm -rf /var/lib/apt/lists/*

touch "$STAMP"
echo "[*] Herramientas instaladas. Todo queda guardado permanentemente en la app."
