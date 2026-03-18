# DepImpact

## 运行方式

在运行之前，需要准备好日志文件及其相关的属性文件。日志文件应以 `.txt` 结尾，属性文件应与对应日志同名，并以 `.backward.property` 结尾。日志文件和属性文件必须存放在同一个目录中。LLM 相关配置位于 `llm.properties`。

## 在 Linux 上录制并解析日志

### 1. 录制 Sysdig 原始日志

```bash
sysdig -w capture.scap
```

该命令会将采集到的系统调用日志写入 `capture.scap`。

### 2. 解析 `.scap` 并生成项目可用的 `.txt` 日志

```bash
sysdig -r capture.scap -p "%evt.rawtime.s.%evt.rawtime.ns;;%evt.cpu;;%proc.name;;%proc.pid;;%evt.dir;;%evt.type;;%proc.cwd;;%evt.latency;;%evt.args" | awk -F ';;' '$9 != "" {print}' > attack.txt
```

该命令会读取 `capture.scap`，按项目所需格式导出日志，并过滤掉第 9 列为空的记录，最终生成 `attack.txt`。

生成后的文件可作为输入日志使用，并需要配套准备同名属性文件，例如：

```text
attack.txt
attack.backward.property
```

## llm.properties 配置说明

`llm.properties` 用于控制是否启用 LLM 图过滤，以及配置 LLM 服务连接参数。

```properties
llm_enabled=true
base_url=https://integrate.api.nvidia.com/v1
api_key=your_api_key
model=moonshotai/kimi-k2-instruct-0905
```

- `llm_enabled=true`：启用 LLM 过滤流程。
- `llm_enabled=false`：跳过 LLM 过滤，直接返回原始溯源图。
- 启用 LLM 时，需要正确配置 `base_url`、`api_key`、`model`。

示例文件：

```text
cmd_inject.txt
cmd_inject.backward.property
```

## 通过 Java 运行

运行类 `ExperimentRunnerCmd`。参数如下：

1. 日志与属性文件所在目录路径
2. 结果输出目录路径
3. 日志文件名称
4. 模式 `mode`（当前代码中默认固定为 `clusterall`）

支持的模式包括：

- `nonml`：手动投影
- `clusterall`：对所有边进行聚类
- `nonoutlier`：排除离群点后再聚类
- `clusterlocal`：局部聚类
- `localtime`：仅按时间权重进行局部聚类
- `localamount`：仅按数据量权重进行局部聚类
- `localstruct`：仅按结构权重进行局部聚类
- `fanout`：仅按扇出值进行局部聚类

示例：

```bash
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar ./test ./test/result attack.txt
```
