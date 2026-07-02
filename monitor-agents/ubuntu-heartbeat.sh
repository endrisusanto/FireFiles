#!/usr/bin/env bash
set -euo pipefail

URL="${MONITOR_URL:-https://files.endrisusanto.my.id/api/heartbeat}"
TOKEN="${MONITOR_TOKEN:-change-me}"
ID="${MONITOR_ID:-ubuntu}"
NAME="${MONITOR_NAME:-$(hostname)}"

cpu_percent() {
  read -r _ u n s i w irq soft steal _ < /proc/stat
  idle1=$((i + w)); total1=$((u + n + s + i + w + irq + soft + steal))
  sleep 1
  read -r _ u n s i w irq soft steal _ < /proc/stat
  idle2=$((i + w)); total2=$((u + n + s + i + w + irq + soft + steal))
  awk -v idle=$((idle2-idle1)) -v total=$((total2-total1)) 'BEGIN{printf "%.0f", 100*(1-idle/total)}'
}

ram_total=$(awk '/MemTotal/{print $2 * 1024}' /proc/meminfo)
ram_free=$(awk '/MemAvailable/{print $2 * 1024}' /proc/meminfo)
storage_total=$(df -B1 / | awk 'NR==2{print $2}')
storage_free=$(df -B1 / | awk 'NR==2{print $4}')
cpu=$(cpu_percent)

json=$(printf '{"id":"%s","name":"%s","kind":"ubuntu","status":"ok","cpu_percent":%s,"ram_percent":%.0f,"ram_free_bytes":%s,"storage_percent":%.0f,"storage_free_bytes":%s}' \
  "$ID" "$NAME" "$cpu" \
  "$(awk -v f="$ram_free" -v t="$ram_total" 'BEGIN{print 100*(1-f/t)}')" "$ram_free" \
  "$(awk -v f="$storage_free" -v t="$storage_total" 'BEGIN{print 100*(1-f/t)}')" "$storage_free")

curl -fsS -H "content-type: application/json" -H "x-monitor-token: $TOKEN" -d "$json" "$URL" >/dev/null
