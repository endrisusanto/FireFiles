import { invoke } from "@tauri-apps/api/core";

const $ = id => document.getElementById(id);

// ponytail: check platform via userAgent to show/hide linux-only features
const isLinux = navigator.userAgent.toLowerCase().includes("linux");
if (!isLinux) {
  document.querySelectorAll(".linux-only").forEach(el => el.style.display = "none");
}

// Tab Switching
document.querySelectorAll(".tab-btn").forEach(btn => {
  btn.onclick = () => {
    document.querySelectorAll(".tab-btn").forEach(b => b.classList.remove("active"));
    document.querySelectorAll(".tab-content").forEach(c => c.classList.remove("active"));
    btn.classList.add("active");
    $(btn.dataset.tab).classList.add("active");
  };
});

function payload() {
  return {
    source: $("source").value,
    backup: $("backup").value,
    adb: $("adb").value || "adb",
    androidDir: $("androidDir").value || "/sdcard/FirmwareBridge",
    serial: $("serial").value || null,
    stableSecs: Number($("stableSecs").value || 20)
  };
}

async function refresh() {
  const running = await invoke("is_running");
  $("state").textContent = running ? "running" : "stopped";
  $("state").className = running ? "running" : "";
}

$("start").onclick = async () => {
  try {
    $("log").textContent = await invoke("start_worker", { cfg: payload() });
  } catch (e) {
    $("log").textContent = String(e);
  }
  refresh();
};

$("stop").onclick = async () => {
  $("log").textContent = await invoke("stop_worker");
  refresh();
};

// Fetch Config from Monitor Server
$("fetchConfigBtn").onclick = async () => {
  const url = $("monitorUrl").value.trim();
  const token = $("monitorToken").value.trim();
  if (!url) {
    $("log").textContent = "Error: Monitor URL cannot be empty.";
    return;
  }
  $("log").textContent = "Fetching configuration...";
  try {
    const raw = await invoke("fetch_smb_config", { url, token });
    const cfg = JSON.parse(raw);
    if (cfg.host) {
      // Auto fill atau tampilkan hasil
      $("log").textContent = `Configuration fetched successfully:\n${JSON.stringify(cfg, null, 2)}`;
      // Pada client, kita set target path atau info
      if (cfg.folder) {
        $("androidDir").value = `/sdcard/FirmwareBridge/${cfg.folder}`;
      }
    } else {
      $("log").textContent = "Server returned empty config. Setup receiver first.";
    }
  } catch (e) {
    $("log").textContent = `Fetch failed: ${e}`;
  }
};

// Linux Setup Samba and Register
if (isLinux) {
  // Generate random pass on start
  invoke("generate_password").then(pass => {
    $("smbPass").value = pass;
  }).catch(() => {});

  invoke("detect_ip").then(ip => {
    $("log").textContent += `\nDetected LAN IP: ${ip}`;
  }).catch(() => {});

  $("setupSambaBtn").onclick = async () => {
    const shareDir = $("shareDir").value.trim();
    const shareName = $("shareName").value.trim();
    const smbUser = $("smbUser").value.trim();
    const smbPass = $("smbPass").value.trim();
    const url = $("monitorUrl").value.trim();
    const token = $("monitorToken").value.trim();

    if (!shareDir) {
      $("log").textContent = "Error: Samba Folder Path is required.";
      return;
    }

    $("log").textContent = "Setting up Samba... check prompt for password.";
    try {
      const msg = await invoke("setup_samba", { shareDir, shareName, user: smbUser, pass: smbPass });
      $("log").textContent = msg;

      // Detect host IP to register
      const host = await invoke("detect_ip");
      $("log").textContent += `\nRegistering config with IP: ${host}`;

      await invoke("register_config", {
        url, token, host, share: shareName, user: smbUser, pass: smbPass, folder: ""
      });
      $("log").textContent += "\n✓ Registered successfully to server!";
    } catch (e) {
      $("log").textContent += `\nFailed: ${e}`;
    }
  };
}

// Folder Picker click handler
document.querySelectorAll(".pick-btn").forEach(btn => {
  btn.onclick = async () => {
    const targetId = btn.dataset.target;
    try {
      const selected = await invoke("pick_folder");
      if (selected) {
        $(targetId).value = selected;
      }
    } catch (e) {
      $("log").textContent = `Failed to pick folder: ${e}`;
    }
  };
});

refresh();
setInterval(refresh, 3000);

