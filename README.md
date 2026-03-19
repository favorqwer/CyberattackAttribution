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

参数说明：

1. 日志与案例配置所在目录
2. 结果输出目录
3. 日志文件名，多个文件用分号分隔

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
