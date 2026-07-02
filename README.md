# FireFiles

FireFiles memindahkan firmware `.tar.md5` dari Windows ke Ubuntu lewat Android:

```text
Windows download folder -> ADB push -> Android /sdcard/FirmwareBridge -> SMB -> Ubuntu Samba
```

File asli di Windows tidak dihapus. File baru dipindah ke folder backup hanya setelah upload Android ke Ubuntu sukses dan ukuran file cocok.

## 1. Siapkan Ubuntu

Install Samba dan buat share:

```bash
cd ubuntu
chmod +x setup-samba.sh
./setup-samba.sh
```

Default:

```text
Share: //ubuntu-ip/FirmwareInbox
Folder: /home/endri/firmware-inbox
User: firmware
Port: 445
```

Cek file masuk:

```bash
./ubuntu/verify-inbox.sh
```

## 2. Jalankan monitor dashboard

Di Ubuntu/server Docker:

```bash
MONITOR_TOKEN='ganti-token-ini' docker compose up -d --build monitor
```

Dashboard lokal:

```text
http://localhost:8080
```

Jika pakai Cloudflare Tunnel, arahkan tunnel ke:

```text
http://localhost:8080
```

URL publik yang dipakai agent:

```text
https://files.endrisusanto.my.id/
https://files.endrisusanto.my.id/api/heartbeat
```

## 3. Kirim heartbeat Ubuntu

Jalankan manual:

```bash
MONITOR_TOKEN='ganti-token-ini' ./monitor-agents/ubuntu-heartbeat.sh
```

Cron tiap menit:

```bash
crontab -e
```

Tambahkan:

```cron
* * * * * cd /path/to/FireFiles && MONITOR_TOKEN='ganti-token-ini' ./monitor-agents/ubuntu-heartbeat.sh
```

## 4. Install dan konfigurasi Android APK

Download APK dari GitHub Release. Pakai file `app-debug.apk`; APK ini debug-signed supaya bisa langsung diinstall untuk sideload test.

Jika Android menolak update karena signature beda, uninstall APK lama dulu lalu install `app-debug.apk`.

Isi SMB:

```text
Ubuntu host/IP: ubuntu-ip
Share: FirmwareInbox
Username: firmware
Password: password samba
Remote folder: kosongkan untuk root share
Monitor URL: https://files.endrisusanto.my.id/api/heartbeat
Monitor token: ganti-token-ini
```

Tap:

```text
Grant Files Access
Save + Start
```

Android akan memantau:

```text
/sdcard/FirmwareBridge
```

Upload memakai nama sementara:

```text
filename.tar.md5.uploading
```

Setelah upload selesai dan ukuran cocok, Android rename ke:

```text
filename.tar.md5
```

Status untuk Windows ditulis ke:

```text
/sdcard/FirmwareBridge/status/filename.tar.md5.json
```

## 5. Jalankan Windows worker

Install Android Platform Tools, aktifkan USB Debugging, lalu cek device:

```powershell
adb devices
```

Kirim heartbeat Windows:

```powershell
$env:MONITOR_TOKEN='ganti-token-ini'
powershell -ExecutionPolicy Bypass -File .\monitor-agents\windows-heartbeat.ps1
```

Jalankan worker:

```powershell
.\firefiles-worker.exe --source D:\Downloads --backup D:\FirmwareBackup --adb C:\Android\platform-tools\adb.exe --serial DEVICE_SERIAL
```

Worker akan:

- ignore `.tar.md5.part`
- tunggu `.tar.md5` stabil
- push ke Android sebagai `.pushing`
- rename ke `.tar.md5`
- cek ukuran file Android
- tunggu status Android `uploaded`
- pindahkan source Windows ke backup

## 6. Test end-to-end

1. Pastikan Android terhubung USB dan `adb devices` muncul.
2. Pastikan Android dan Ubuntu satu jaringan.
3. Pastikan dashboard terbuka.
4. Letakkan file kecil bernama contoh:

```text
TEST.tar.md5
```

di folder source Windows.

5. Tunggu file muncul di:

```text
/home/endri/firmware-inbox
```

6. Pastikan file Windows berpindah ke backup.
7. Pastikan dashboard menampilkan card Windows, Android, dan Ubuntu dengan badge `Healthy`.

## 7. Release baru

Auto bump patch, commit, tag, push, dan trigger GitHub Actions:

```bash
./script.sh
```

Pakai versi manual:

```bash
./script.sh 0.1.4
```

Release menghasilkan:

- APK installable (`app-debug.apk`)
- MSI
- DEB
- RPM

## Troubleshooting

- `adb devices` kosong: unlock Android, accept USB debugging, ganti kabel USB data.
- APK tidak bisa install: pakai `app-debug.apk`, bukan release unsigned; uninstall versi lama jika muncul signature mismatch.
- Android tidak bisa akses `/sdcard/FirmwareBridge`: tap `Grant Files Access`.
- Upload SMB gagal: cek IP Ubuntu, user `firmware`, password Samba, dan port TCP 445.
- Windows tidak selesai: cek file status di `/sdcard/FirmwareBridge/status/`.
- Dashboard kosong: cek `MONITOR_TOKEN` sama di server dan agent.
- Badge `Warning`: CPU/RAM/storage tinggi atau status berisi `missing`, `failed`, atau `error`.
