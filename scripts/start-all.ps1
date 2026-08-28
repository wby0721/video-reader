<#
=============================================================================
  Video Agent 一键启动脚本
  依次启动：MySQL → Redis → Kafka → MinIO → Qdrant → 推理(embedding/asr/ocr)
           → 后端(8081) → 前端 Vite(5173)
  用法：
    powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1              # 全部启动（含前端）
    powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1 -SkipFrontend # 不启动前端
  退出：按 Ctrl+C（或关闭窗口）自动停止本次启动的全部服务。
  日志：E:\agent_projct\.tools\logs\<服务名>.log / .err.log
=============================================================================
#>
param([switch]$SkipFrontend)

$ErrorActionPreference = 'Stop'

# ---------------- 路径 ----------------
$Root    = 'E:\agent_projct'
$Tools   = "$Root\.tools"
$Logs    = "$Tools\logs"
New-Item -ItemType Directory -Force -Path $Logs | Out-Null

$Py        = "$Tools\python312\python.exe"
$JavaHome  = 'C:\Users\wby\.jdks\openjdk-26.0.2'
$Java      = "$JavaHome\bin\java.exe"
$Mysqld    = 'E:\mysql-26.7.0-winx64\bin\mysqld.exe'
$MysqlCli  = 'E:\mysql-26.7.0-winx64\bin\mysql.exe'
$Redis     = "$Tools\redis-native\redis-server.exe"
$KafkaDir  = "$Tools\kafka_2.13-3.8.0"
$KafkaProps= "$Tools\kraft-server.properties"
$Minio     = "$Tools\minio.exe"
$Qdrant    = "$Tools\qdrant\qdrant.exe"
$QdrantCfg = "$Tools\qdrant-config.yaml"
$Jar       = "$Root\video_reader\server\target\video-reader-server-0.1.0-SNAPSHOT.jar"
$Client    = "$Root\video_reader\client"

# ---------------- 密钥加载（安全约定） ----------------
# 本仓库【不包含任何真实密钥】。密钥仅通过环境变量或项目根目录 .env 文件提供：
#   1) 先读取项目根目录 .env（已 gitignore，参考 .env.example 创建）
#   2) 已存在的同名环境变量优先（脚本不覆盖）
# 未配置 LLM Key 时后端仍可启动，Agent 会在真正调用时提示"请配置 LLM_API_KEY 或用户自带 Key"。
$EnvFile = Join-Path $Root '.env'
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith('#')) {
            $idx = $line.IndexOf('=')
            if ($idx -gt 0) {
                $k = $line.Substring(0, $idx).Trim()
                $v = $line.Substring($idx + 1).Trim().Trim('"').Trim("'")
                if (-not [Environment]::GetEnvironmentVariable($k)) {
                    [Environment]::SetEnvironmentVariable($k, $v)
                }
            }
        }
    }
    Write-Host "[env] 已从 .env 加载配置（$EnvFile）"
}

$EnvLLMKey   = $env:LLM_API_KEY
$EnvLLMModel = if ([string]::IsNullOrWhiteSpace($env:LLM_MODEL)) { 'deepseek-v4-flash' } else { $env:LLM_MODEL }
$EnvXfAppid  = $env:XF_APPID
$EnvXfApikey = $env:XF_APIKEY
$EnvXfSecret = $env:XF_APISECRET

if ([string]::IsNullOrWhiteSpace($EnvLLMKey)) {
    Write-Host "[warn] 未配置 LLM_API_KEY（环境变量或 .env）——Agent 分析需要 LLM，用户可在个人设置里提交自己的 Key"
}

# ---------------- 辅助函数 ----------------
$started = New-Object System.Collections.Generic.List[object]

# 端口探测：netstat（Get-NetTCPConnection 在部分受限环境下不可见，netstat 更稳）
function Test-Port([int]$Port) {
    return [bool](netstat -ano | Select-String -Pattern (":$Port\s+.*LISTENING") | Select-Object -First 1)
}

