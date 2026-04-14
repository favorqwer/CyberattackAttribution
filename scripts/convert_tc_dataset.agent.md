# Agent 使用说明：`convert_tc_dataset.ps1`

本文档面向在本仓库中工作的 agent，说明如何安全、稳定地使用 [convert_tc_dataset.ps1](/d:/depimpact_source/scripts/convert_tc_dataset.ps1) 将 Transparent Computing Engagement 5 数据集转换为 `json` 或 `json.gz`。

## 1. 适用场景

- 需要将 `Transparent_Computing_Engagement_5_Dataset/Data/<provider>` 下的 `*.bin.gz` 或 `*.bin.N.gz` 转换为可读 JSON。

## 2. 前置条件

运行前应确认以下路径存在：

- 数据集根目录：`Transparent_Computing_Engagement_5_Dataset`
- Schema：`Transparent_Computing_Engagement_5_Dataset/Schema/TCCDMDatum.avsc`
- Java consumer jar：
  `Transparent_Computing_Engagement_5_Dataset/Tools/ta3-java-consumer/tc-bbn-kafka/target/kafkaclients-1.0-SNAPSHOT-jar-with-dependencies.jar`

系统依赖：

- `java`
- `7z`，或 `C:\Program Files\7-Zip\7z.exe`

## 3. 推荐默认策略

agent 默认应遵循以下策略：

- 优先使用 `-OutputFormat json`，便于后续分析与调试。
- 若目录中已有正式转换结果，优先使用单独的 `-OutputRoot` 做测试，避免覆盖。
- 并行转换优先从`-Parallelism 4` 开始，不要一开始开太高。
- 只有在用户明确要求覆盖已有结果时，才使用 `-Force`。

## 4. 常用命令模板

### 4.1 转换 `theia` 为纯 JSON

```powershell
& .\scripts\convert_tc_dataset.ps1 `
  -DatasetRoot .\Transparent_Computing_Engagement_5_Dataset `
  -Providers theia `
  -OutputFormat json `
  -Parallelism 4
```

默认输出目录为：

```text
Transparent_Computing_Engagement_5_Dataset\Readable_Json\theia
```

### 4.2 进行并行小样本测试，不覆盖现有结果

```powershell
& .\scripts\convert_tc_dataset.ps1 `
  -DatasetRoot .\Transparent_Computing_Engagement_5_Dataset `
  -Providers theia `
  -OutputFormat json `
  -OutputRoot .\Transparent_Computing_Engagement_5_Dataset\Readable_Json_parallel_smoke `
  -Parallelism 4 `
  -MaxFiles 4
```

### 4.3 转换全部 provider

```powershell
& .\scripts\convert_tc_dataset.ps1 `
  -DatasetRoot .\Transparent_Computing_Engagement_5_Dataset `
  -OutputFormat json `
  -Parallelism 4
```

### 4.4 输出为 `json.gz`

```powershell
& .\scripts\convert_tc_dataset.ps1 `
  -DatasetRoot .\Transparent_Computing_Engagement_5_Dataset `
  -Providers theia `
  -OutputFormat json.gz `
  -Parallelism 4
```

注意：`json.gz` 模式默认是“就地输出”，即输出到源文件所在 provider 目录的对应位置。若不希望就地写入，应显式传入 `-OutputRoot`。

## 5. 关键参数说明

- `-DatasetRoot`
  数据集根目录。若省略，脚本默认使用仓库中的 `Transparent_Computing_Engagement_5_Dataset`。

- `-Providers`
  指定 provider，例如 `theia`、`cadets`。可传多个值；若省略则处理全部 provider。

- `-OutputFormat`
  取值为 `json` 或 `json.gz`。推荐优先使用 `json`。

- `-OutputRoot`
  自定义输出根目录。做小样本测试时强烈建议设置。

- `-Parallelism`
  文件级并行度。每个 worker 处理一个输入文件。推荐起步值：
  - SSD 机器：`2` 到 `4`
  - 磁盘压力较大时：先用 `2`

- `-MaxFiles`
  最多处理多少个输入文件。用于烟雾测试非常有用。
  说明：它限制的是“文件数”，不是单个文件的记录数，不会截断单个输出文件。

- `-Force`
  若目标文件已存在则覆盖。非必要不要使用。

- `-KeepTemp`
  保留中间临时目录 `_tc_convert_tmp`，便于排错。

## 6. 输出命名规则

- `xxx.bin.gz` -> `xxx.json`
- `xxx.bin.1.gz` -> `xxx.json.1`
- 在 `json.gz` 模式下，输出名分别为 `xxx.json.gz`、`xxx.json.1.gz`

## 7. 转换后建议检查

建议至少执行以下检查：

### 7.1 检查输入文件数

```powershell
Get-ChildItem -LiteralPath .\Transparent_Computing_Engagement_5_Dataset\Data\theia -Recurse -File |
  Where-Object { $_.Name -match '\.bin(\.\d+)?\.gz$' } |
  Measure-Object
```

### 7.2 检查输出文件数

```powershell
Get-ChildItem -LiteralPath .\Transparent_Computing_Engagement_5_Dataset\Readable_Json\theia -Recurse -File |
  Where-Object { $_.Name -match '\.json(\.\d+)?$' } |
  Measure-Object
```

### 7.3 抽样查看头尾

```powershell
Get-Content .\Transparent_Computing_Engagement_5_Dataset\Readable_Json\theia\<sample>.json -TotalCount 2
Get-Content .\Transparent_Computing_Engagement_5_Dataset\Readable_Json\theia\<sample>.json -Tail 2
```

## 8. 常见注意事项

- 单个 JSON 文件可能非常大，运行前应确认磁盘空间充足。
- 提高 `-Parallelism` 不一定线性提速；若磁盘成为瓶颈，过高并发反而会变慢。
- 脚本内部的 `-WorkerMode` 参数是并行 worker 使用的内部参数，agent 不应手动传入。
- 若只想验证并行链路，不要直接写入 `Readable_Json\theia`，应使用独立的 `-OutputRoot`。

