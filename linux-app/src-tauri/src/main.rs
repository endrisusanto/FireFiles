use std::{fs, io::Read, net::UdpSocket, process::Command};

// ponytail: embed script at compile time — no bundling needed
const SETUP_SCRIPT: &str = include_str!("../../../ubuntu/setup-samba.sh");

/// Detect LAN IP by connecting a UDP socket (no packet sent).
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

/// Read 12 bytes from /dev/urandom → 24-char hex password.
#[tauri::command]
fn generate_password() -> String {
    let mut buf = [0u8; 12];
    fs::File::open("/dev/urandom")
        .ok()
        .and_then(|mut f| f.read_exact(&mut buf).ok());
    buf.iter().map(|b| format!("{b:02x}")).collect()
}

/// Write setup script to /tmp, run via pkexec (shows GUI password dialog).
#[tauri::command]
fn setup_samba(
    share_dir: String,
    share_name: String,
    user: String,
    pass: String,
) -> Result<String, String> {
    let tmp = std::env::temp_dir().join("firefiles-samba-setup.sh");
    fs::write(&tmp, SETUP_SCRIPT).map_err(|e| e.to_string())?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&tmp, fs::Permissions::from_mode(0o700))
            .map_err(|e| e.to_string())?;
    }

    let status = Command::new("pkexec")
        .args([
            "bash",
            tmp.to_str().unwrap(),
            &share_dir,
            &user,
            &share_name,
            &pass,
        ])
        .status()
        .map_err(|e| format!("pkexec not found: {e}. Install policykit-1."))?;

    let _ = fs::remove_file(&tmp);

    if status.success() {
        Ok(format!("Samba share '{share_name}' siap di {share_dir}"))
    } else {
        Err(format!(
            "Setup gagal (exit {})",
            status.code().unwrap_or(-1)
        ))
    }
}

/// POST SMB config ke monitor server agar Android/Windows bisa auto-fetch.
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
    // ponytail: manual JSON string — avoid serde_json dep
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

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            detect_ip,
            generate_password,
            setup_samba,
            register_config,
        ])
        .run(tauri::generate_context!())
        .expect("error running FireFiles Setup");
}
