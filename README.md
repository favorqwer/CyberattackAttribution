# DepImpact

## 运行方式

运行前需要准备：

- Sysdig 导出的日志文件，后缀为 `.txt`
- 与日志同名的案例配置文件，后缀为 `.property`
- 根目录下的全局配置文件 `depimpact.properties`

日志文件和对应的案例配置文件需要放在同一个目录中。

## 全局配置

`depimpact.properties` 用于统一管理系统级配置，包括：

- `weight_mode`：权重计算模式
- `llm_enabled`、`base_url`、`api_key`、`model`、`temperature`、`max_tokens`：LLM 过滤配置
- `local_ip`：本机 IP 列表
- `default_mid_rp`：默认背景噪音实体
- `syscall.*`：系统调用白名单

示例：

```properties
weight_mode=clusterall
llm_enabled=true
base_url=https://integrate.api.nvidia.com/v1
api_key=your_api_key
model=moonshotai/kimi-k2-instruct-0905
temperature=0.1
max_tokens=20480
local_ip=127.0.0.1
```

## 录制并转换日志

### 1. 录制 Sysdig 原始日志

```bash
sysdig -w capture.scap
```

### 2. 解析 `.scap` 为项目可用的 `.txt`

```bash
sysdig -r capture.scap -p "%evt.rawtime.s.%evt.rawtime.ns;;%evt.cpu;;%proc.name;;%proc.pid;;%evt.dir;;%evt.type;;%proc.cwd;;%evt.latency;;%evt.args" | awk -F ';;' '$9 != "" {print}' > attack.txt
```

案例目录示例：

```text
attack.txt
attack.backward.property
```

## Java 运行

```bash
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar ./test ./test/result attack.txt
```

### Theia/CDM Avro 构图

Theia 数据集可以通过 `.cdm` manifest 作为第三个参数输入。`.cdm` 文件与同名 `.property` 放在同一案例目录下，`.property` 中的 `POI`、`highRP`、`lowRP`、`entry` 继续填写构图后的节点签名，例如文件路径 `/home/admin/.vnc/ta1-theia-target-1:1.log`、网络连接 `local:port->remote:port`，或进程签名 `cid@uuid8name`。

`theia-e5.cdm` 示例：

```properties
cdm.input_dir=Transparent_Computing_Engagement_5_Dataset/Data/theia
cdm.file_glob=ta1-theia-1-e5-official-2.bin*.gz
cdm.start_nanos=1557241091148522395
cdm.end_nanos=1557244691148522395
cdm.max_events=0
```

运行方式仍保持三参数风格：

```powershell
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar ./test ./test/result theia-e5.cdm
```

同时处理多个日志时，可在第三个参数中使用分号分隔多个日志文件名：

```bash
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar ./test ./test/result "attack.txt;cmd-inject.txt"
```

如果希望在运行时手动选择日志，可使用交互模式：

```bash
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar ./test ./test/result --interactive
```

参数说明：

1. 日志与案例配置所在目录
2. 结果输出目录
3. 日志文件名，多个文件用分号分隔

当第三个参数包含多个日志文件名时，程序会按顺序逐个处理，并在结果目录下为每个日志分别生成对应的结果子目录。

当第三个参数为 `--interactive` 或 `-i` 时，程序会在启动后列出当前目录下可选的日志文件，用户可以输入编号或文件名来选择要溯源的日志。

如果使用 VS Code，也可以直接使用 `.vscode/launch.json` 中的 `Run ExperimentRunnerCmd Interactive` 启动配置：

```json
{
  "type": "java",
  "name": "Run ExperimentRunnerCmd Interactive",
  "request": "launch",
  "mainClass": "pagerank.main.ExperimentRunnerCmd",
  "args": "./test ./test/result --interactive",
  "projectName": "reptracker"
}
```

## 支持的权重模式

- `nonml`
- `clusterall`
- `clusterlocal`
- `clusterlocal_dec`
- `localtime`
- `localamount`
- `localstruct`
- `fanout`
- `nonmlrandom`
