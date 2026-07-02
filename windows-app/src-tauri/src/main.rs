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
    if let Ok(dir) = app.path().resource_dir() {
        let bundled = dir.join("firefiles-worker.exe");
        if bundled.exists() {
            return bundled;
        }
    }
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|dir| dir.join("firefiles-worker.exe")))
        .filter(|p| p.exists())
        .unwrap_or_else(|| PathBuf::from("firefiles-worker.exe"))
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
        .invoke_handler(tauri::generate_handler![start_worker, stop_worker, is_running])
        .run(tauri::generate_context!())
        .expect("error while running FireFiles");
}
