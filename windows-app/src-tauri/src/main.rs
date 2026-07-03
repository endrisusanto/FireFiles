#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Deserialize;
use std::{
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::Mutex,
};
use tauri::{
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    AppHandle, Manager, State, WindowEvent,
};

struct Worker(Mutex<Option<Child>>);

#[derive(Deserialize)]
struct WorkerConfig {
    source: String,
    backup: String,
    adb: String,
    serial: Option<String>,
    #[serde(rename = "androidDir")]
    android_dir: String,
    #[serde(rename = "stableSecs")]
    stable_secs: u64,
}

#[tauri::command]
fn start_worker(app: AppHandle, cfg: WorkerConfig, state: State<Worker>) -> Result<String, String> {
    let mut worker = state.0.lock().map_err(|_| "worker lock failed")?;
    if worker.as_mut().is_some_and(|child| child.try_wait().ok().flatten().is_none()) {
        return Ok("worker already running".into());
    }

    if cfg.source.trim().is_empty() || cfg.backup.trim().is_empty() {
        return Err("source and backup are required".into());
    }

    let mut cmd = Command::new(worker_exe(&app));
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
    }
    cmd.args([
        "--source",
        &cfg.source,
        "--backup",
        &cfg.backup,
        "--adb",
        &cfg.adb,
        "--android-dir",
        &cfg.android_dir,
        "--stable-secs",
        &cfg.stable_secs.to_string(),
    ]);
    if let Some(serial) = cfg.serial.filter(|s| !s.trim().is_empty()) {
        cmd.args(["--serial", &serial]);
    }

    *worker = Some(
        cmd.stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| format!("failed to start firefiles-worker: {e}"))?,
    );
    Ok("worker started".into())
}

#[tauri::command]
fn stop_worker(state: State<Worker>) -> Result<String, String> {
    let mut worker = state.0.lock().map_err(|_| "worker lock failed")?;
    if let Some(mut child) = worker.take() {
        let _ = child.kill();
        let _ = child.wait();
        return Ok("worker stopped".into());
    }
    Ok("worker already stopped".into())
}

#[tauri::command]
fn is_running(state: State<Worker>) -> Result<bool, String> {
    let mut worker = state.0.lock().map_err(|_| "worker lock failed")?;
    if let Some(child) = worker.as_mut() {
        if child.try_wait().map_err(|e| e.to_string())?.is_none() {
            return Ok(true);
        }
        *worker = None;
    }
    Ok(false)
}

fn worker_exe(app: &AppHandle) -> PathBuf {
    #[cfg(target_os = "windows")]
    let name = "firefiles-worker-x86_64-pc-windows-msvc.exe";
    #[cfg(target_os = "linux")]
    let name = "firefiles-worker-x86_64-unknown-linux-gnu";
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    let name = "firefiles-worker";

    if let Ok(dir) = app.path().resource_dir() {
        let bundled = dir.join("binaries").join(name);
        if bundled.exists() {
            return bundled;
        }
        let bundled_root = dir.join(name);
        if bundled_root.exists() {
            return bundled_root;
        }
    }
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|dir| dir.join(name)))
        .filter(|p| p.exists())
        .unwrap_or_else(|| PathBuf::from(name))
}

use std::{fs, io::Read, net::UdpSocket};

#[tauri::command]
fn detect_ip() -> String {
    UdpSocket::bind("0.0.0.0:0")
        .ok()
        .and_then(|s| {
            s.connect("8.8.8.8:80").ok()?;
            s.local_addr().ok()
        })
        .map(|a| a.ip().to_string())
        .unwrap_or_else(|| "127.0.0.1".to_string())
}

#[tauri::command]
fn generate_password() -> String {
    #[cfg(target_os = "linux")]
    {
        let mut buf = [0u8; 12];
        if let Ok(mut f) = fs::File::open("/dev/urandom") {
            let _ = f.read_exact(&mut buf);
        }
        buf.iter().map(|b| format!("{b:02x}")).collect()
    }
    #[cfg(not(target_os = "linux"))]
    {
        use std::time::SystemTime;
        let nanos = SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(123456789);
        format!("{:x}", nanos)
    }
}

#[tauri::command]
fn setup_samba(
    share_dir: String,
    share_name: String,
    user: String,
    pass: String,
) -> Result<String, String> {
    // Di Linux, jalankan script setup samba via pkexec dengan mengekstrak script dari binary
    #[cfg(target_os = "linux")]
    {
        let temp_script = std::env::temp_dir().join("firefiles-setup-samba.sh");
        fs::write(&temp_script, include_str!("../../../ubuntu/setup-samba.sh"))
            .map_err(|e| format!("failed to write temp script: {e}"))?;

        let status = Command::new("pkexec")
            .args([
                "bash",
                temp_script.to_str().unwrap(),
                &share_dir,
                &user,
                &share_name,
                &pass,
            ])
            .status()
            .map_err(|e| format!("pkexec failed: {e}"))?;

        let _ = fs::remove_file(temp_script);

        if status.success() {
            Ok(format!("Samba share '{share_name}' siap di {share_dir}"))
        } else {
            Err(format!("Setup gagal (exit {})", status.code().unwrap_or(-1)))
        }
    }
    #[cfg(not(target_os = "linux"))]
    {
        Err("Setup Samba hanya disupport di Linux".into())
    }
}

#[tauri::command]
fn register_config(
    url: String,
    token: String,
    host: String,
    share: String,
    user: String,
    pass: String,
    folder: String,
) -> Result<(), String> {
    let body = format!(
        r#"{{"host":"{host}","share":"{share}","user":"{user}","pass":"{pass}","folder":"{folder}"}}"#
    );
    let endpoint = format!("{}/api/smb-config", url.trim_end_matches('/'));
    ureq::post(&endpoint)
        .set("content-type", "application/json")
        .set("x-monitor-token", &token)
        .send_string(&body)
        .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn fetch_smb_config(url: String, token: String) -> Result<String, String> {
    let endpoint = format!("{}/api/smb-config", url.trim_end_matches('/'));
    let resp = ureq::get(&endpoint)
        .set("x-monitor-token", &token)
        .call()
        .map_err(|e| e.to_string())?
        .into_string()
        .map_err(|e| e.to_string())?;
    Ok(resp)
}

#[tauri::command]
fn pick_folder() -> Option<String> {
    rfd::FileDialog::new()
        .pick_folder()
        .map(|p| p.to_string_lossy().into_owned())
}

fn main() {
    tauri::Builder::default()
        .manage(Worker(Mutex::new(None)))
        .setup(|app| {
            let show = MenuItem::with_id(app, "show", "Show", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &quit])?;
            TrayIconBuilder::new()
                .menu(&menu)
                .show_menu_on_left_click(true)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "show" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .build(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            start_worker,
            stop_worker,
            is_running,
            detect_ip,
            generate_password,
            setup_samba,
            register_config,
            fetch_smb_config,
            pick_folder
        ])
        .run(tauri::generate_context!())
        .expect("error while running FireFiles");
}

