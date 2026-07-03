import { invoke } from "@tauri-apps/api/core";

const $ = id => document.getElementById(id);

// Check platform via userAgent to show/hide linux-only features
const isLinux = navigator.userAgent.toLowerCase().includes("linux");
if (!isLinux) {
  document.querySelectorAll(".linux-only").forEach(el => el.style.display = "none");
  document.querySelectorAll(".windows-only").forEach(el => el.style.display = "block");
} else {
  document.querySelectorAll(".linux-only").forEach(el => el.style.display = "block");
  document.querySelectorAll(".windows-only").forEach(el => el.style.display = "none");
}

// Wizard Step Navigation Logic
let currentStep = 1;
const maxSteps = 3;

function updateWizardUI() {
  // Toggle visible step panels
  for (let i = 1; i <= maxSteps; i++) {
    const stepPanel = $(`wizard-step-${i}`);
    const stepInd = $(`ind-step${i}`);
    
    if (i === currentStep) {
      stepPanel.classList.add("active");
      stepInd.classList.add("active");
      stepInd.classList.remove("completed");
    } else {
      stepPanel.classList.remove("active");
      if (i < currentStep) {
        stepInd.classList.add("completed");
        stepInd.classList.remove("active");
      } else {
        stepInd.classList.remove("active", "completed");
      }
    }
  }

  // Update navigation buttons
  $("prevBtn").disabled = currentStep === 1;
  
  if (currentStep === maxSteps) {
    $("nextBtn").textContent = "Finish ✓";
    $("nextBtn").style.display = "none"; // Hide next on the final control step to focus on start/stop
  } else {
    $("nextBtn").textContent = "Next →";
    $("nextBtn").style.display = "inline-flex";
  }
}

$("nextBtn").onclick = () => {
  if (currentStep < maxSteps) {
    currentStep++;
    updateWizardUI();
  }
};

$("prevBtn").onclick = () => {
  if (currentStep > 1) {
    currentStep--;
    updateWizardUI();
  }
};

// Make step indicators clickable to jump between steps
document.querySelectorAll(".step-indicator").forEach(el => {
  el.onclick = () => {
    currentStep = Number(el.dataset.step);
    updateWizardUI();
  };
});

// Sync Worker payload
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
  $("log").textContent = "Fetching configurations...";
  try {
    const raw = await invoke("fetch_smb_config", { url, token });
    const cfg = JSON.parse(raw);
    if (cfg.host) {
      $("log").textContent = `Configuration synced successfully:\n${JSON.stringify(cfg, null, 2)}`;
      
      // Auto-fill target folder & input directories
      if (cfg.folder) {
        $("androidDir").value = `/sdcard/FirmwareBridge/${cfg.folder}`;
      }
      
      // On Windows/Linux, if Samba path matches, we auto fill source directory
      if (cfg.share && cfg.host) {
        if (!isLinux) {
          $("source").value = `\\\\${cfg.host}\\${cfg.share}`;
        } else {
          // If we are on Linux setup, we can use local mounting path or configured share dir
          if ($("shareDir").value) {
            $("source").value = $("shareDir").value;
          }
        }
      }
    } else {
      $("log").textContent = "Server returned empty configurations.";
    }
  } catch (e) {
    $("log").textContent = `Fetch failed: ${e}`;
  }
};

// Linux Setup Samba and Register Flow
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

    $("log").textContent = "Setting up Samba... please type password when requested in prompt.";
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
  btn.onclick = async (e) => {
    e.preventDefault();
    const targetId = btn.dataset.target;
    $("log").textContent = `Opening folder picker...`;
    try {
      const selected = await invoke("pick_folder");
      if (selected) {
        $(targetId).value = selected;
        $("log").textContent = `Selected path: ${selected}`;
      } else {
        $("log").textContent = `Folder selection cancelled.`;
      }
    } catch (err) {
      $("log").textContent = `Failed to pick folder: ${err}`;
    }
  };
});

// Initial boot configurations
updateWizardUI();
refresh();
setInterval(refresh, 3000);
