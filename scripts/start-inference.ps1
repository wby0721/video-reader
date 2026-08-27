# 启动本地推理服务（ASR 8001 / OCR 8002 / Embedding 8000）
# 用法：powershell -ExecutionPolicy Bypass -File scripts/start-inference.ps1
$ErrorActionPreference = "Stop"
$py = "E:\agent_projct\.tools\python312\python.exe"

$jobs = @(
    @{ Name = "embedding"; Port = 8000; File = "E:\agent_projct\video_reader\inference\embedding\app.py" },
    @{ Name = "asr";       Port = 8001; File = "E:\agent_projct\video_reader\inference\asr\app.py" },
    @{ Name = "ocr";       Port = 8002; File = "E:\agent_projct\video_reader\inference\ocr\app.py" }
)

foreach ($j in $jobs) {
    $already = Test-NetConnection -ComputerName 127.0.0.1 -Port $j.Port -WarningAction SilentlyContinue
    if ($already.TcpTestSucceeded) {
        Write-Host "[$($j.Name)] 已在 $($j.Port) 端口运行，跳过" -ForegroundColor Yellow
        continue
    }
    Write-Host "[$($j.Name)] 启动中 -> http://localhost:$($j.Port)" -ForegroundColor Cyan
    Start-Process -FilePath $py -ArgumentList $j.File -WindowStyle Hidden
    Start-Sleep -Seconds 2
}

Write-Host "全部推理服务启动命令已发出；检查端口可用性："
foreach ($j in $jobs) {
    $t = Test-NetConnection -ComputerName 127.0.0.1 -Port $j.Port -WarningAction SilentlyContinue
    Write-Host "  $($j.Name) : $($j.Port) -> $(if ($t.TcpTestSucceeded) { 'UP' } else { 'starting...' })"
}
