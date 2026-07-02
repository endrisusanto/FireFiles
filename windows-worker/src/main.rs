use std::{
    env,
    ffi::OsStr,
    fs,
    io,
    path::{Path, PathBuf},
    process::Command,
    thread,
    time::{Duration, Instant},
};

#[derive(Debug)]
struct Config {
    source: PathBuf,
    backup: PathBuf,
    adb: PathBuf,
    serial: Option<String>,
    android_dir: String,
    stable_secs: u64,
    poll_secs: u64,
}

fn main() {
    let cfg = match Config::from_args() {
        Ok(cfg) => cfg,
        Err(msg) => {
            eprintln!("{msg}");
            eprintln!("usage: firefiles-worker --source DIR --backup DIR [--adb adb] [--serial SERIAL] [--android-dir /sdcard/FirmwareBridge] [--stable-secs 20]");
            std::process::exit(2);
        }
    };

    fs::create_dir_all(&cfg.backup).expect("create backup dir");
    adb(&cfg, &["shell", "mkdir", "-p", &cfg.android_dir, &format!("{}/status", cfg.android_dir)])
        .expect("create android bridge dirs");

    loop {
        match next_firmware(&cfg.source) {
            Ok(Some(path)) => {
                if let Err(err) = process_file(&cfg, &path) {
                    eprintln!("failed {}: {err}", path.display());
                }
            }
            Ok(None) => thread::sleep(Duration::from_secs(cfg.poll_secs)),
            Err(err) => {
                eprintln!("scan failed: {err}");
                thread::sleep(Duration::from_secs(cfg.poll_secs));
            }
        }
    }
}

impl Config {
    fn from_args() -> Result<Self, String> {
        let mut args = env::args().skip(1);
        let mut cfg = Config {
            source: PathBuf::new(),
            backup: PathBuf::new(),
            adb: PathBuf::from("adb"),
            serial: None,
            android_dir: "/sdcard/FirmwareBridge".to_string(),
            stable_secs: 20,
            poll_secs: 5,
        };

        while let Some(arg) = args.next() {
            let mut value = || args.next().ok_or_else(|| format!("missing value for {arg}"));
            match arg.as_str() {
                "--source" => cfg.source = PathBuf::from(value()?),
                "--backup" => cfg.backup = PathBuf::from(value()?),
                "--adb" => cfg.adb = PathBuf::from(value()?),
                "--serial" => cfg.serial = Some(value()?),
                "--android-dir" => cfg.android_dir = value()?,
                "--stable-secs" => cfg.stable_secs = value()?.parse().map_err(|_| "bad --stable-secs".to_string())?,
                "--poll-secs" => cfg.poll_secs = value()?.parse().map_err(|_| "bad --poll-secs".to_string())?,
                _ => return Err(format!("unknown arg {arg}")),
            }
        }

        if cfg.source.as_os_str().is_empty() || cfg.backup.as_os_str().is_empty() {
            return Err("--source and --backup are required".to_string());
        }
        Ok(cfg)
    }
}

fn next_firmware(dir: &Path) -> io::Result<Option<PathBuf>> {
    let mut files = Vec::new();
    for entry in fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_file() && is_tar_md5(&path) {
            files.push(path);
        }
    }
    files.sort();
    Ok(files.into_iter().next())
}

fn is_tar_md5(path: &Path) -> bool {
    let name = path.file_name().and_then(OsStr::to_str).unwrap_or("");
    name.ends_with(".tar.md5") && !name.ends_with(".tar.md5.part")
}

fn process_file(cfg: &Config, path: &Path) -> io::Result<()> {
    println!("waiting stable: {}", path.display());
    let size = wait_stable(path, Duration::from_secs(cfg.stable_secs))?;
    let name = path.file_name().and_then(OsStr::to_str).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "bad filename"))?;
    if name.contains(char::is_whitespace) || name.contains('\'') || name.contains('"') {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "filename contains shell-hostile characters"));
    }
    let remote_tmp = format!("{}/{name}.pushing", cfg.android_dir);
    let remote_final = format!("{}/{}", cfg.android_dir, name);

    println!("pushing: {name}");
    adb(cfg, &["push", path.to_str().unwrap(), &remote_tmp])?;
    adb(cfg, &["shell", "mv", &remote_tmp, &remote_final])?;

    let android_size = adb_text(cfg, &["shell", "stat", "-c%s", &remote_final])?
        .trim()
        .parse::<u64>()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad android size"))?;
    if android_size != size {
        return Err(io::Error::new(io::ErrorKind::Other, format!("android size mismatch: {android_size} != {size}")));
    }

    println!("waiting ubuntu upload: {name}");
    wait_android_uploaded(cfg, name, size, Duration::from_secs(60 * 60 * 12))?;
    fs::rename(path, cfg.backup.join(name))?;
    println!("completed: {name}");
    Ok(())
}

fn wait_stable(path: &Path, stable_for: Duration) -> io::Result<u64> {
    let mut last = fs::metadata(path)?.len();
    let mut since = Instant::now();
    loop {
        thread::sleep(Duration::from_secs(2));
        let size = fs::metadata(path)?.len();
        if size == last {
            if since.elapsed() >= stable_for {
                return Ok(size);
            }
        } else {
            last = size;
            since = Instant::now();
        }
    }
}

fn wait_android_uploaded(cfg: &Config, name: &str, size: u64, timeout: Duration) -> io::Result<()> {
    let start = Instant::now();
    let status_path = format!("{}/status/{name}.json", cfg.android_dir);
    while start.elapsed() < timeout {
        let status = adb_text(cfg, &["shell", "cat", &status_path]).unwrap_or_default();
        if status.contains("\"state\":\"uploaded\"") && status.contains(&format!("\"size\":{size}")) {
            return Ok(());
        }
        if status.contains("\"state\":\"failed\"") {
            return Err(io::Error::new(io::ErrorKind::Other, status));
        }
        thread::sleep(Duration::from_secs(cfg.poll_secs));
    }
    Err(io::Error::new(io::ErrorKind::TimedOut, "android upload status timeout"))
}

fn adb(cfg: &Config, args: &[&str]) -> io::Result<()> {
    let status = adb_cmd(cfg, args).status()?;
    if status.success() {
        Ok(())
    } else {
        Err(io::Error::new(io::ErrorKind::Other, format!("adb failed: {args:?}")))
    }
}

fn adb_text(cfg: &Config, args: &[&str]) -> io::Result<String> {
    let output = adb_cmd(cfg, args).output()?;
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).to_string())
    } else {
        Err(io::Error::new(io::ErrorKind::Other, String::from_utf8_lossy(&output.stderr).to_string()))
    }
}

fn adb_cmd(cfg: &Config, args: &[&str]) -> Command {
    let mut cmd = Command::new(&cfg.adb);
    if let Some(serial) = &cfg.serial {
        cmd.arg("-s").arg(serial);
    }
    cmd.args(args);
    cmd
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_completed_tar_md5_is_processed() {
        assert!(is_tar_md5(Path::new("AP.tar.md5")));
        assert!(!is_tar_md5(Path::new("AP.tar.md5.part")));
        assert!(!is_tar_md5(Path::new("AP.zip")));
    }
}