function Start-Svc {
    param([string]$Name, [string]$File, [string[]]$ArgList = @(), [hashtable]$Env = @{}, [int]$Port = 0)
    if (-not (Test-Path $File)) { Write-Host "[$Name] 找不到 $File，跳过" -ForegroundColor Yellow; return }
    if ($Port -gt 0 -and (Test-Port $Port)) { Write-Host "[$Name] 已在端口 $Port 运行，跳过" -ForegroundColor Yellow; return }
    # 设置子进程环境变量（结束后还原）
    $old = @{}
    foreach ($k in $Env.Keys) {
        $old[$k] = [Environment]::GetEnvironmentVariable($k)
        [Environment]::SetEnvironmentVariable($k, [string]$Env[$k])
    }
    $out = "$Logs\$Name.log"; $err = "$Logs\$Name.err.log"
    $p = Start-Process -FilePath $File -ArgumentList $ArgList -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $out -RedirectStandardError $err
    foreach ($k in $Env.Keys) {
        if ($null -eq $old[$k]) { [Environment]::SetEnvironmentVariable($k, $null) }
        else { [Environment]::SetEnvironmentVariable($k, $old[$k]) }
    }
    $started.Add([pscustomobject]@{ Name = $Name; Pid = $p.Id; Port = $Port })
    Write-Host "[$Name] 启动 (PID $($p.Id)) -> 日志 $out" -ForegroundColor Cyan
}

function Wait-Port([int]$Port, [int]$Seconds = 60, [string]$Name = "port $Port") {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        if (Test-Port $Port) { Write-Host "  [$Name] 就绪 ($Port)" -ForegroundColor Green; return $true }
        Start-Sleep -Seconds 2
    }
    Write-Host "  [$Name] 等待超时（$Seconds 秒后仍未监听 $Port）" -ForegroundColor Red
    return $false
}

function Wait-Http([string]$Url, [int]$Seconds = 60, [string]$Name = $Url) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        try {
            $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { Write-Host "  [$Name] 就绪" -ForegroundColor Green; return $true }
        } catch {}
        Start-Sleep -Seconds 2
    }
    Write-Host "  [$Name] 等待超时" -ForegroundColor Red
    return $false
}

# 按端口找占用进程 PID（netstat 输出：... LISTENING   <PID>）
function Get-PortPid([int]$Port) {
    $line = netstat -ano | Select-String -Pattern (":$Port\s+.*LISTENING") | Select-Object -First 1
    if (-not $line) { return $null }
    $parts = ($line.Line -split '\s+') | Where-Object { $_ }
    return [int]$parts[-1]
}

function Stop-All {
    Write-Host "`n正在停止本次启动的全部服务 ..." -ForegroundColor Yellow
    foreach ($s in $started) {
        Stop-Process -Id $s.Pid -Force -ErrorAction SilentlyContinue
        if ($s.Port -gt 0) {
            # 兜底：某些服务启动后 PID 会变化（如 mysqld），按端口占用者再杀一次
            $pid2 = Get-PortPid $s.Port
            if ($pid2) { Stop-Process -Id $pid2 -Force -ErrorAction SilentlyContinue }
        }
        Write-Host "  已停止 $($s.Name) (PID $($s.Pid))"
    }
    $started.Clear()
}

# ---------------- 主流程 ----------------
Write-Host "========== Video Agent 一键启动 ==========" -ForegroundColor Magenta
Write-Host "日志目录: $Logs`n" -ForegroundColor DarkGray

