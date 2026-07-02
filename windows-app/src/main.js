import { invoke } from "@tauri-apps/api/core";

const $ = id => document.getElementById(id);

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

refresh();
setInterval(refresh, 3000);
