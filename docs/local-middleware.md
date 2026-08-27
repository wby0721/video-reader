# 本机（无 Docker）中间件启动手册

> 适用场景：本机已原生安装 MySQL / Redis，或 Docker（WSL2）不可用时的本地演示。
> 生产/标准演示推荐 `docker compose up -d`。

## 端口约定

| 组件 | 端口 | 后端配置默认值 |
|:---|:---|:---|
| MySQL | 3306 | root / root123，库 `video_agent` |
| Redis | 6379 | 无密码 |
| Kafka | 9092 | localhost:9092 |
| MinIO | 9000 / 9001 | minioadmin / minioadmin123，桶 `video-media` |
| Qdrant | 6333 / 6334 | 无鉴权 |

## 1. MySQL（用户态，无需管理员）

```powershell
# 初始化数据目录（仅首次）
mysqld --initialize-insecure --datadir=E:\agent_projct\.tools\mysql-data
# 启动
mysqld --datadir=E:\agent_projct\.tools\mysql-data --port=3306 --bind-address=127.0.0.1 --console
# 设置 root 密码并建库（首次）
mysql -h 127.0.0.1 -u root -e "ALTER USER 'root'@'localhost' IDENTIFIED BY 'root123'; CREATE DATABASE IF NOT EXISTS video_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

> 注：mysqld / redis-server 等需要以非沙箱进程运行（本机原生服务亦可 `sc start MySQL`）。

## 2. Redis

本机已装 `Redis-7.4.10-Windows-x64-msys2`（服务方式）或使用原生构建：

```powershell
redis-server.exe --port 6379 --bind 127.0.0.1
```

## 3. Kafka（原生 Java，KRaft 单节点）

```powershell
$env:JAVA_HOME = "C:\path\to\jdk21+"
java -cp "kafka_2.13-3.8.0\libs\*" kafka.tools.StorageTool random-uuid   # 生成 cluster id
java -cp "kafka_2.13-3.8.0\libs\*" kafka.tools.StorageTool format -t <uuid> -c kraft-server.properties
java -cp "kafka_2.13-3.8.0\libs\*" kafka.Kafka kraft-server.properties
```

`kraft-server.properties` 在默认单节点模板上仅需改 `log.dirs` 到本地目录。

## 4. MinIO（原生 exe）

```powershell
$env:MINIO_ROOT_USER="minioadmin"; $env:MINIO_ROOT_PASSWORD="minioadmin123"
minio.exe server E:\data\minio --console-address ":9001"
```

## 5. Qdrant（官方 Windows 构建）

```powershell
qdrant.exe --config-path qdrant-config.yaml
# qdrant-config.yaml: storage.storage_path 指向本地目录, service.http_port: 6333
```

## 验证

```powershell
Test-NetConnection 127.0.0.1 -Port 3306/6379/9092/9000/6333   # 依次验证
curl http://localhost:6333/readyz
```