try {
    # 1) MySQL (3307)
    if (-not (Test-Port 3307)) {
        Start-Svc -Name mysql -File $Mysqld -ArgList @("--datadir=$Tools\mysql-data", "--port=3307", "--bind-address=127.0.0.1") -Port 3307
        $null = Wait-Port 3307 60 'MySQL'
        # 验证可登录（MYSQL_PWD 避免命令行密码告警）
        $env:MYSQL_PWD = 'root123'
        for ($i = 0; $i -lt 5; $i++) {
            & $MysqlCli -h 127.0.0.1 -P 3307 -u root -e "SELECT 1" 2>&1 | Out-Null
            if ($LASTEXITCODE -eq 0) { Write-Host "  [MySQL] 可登录" -ForegroundColor Green; break }
            Start-Sleep -Seconds 3
        }
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
    } else { Write-Host "[MySQL] 已在 3307 运行，跳过" -ForegroundColor Yellow }

    # 2) Redis (6379)
    if (-not (Test-Port 6379)) {
        Start-Svc -Name redis -File $Redis -Port 6379
        $null = Wait-Port 6379 30 'Redis'
    } else { Write-Host "[Redis] 已在 6379 运行，跳过" -ForegroundColor Yellow }

    # 3) Kafka (9092)
    if (-not (Test-Port 9092)) {
        Start-Svc -Name kafka -File $Java -ArgList @('-cp', "$KafkaDir\libs\*", 'kafka.Kafka', $KafkaProps) -Port 9092
        $null = Wait-Port 9092 90 'Kafka'
    } else { Write-Host "[Kafka] 已在 9092 运行，跳过" -ForegroundColor Yellow }

    # 4) MinIO (9000 / 控制台 9001)
    if (-not (Test-Port 9000)) {
        Start-Svc -Name minio -File $Minio -ArgList @('server', "$Tools\minio-data", '--console-address', ':9001') `
            -Env @{ MINIO_ROOT_USER = 'minioadmin'; MINIO_ROOT_PASSWORD = 'minioadmin123' } -Port 9000
        $null = Wait-Http 'http://localhost:9000/minio/health/live' 30 'MinIO'
    } else { Write-Host "[MinIO] 已在 9000 运行，跳过" -ForegroundColor Yellow }

    # 5) Qdrant (6333)
    if (-not (Test-Port 6333)) {
        Start-Svc -Name qdrant -File $Qdrant -ArgList @('--config-path', $QdrantCfg) -Port 6333
        $null = Wait-Http 'http://localhost:6333/readyz' 30 'Qdrant'
    } else { Write-Host "[Qdrant] 已在 6333 运行，跳过" -ForegroundColor Yellow }

    # 6) 推理服务 (embedding 8000 / asr 8001 / ocr 8002)
    if (-not (Test-Port 8000)) { Start-Svc -Name embedding -File $Py -ArgList @("$Root\video_reader\inference\embedding\app.py") -Port 8000 }
    if (-not (Test-Port 8001)) { Start-Svc -Name asr -File $Py -ArgList @("$Root\video_reader\inference\asr\app.py") -Port 8001 -Env @{ XF_APPID = $EnvXfAppid; XF_APIKEY = $EnvXfApikey; XF_APISECRET = $EnvXfSecret } }
    if (-not (Test-Port 8002)) { Start-Svc -Name ocr -File $Py -ArgList @("$Root\video_reader\inference\ocr\app.py") -Port 8002 }
    $null = Wait-Http 'http://localhost:8000/health' 60 'embedding'
    $null = Wait-Http 'http://localhost:8001/health' 60 'asr'
    $null = Wait-Http 'http://localhost:8002/health' 60 'ocr'
    # OCR GPU 提示（可选加速）：未装 onnxruntime-gpu 时为 CPU 推理
    try {
        $ocrCuda = & $Py -c "import onnxruntime as ort; print('CUDAExecutionProvider' in ort.get_available_providers())" 2>$null
        if ($ocrCuda -ne 'True') {
            Write-Host "  [提示] OCR 当前为 CPU 推理；执行以下命令可启用 GPU 加速（见 README）：" -ForegroundColor Yellow
            Write-Host "    $Py -m pip install onnxruntime-gpu -i https://repo.huaweicloud.com/repository/pypi/simple/" -ForegroundColor Yellow
        }
    } catch {}

    # 7) 后端 (8081)
    if (-not (Test-Port 8081)) {
        if (-not (Test-Path $Jar)) { throw "后端 jar 不存在: $Jar，请先执行 mvn -DskipTests package 构建" }
        $mysqlUrl = 'jdbc:mysql://localhost:3307/video_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
        Start-Svc -Name backend -File $Java -ArgList @('-jar', $Jar) -Port 8081 -Env @{
            SERVER_PORT    = '8081'
            MYSQL_URL      = $mysqlUrl
            MYSQL_USER     = 'root'
            MYSQL_PASSWORD = 'root123'
            FFMPEG_PATH    = "$Tools\ffmpeg\ffmpeg.exe"
            LLM_API_KEY    = $EnvLLMKey
            LLM_MODEL      = $EnvLLMModel
        }
        if (-not (Wait-Http 'http://localhost:8081/health' 120 'backend')) {
            Write-Host "后端启动失败，请查看 $Logs\backend.err.log" -ForegroundColor Red
            throw 'backend failed to start'
        }
    } else { Write-Host "[backend] 已在 8081 运行，跳过" -ForegroundColor Yellow }

    # 8) 前端 (5173)
    if (-not $SkipFrontend) {
        if (-not (Test-Path "$Client\node_modules")) {
            Write-Host "[前端] node_modules 缺失，执行 npm install ..." -ForegroundColor Cyan
            Push-Location $Client
            & npm.cmd install --registry https://registry.npmmirror.com
            Pop-Location
        }
        Write-Host "`n[前端] 启动 Vite dev server -> http://localhost:5173（Ctrl+C 停止全部）" -ForegroundColor Green
        Push-Location $Client
        & npm.cmd run dev
        Pop-Location
    } else {
        Write-Host "`n全部服务已启动（未启动前端）。" -ForegroundColor Green
        Write-Host "按 Ctrl+C 停止全部服务。前端可另开终端执行: cd client ; npm run dev" -ForegroundColor DarkGray
        while ($true) { Start-Sleep -Seconds 5 }
    }
}
finally {
    Stop-All
    Write-Host "已全部停止，再见。" -ForegroundColor Magenta
}
