#!/usr/bin/env bash
set -euo pipefail

SHARE_DIR="${SHARE_DIR:-/home/endri/firmware-inbox}"
SMB_USER="${SMB_USER:-firmware}"
SHARE_NAME="${SHARE_NAME:-FirmwareInbox}"

sudo apt update
sudo apt install -y samba
sudo mkdir -p "$SHARE_DIR"
sudo groupadd -f "$SMB_USER"
sudo useradd -M -s /usr/sbin/nologin -g "$SMB_USER" "$SMB_USER" 2>/dev/null || true
sudo chown -R "$SMB_USER:$SMB_USER" "$SHARE_DIR"
sudo chmod 2775 "$SHARE_DIR"
sudo smbpasswd -a "$SMB_USER"

if ! grep -q "^\[$SHARE_NAME\]" /etc/samba/smb.conf; then
  sudo tee -a /etc/samba/smb.conf >/dev/null <<EOF

[$SHARE_NAME]
path = $SHARE_DIR
browseable = yes
read only = no
valid users = $SMB_USER
create mask = 0664
directory mask = 0775
EOF
fi

sudo systemctl restart smbd
sudo ufw allow samba || true
echo "Ready: //$HOSTNAME/$SHARE_NAME -> $SHARE_DIR"
