#!/bin/sh
set -eu

: "${FTP_USER:?FTP_USER is required}"
: "${FTP_PASSWORD:?FTP_PASSWORD is required}"

HOME_DIR=/srv/ftp/home
FILES_DIR="$HOME_DIR/files"
RUNTIME_CONF=/run/vsftpd/vsftpd.conf

if ! id "$FTP_USER" >/dev/null 2>&1; then
  adduser -D -H -h "$HOME_DIR" -s /sbin/nologin "$FTP_USER"
fi
printf '%s:%s\n' "$FTP_USER" "$FTP_PASSWORD" | chpasswd >/dev/null 2>&1
printf '%s\n' "$FTP_USER" > /etc/vsftpd/user_list

# vsftpd refuses a writable chroot root, so the home is root-owned and
# read-only and the account writes under files/<category>/.
mkdir -p "$FILES_DIR/fault-records" "$FILES_DIR/connection-documents" "$FILES_DIR/drone-videos"
chown root:root "$HOME_DIR"
chmod 0555 "$HOME_DIR"
chown "$FTP_USER:$FTP_USER" "$FILES_DIR" "$FILES_DIR"/*
chmod 0750 "$FILES_DIR" "$FILES_DIR"/*

cp /etc/vsftpd/vsftpd.conf "$RUNTIME_CONF"
if [ -n "${FTP_PASV_ADDRESS:-}" ]; then
  printf 'pasv_address=%s\n' "$FTP_PASV_ADDRESS" >> "$RUNTIME_CONF"
fi

echo "vsftpd: user=$FTP_USER pasv_address=${FTP_PASV_ADDRESS:-<container ip>}"
exec vsftpd "$RUNTIME_CONF"
