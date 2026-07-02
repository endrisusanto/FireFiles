param(
  [string]$Url = $env:MONITOR_URL,
  [string]$Token = $env:MONITOR_TOKEN,
  [string]$Id = $env:MONITOR_ID,
  [string]$Name = $env:COMPUTERNAME
)

if (-not $Url) { $Url = "https://files.endrisusanto.my.id/api/heartbeat" }
if (-not $Token) { $Token = "change-me" }
if (-not $Id) { $Id = "windows" }

$os = Get-CimInstance Win32_OperatingSystem
$disk = Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:'"
$cpu = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
$worker = Get-Process firefiles-worker -ErrorAction SilentlyContinue

$body = @{
  id = $Id
  name = $Name
  kind = "windows"
  status = $(if ($worker) { "worker_running" } else { "worker_missing" })
  cpu_percent = [int]$cpu
  ram_percent = [int](100 * (1 - ($os.FreePhysicalMemory / $os.TotalVisibleMemorySize)))
  ram_free_bytes = [int64]$os.FreePhysicalMemory * 1024
  storage_percent = [int](100 * (1 - ($disk.FreeSpace / $disk.Size)))
  storage_free_bytes = [int64]$disk.FreeSpace
  detail = $(if ($worker) { "pid " + (($worker | Select-Object -First 1).Id) } else { "firefiles-worker not found" })
} | ConvertTo-Json -Compress

Invoke-RestMethod -Method Post -Uri $Url -Headers @{"x-monitor-token"=$Token} -ContentType "application/json" -Body $body | Out-Null
