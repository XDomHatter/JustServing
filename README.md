# JustServing

轻量级 Linux 服务器管理工具：SSH 连接管理、SFTP 文件传输、服务器监控、交互式终端、远程命令执行、systemd 服务管理、内网穿透（SSH 隧道 + frp），提供 **GUI（Swing）** 界面。

## 功能

1. **SSH 连接** — 服务器连接列表，支持密码 / 私钥（含口令）认证；凭据以主密码（AES-256-GCM + PBKDF2）加密存储于本地保险库。
2. **文件上传下载** — 本机与服务器之间递归上传/下载文件和文件夹，任务队列并发执行、进度/速度/取消/冲突策略（覆盖 / 跳过 / 重命名），GUI 支持从资源管理器拖拽上传。
3. **本机文件打开方式** — 自定义「文件模式 → 命令模板」绑定（如 `*.log → code %f`），双击文件按绑定方式打开；无绑定时回退系统默认。远程文件双击 = 自动下载到临时目录后打开。
4. **服务器监控** — CPU / 内存 / 交换分区使用率、磁盘占用、端口占用（`ss`）、进程占用（`ps` top25）。单次 SSH exec 批量采集，默认 3 秒刷新，无需在服务器安装任何组件（仅支持 Linux）。
5. **交互式终端** — 在应用内直接打开远端 shell（PTY，vim/htop 等全屏程序照常可用），复用已有 SSH 连接与保险库凭据，窗口缩放自动同步远端；GUI 为 JediTerm 嵌入式终端组件。
6. **远程命令** — 保存常用命令为可重复执行的任务（可绑定某台服务器或设为全局），也支持临时输入命令单次执行；输出实时滚动、超时保护、可中途取消，保留最近 100 条执行历史。两种执行通道：
   - **JSch**：复用应用内 SSH 连接，凭保险库里的密码/私钥认证；
   - **原生 ssh**：调用系统 `ssh` 客户端（BatchMode），凭 ssh-agent / 默认密钥 / `~/.ssh/config` 认证，适合 JSch 不支持的新密钥格式或依赖 ssh config 的场景。
7. **服务管理** — 列出服务器上的 systemd 服务单元（运行中的在前，支持按名称/描述过滤），启动 / 停止 / 重启、设为/取消开机自启，一键查看 `systemctl status` 与最近日志。
8. **内网穿透** —
   - **SSH 隧道**：本地转发（-L）/ 远程转发（-R，穿透主力），后台自动探测端口存活；
   - **frp 管理**：生成 frpc/frps 配置（TOML）、本机 frpc 进程启停与日志、服务器端 frps 一键部署（上传二进制 + systemd unit）、启动/停止/重启/日志、token 存入加密保险库。

## 构建与运行

```bash
./gradlew shadowJar        # 产出 build/libs/JustServing-1.0.0-all.jar（约 2 MB）
java -jar build/libs/JustServing-1.0.0-all.jar          # GUI
```

首次启动会要求设置主密码，之后每次启动输入主密码解锁凭据（可在“设置”中修改）。

配置与数据目录：`~/.justserving/`
- `config.json` — 连接 / 隧道 / frp 代理 / 打开方式绑定 / 远程命令任务（明文）
- `vault.json` — 加密的凭据（密码、私钥口令、frps token）
- `frp/` — frpc 二进制与生成的配置
- `known_hosts` — SSH 主机密钥（自动接受新主机并记录）

## 开发

- JDK 25（构建脚本通过 `gradle/gradle-daemon-jvm.properties` 固定守护进程 JVM）
- 依赖：`com.github.mwiede:jsch`（SSH/SFTP/端口转发/交互终端）、`com.google.code.gson:gson`；GUI 为 JDK 内置 Swing + `org.jetbrains.jediterm`（内嵌终端，未发布到 Maven Central，构建脚本配置了 JetBrains 官方仓库）

```bash
./gradlew test          # 单元测试（凭据加解密、监控解析器、TOML 生成、绑定匹配、命令通道参数、systemctl 解析、终端连接器等）
```

## 注意事项

- 监控 / 服务管理 / frps 管理假设服务器为 Linux（systemd），且当前用户为 root 或具有 sudo 免密权限（`ss`/`ps`/`systemctl`）。
- 交互式终端复用应用内 SSH 连接：断开服务器（`x`）会一并终止其终端会话。
- 原生 ssh 通道使用 BatchMode，无法交互输入密码：密码认证的连接请用 JSch 通道，原生通道需 ssh-agent、默认密钥或 `~/.ssh/config` 已配置。
- SSH 远程转发若要绑定到服务器公网地址，需 sshd 开启 `GatewayPorts`。
- 端口占用表中无进程名的条目是权限不足所致（服务器端需要 root 才能看到其他用户进程）。
