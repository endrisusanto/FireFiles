# FireFiles firmware bridge

Minimal v1 pipeline:

```text
Windows folder -> adb push -> Android /sdcard/FirmwareBridge -> SMB -> Ubuntu Samba
```

Windows never deletes the original `.tar.md5`; it moves it to backup only after Android reports the Ubuntu upload succeeded.

## Ubuntu

```bash
cd ubuntu
chmod +x setup-samba.sh
./setup-samba.sh
```

Default share:

```text
//ubuntu-host/FirmwareInbox
/home/endri/firmware-inbox
user: firmware
```

## Windows worker

Build:

```bash
cd windows-worker
cargo build --release
```

Run:

```bash
firefiles-worker.exe --source D:\Downloads --backup D:\FirmwareBackup --adb C:\Android\platform-tools\adb.exe --serial DEVICE_SERIAL
```

Behavior:

- ignores `.tar.md5.part`
- waits until `.tar.md5` size is stable
- pushes to Android as `filename.tar.md5.pushing`
- renames to `filename.tar.md5` on Android after push
- verifies Android file size
- polls `/sdcard/FirmwareBridge/status/<filename>.json`
- moves the Windows source file to backup only after status is `uploaded`

Expected Android status file:

```json
{"file":"AP.tar.md5","state":"uploaded","size":123456}
```

## Android

Open `android-app` in Android Studio, configure SMB host/share/user/password in the app, then start the foreground uploader.

The Android app watches:

```text
/sdcard/FirmwareBridge
```

It uploads each `.tar.md5` as:

```text
filename.tar.md5.uploading
```

Then verifies remote size and renames to:

```text
filename.tar.md5
```

Status is written to:

```text
/sdcard/FirmwareBridge/status/filename.tar.md5.json
```

## Troubleshooting

- `adb devices` must show exactly the phone serial used by the worker.
- Unlock the phone once after USB connect and allow USB debugging.
- Samba port is TCP 445.
- Android and Ubuntu must be on the same network for SMB.
- If Android 11+ blocks `/sdcard/FirmwareBridge`, grant all-files access to the sideloaded app or switch the worker destination to the app external files directory.

## Monitor dashboard

Run the dashboard on the Ubuntu box:

```bash
MONITOR_TOKEN='change-this' docker compose up -d monitor
```

Open:

```text
https://files.endrisusanto.my.id/
```

Cloudflare Tunnel can point to:

```text
http://localhost:8080
```

Ubuntu heartbeat:

```bash
MONITOR_TOKEN='change-this' ./monitor-agents/ubuntu-heartbeat.sh
```

Windows heartbeat:

```powershell
$env:MONITOR_TOKEN='change-this'
powershell -ExecutionPolicy Bypass -File .\monitor-agents\windows-heartbeat.ps1
```

Run those from Task Scheduler or cron every minute.

Android heartbeat:

- Open the APK.
- Set `Monitor URL` to `https://files.endrisusanto.my.id/api/heartbeat`.
- Set the same token.
- Tap `Save + Start`.
