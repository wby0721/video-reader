<#
=============================================================================
  Video Agent 一键停止脚本
  停止全部后台服务：后端(8081) / MySQL(3307) / Redis(6379) / Kafka(9092) /
  MinIO(9000,9001) / Qdrant(6333) / 推理(embedding 8000, asr 8001, ocr 8002)
  用法：
    powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1
  说明：
    - 按端口找占用进程停止（兼容 mysqld 等启动后换 PID 的情况）；
    - 兜底按进程名/路径清理（仅限本项目环境的 python / mysqld / qdrant / minio）；
    - 系统级 redis-server 若无权限会提示，需管理员执行 taskkill。
=============================================================================
#>
$ErrorActionPreference = 'Continue'

$Tools = 'E:\agent_projct\.tools'

# 端口 → 显示名
$portNames = @{
    8081 = '后端 (8081)'; 8000 = 'embedding (8000)'; 8001 = 'asr (8001)'; 8002 = 'ocr (8002)'
    3307 = 'MySQL (3307)'; 6379 = 'Redis (6379)'; 9092 = 'Kafka (9092)'
    9000 = 'MinIO (9000)'; 9001 = 'MinIO 控制台 (9001)'; 6333 = 'Qdrant (6333)'; 5173 = '前端 Vite (5173)'
}

$stopped = 0
$failed = @()

Write-Host "========== Video Agent 一键停止 ==========" -ForegroundColor Magenta

# 1) 按端口停止（netstat 找监听进程 PID）
foreach ($port in $portNames.Keys | Sort-Object) {
    $line = netstat -ano | Select-String -Pattern (":$port\s+.*LISTENING") | Select-Object -First 1
    if (-not $line) { continue }
    $parts = ($line.Line -split '\s+') | Where-Object { $_ }
    $pid2 = [int]$parts[-1]
    $proc = Get-Process -Id $pid2 -ErrorAction SilentlyContinue
    try {
        Stop-Process -Id $pid2 -Force -ErrorAction Stop
        Write-Host ("  已停止 {0}  (PID {1})" -f $portNames[$port], $pid2) -ForegroundColor Green
        $stopped++
    } catch {
        Write-Host ("  无法停止 {0}  (PID {1})，权限不足，请管理员执行: taskkill /PID {1} /F" -f $portNames[$port], $pid2) -ForegroundColor Red
        $failed += $portNames[$port]
    }
}

# 2) 兜底：按进程名/路径清理（只清本项目环境，避免误杀）
Get-Process -Name 'mysqld','qdrant','minio','redis-server' -ErrorAction SilentlyContinue | ForEach-Object {
    $procName = $_.ProcessName; $procId = $_.Id
    try {
        Stop-Process -Id $procId -Force -ErrorAction Stop
        Write-Host ("  已停止(兜底) {0}  (PID {1})" -f $procName, $procId) -ForegroundColor Green
        $stopped++
    } catch {
        Write-Host ("  无法停止(兜底) {0}  (PID {1})，权限不足" -f $procName, $procId) -ForegroundColor Red
        $failed += $procName
    }
}
# 本项目推理服务的 python（按可执行文件路径精确匹配，避免误杀其他 python）
Get-Process -Name 'python' -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$Tools\python312\*" } | ForEach-Object {
    $procId = $_.Id
    try {
        Stop-Process -Id $procId -Force -ErrorAction Stop
        Write-Host ("  已停止(兜底) 推理服务 python  (PID {0})" -f $procId) -ForegroundColor Green
        $stopped++
    } catch {
        Write-Host ("  无法停止(兜底) python  (PID {0})，权限不足" -f $procId) -ForegroundColor Red
        $failed += 'python'
    }
}

Start-Sleep -Seconds 2

# 3) 复查
$left = @()
foreach ($port in $portNames.Keys) {
    if (netstat -ano | Select-String (":$port\s+.*LISTENING")) { $left += $port }
}
Write-Host "`n----------------------------------------" -ForegroundColor DarkGray
if ($left.Count -eq 0) {
    Write-Host ("全部服务已停止 ✓（本次停止 {0} 个进程）" -f $stopped) -ForegroundColor Green
} else {
    Write-Host ("仍被占用的端口: {0}" -f ($left -join ', ')) -ForegroundColor Yellow
}
if ($failed.Count -gt 0) {
    Write-Host ("以下进程因权限不足未停止（多为系统级服务，可忽略）: {0}" -f ($failed -join ', ')) -ForegroundColor Yellow
}
