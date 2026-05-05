# DepImpact 当前项目如何把 `Transparent_Computing_Engagement_5_Dataset\Data\theia` 日志构建为溯源图并完成逐步溯源

本文面向当前仓库的实际实现，详细说明项目如何从 `Transparent_Computing_Engagement_5_Dataset\Data\theia` 中的 Theia/CDM 原始日志出发，构建统一的依赖图，再经过后向切片、因果保持压缩、边权重计算、后向传播、入口筛选、前向验证和 LLM 过滤，最终生成核心溯源图。

这份说明不是只讲论文思路，而是严格对应当前代码路径、配置方式、数据结构和输出结果。

## 1. 先说结论：Theia 数据在当前项目里走的是 “CDM 直构图” 路径

当前项目实际上支持两条输入链路：

1. 传统链路：`sysdig .txt -> SysdigOutputParserNoRegex -> GetGraph -> 后续溯源`
2. 新增链路：`Theia/CDM .cdm manifest -> CdmGraphBuilder -> 后续溯源`

你关心的 `Transparent_Computing_Engagement_5_Dataset\Data\theia` 属于第二条链路。

也就是说：

- `Data\theia` 下的 `*.bin.gz` 或 `*.bin.N.gz` 并不会先被转换成当前主流程必须的 sysdig 文本再处理。
- 当前主流程已经能直接读取 Theia 的 CDM Avro 压缩文件。
- Theia 数据要先通过一个 `.cdm` 清单文件告诉程序“去哪个目录、取哪些文件、截取哪个时间窗、POI 是谁”。
- 一旦 `CdmGraphBuilder` 把 Theia 数据构造成 `DirectedPseudograph<EntityNode, EventEdge>`，后面的溯源主链就和 sysdig 路径统一了。

这点很关键，因为项目的“后半段”基本是统一的，而“前半段”的日志解析/构图方式对 Theia 和 sysdig 是不同的。

## 2. 一次完整运行从哪里开始

当前命令行入口在：

- `src/main/java/pagerank/main/ExperimentRunnerCmd.java`

典型运行方式：

```powershell
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar ./test ./test/result theia-e5-official-2.cdm
```

这三个参数的含义分别是：

1. 日志和案例配置文件所在目录
2. 结果输出目录
3. 要运行的日志名；这里既可以是 sysdig 的 `.txt`，也可以是 Theia 的 `.cdm`

对 Theia 而言，第三个参数不是原始 `bin.gz`，而是 `.cdm` manifest。比如仓库里的：

- `test/theia-e5-official-2.cdm`
- `test/theia-e5-official-2.property`

程序会做两件事：

1. 识别第三个参数是否是 `.cdm`
2. 查找与其同名的 `.property` 实验配置文件

当前匹配规则是：

- `xxx.cdm` 会匹配 `xxx.property`
- 或匹配以 `xxx:` 开头的 `.property`

这一步在 `ExperimentRunnerCmd.isMatchingPropertyFile` 中实现。

## 3. `.cdm` 和 `.property` 各自负责什么

### 3.1 `.cdm` manifest 负责告诉程序如何读取 Theia 原始数据

例如 `test/theia-e5-official-2.cdm`：

```properties
cdm.input_dir=Transparent_Computing_Engagement_5_Dataset/Data/theia
cdm.file_glob=ta1-theia-1-e5-official-2.bin*.gz
cdm.start_nanos=1557241091148522395
cdm.end_nanos=1557244691148522395
cdm.max_events=0
```

这些字段的语义是：

- `cdm.input_dir`：Theia 原始 Avro/GZip 文件目录
- `cdm.file_glob`：要读取的文件集合
- `cdm.start_nanos` / `cdm.end_nanos`：时间窗裁剪范围
- `cdm.max_events`：最多处理多少个事件，`0` 表示不限

### 3.2 `.property` 负责告诉程序要怎么做溯源实验

例如 `test/theia-e5-official-2.property`：

```properties
POI=/home/admin/.vnc/ta1-theia-target-1:1.log
threshold=0
trackOrigin=false
detectionSize=0
highRP=
midRP=
lowRP=
criticalEdge=
criticalNodes=
importantFileStarts=
importantProcessStarts=
importantIPStarts=
entry=
```

这里最核心的是 `POI`。它必须和构图后某个节点的 `signature` 完全一致，否则后向切片起点找不到。

当前 `.property` 由 `src/main/java/pagerank/main/Experiment.java` 解析，主要做了这些事情：

- 读取 `POI`
- 读取 `highRP`、`lowRP`、`midRP`
- 自动把 `POI` 加入 `highRP`
- 读取 `detectionSize`
- 读取 `entry`
- 读取若干评估辅助项

注意一个实现细节：

- `importantFileStarts`、`importantProcessStarts`、`importantIPStarts` 当前会被读进来，但主流程里基本没真正参与后续决策
- `entry` 当前主要用于日志高亮和记录，不会直接替换“按类别取 top-3 入口”的核心逻辑

## 4. 入口调度层：`ExperimentRunnerCmd` 实际做了什么

### 4.1 先加载全局配置

全局配置文件是根目录下的：

- `depimpact.properties`

由 `src/main/java/pagerank/config/GlobalConfig.java` 加载。

它控制：

- `weight_mode`
- `cpr_mode`
- `cpr_time_window`
- `fd_window_size`
- `sd_source_set_limit`
- LLM 相关开关和接口参数
- 本地 IP 列表
- syscall 白名单
- 默认背景实体 `default_mid_rp`

### 4.2 再按日志逐个生成图

`ExperimentRunnerCmd.run2()` 会：

1. 解析 `weight_mode`、`cpr_mode`、`cpr_time_window`
2. 找到本次要处理的日志文件
3. 对每个日志只构图一次
4. 对该日志关联的每个 `.property` 场景复用同一张原始图

这意味着：

- 同一个 Theia 原始图可以复用给多个 POI/多个场景
- 解析和构图成本不会在多场景下重复支付

### 4.3 区分 Theia 和 sysdig 的关键分支

真正分支点在 `loadGraph()`：

- 如果输入文件后缀是 `.cdm`，调用 `CdmGraphBuilder.build(...)`
- 否则走 `GetGraph.GenerateGraph()`

因此，对 `Data\theia` 的处理从这里正式进入 Theia 专用构图链路。

## 5. Theia 原始数据是如何被读取的

### 5.1 manifest 解析：`CdmGraphManifest`

代码位置：

- `src/main/java/logparsers/cdm/CdmGraphManifest.java`

它负责：

1. 读取 `.cdm` 清单文件
2. 校验必须字段是否齐全
3. 把相对路径解析为绝对路径
4. 在 `input_dir` 中按照 `file_glob` 找到所有匹配文件
5. 按自然顺序排序文件名

这里的“自然顺序”很重要。

例如：

- `bin.2.gz` 会排在 `bin.10.gz` 前面

这保证了分卷文件会按正确顺序输入，而不是按纯字典序错位。

### 5.2 Avro + GZip 读取：`CdmAvroGzipReader`

代码位置：

- `src/main/java/logparsers/cdm/CdmAvroGzipReader.java`

它做了几件关键工作：

1. 按 1MB buffer 打开输入流
2. 如果文件名以 `.gz` 结尾，则套上 `GZIPInputStream`
3. 用 `DataFileStream<GenericRecord>` 流式读取 Avro 记录
4. 校验 Avro schema 全名必须是  
   `com.bbn.tc.schema.avro.cdm20.TCCDMDatum`

这意味着当前实现并不是把整份 Theia 文件一次性读入内存，而是流式迭代记录。

## 6. Theia 构图核心：`CdmGraphBuilder`

代码位置：

- `src/main/java/logparsers/cdm/CdmGraphBuilder.java`

### 6.1 默认是“两遍构图”

`CdmGraphBuilder.build()` 默认采用两遍构图：

1. `firstPass(files)`
2. `secondPass(files)`

另有 `buildSinglePass()`，但主入口默认不用它。

两遍构图的原因是：

- CDM 的事件记录经常引用前面或别处定义的 `subject` / `file object` / `netflow object`
- 先做一遍实体缓存，第二遍映射事件时命中率更高，缺失回填更少

### 6.2 第一遍：只缓存实体，不处理事件边

第一遍会扫描所有记录，根据 `recordType` 调用 `cacheEntity(...)`。

当前重点缓存这些对象：

- `RECORD_SUBJECT`
- `RECORD_FILE_OBJECT`
- `RECORD_NET_FLOW_OBJECT`
- `RECORD_IPC_OBJECT`
- `RECORD_PACKET_SOCKET_OBJECT`
- `RECORD_REGISTRY_KEY_OBJECT`
- `RECORD_MEMORY_OBJECT`
- `RECORD_SRC_SINK_OBJECT`

#### 6.2.1 进程节点怎么构造

`cacheSubject(...)` 会从 subject 中取：

- `uuid`
- `cid`
- `parentSubject`
- `path`
- `cmdLine`

然后生成：

- `name`：优先取可执行路径 basename，再退化到命令行首 token，再退化到 `subject-uuid8`
- `pid`：`cid + "@" + uuid8(uuid)`

最终进程签名来自 `Process.getPidAndName()`，也就是：

```text
cid@uuid8name
```

这正是 README 里提到的 Theia 进程签名格式。

#### 6.2.2 文件节点怎么构造

`cacheFileObject(...)` 会优先取：

1. `baseObject.filename`
2. `baseObject.path`
3. 事件携带的 `predicateObjectPath`

如果都拿不到，就回退成：

```text
file://<uuid>
```

所以你在结果里看到的文件节点既可能是正常路径，也可能是 `file://...` 的占位签名。

#### 6.2.3 网络节点怎么构造

`cacheNetFlowObject(...)` 会读取：

- `localAddress`
- `remoteAddress`
- `localPort`
- `remotePort`

最终签名格式是：

```text
local:port->remote:port
```

这和 `.property` / README 中要求的网络 POI/入口格式一致。

#### 6.2.4 IPC / socket / registry / memory 怎么处理

当前实现会把这些对象尽量映射成统一的可追踪节点：

- Unix socket 类对象会被转成一个“网络样式”节点，形如 `unix:0->path:0`
- registry、memory、srcsink、ipc 等无法纳入三大类语义时，通常转成带前缀的文件样式签名，例如：
  - `registry://...`
  - `mem://...`
  - `ipc://...`
  - `srcsink://...`

这说明当前项目虽然统一只保留了三种 `EntityNode` 承载形式，但在签名层面已经把一部分 CDM 特有对象折叠进统一图模型了。

### 6.3 第二遍：只处理 `RECORD_EVENT`

第二遍只关心 `RECORD_EVENT`。

每条事件会经过以下过滤：

1. 读取 `timestampNanos`
2. 检查是否落在 `[start_nanos, end_nanos]`
3. 如果 `max_events > 0`，则检查是否已超限
这里的 `eventsInWindow` 统计的是“进入时间窗并真正参与映射的事件数”。

### 6.4 事件类型到信息流方向的映射：`CdmEventMapper`

代码位置：

- `src/main/java/logparsers/cdm/CdmEventMapper.java`

它并不直接把 CDM 事件原样塞进图里，而是先做“语义归类”。

当前分成五类：

1. `OBJECT_TO_PROCESS`
2. `PROCESS_TO_OBJECT`
3. `PROCESS_TO_PROCESS`
4. `OBJECT_TO_OBJECT`
5. `SKIP`

几个典型映射：

- `READ`、`RECVFROM`、`RECVMSG`、`ACCEPT`、`LOADLIBRARY` -> `OBJECT_TO_PROCESS`
- `WRITE`、`SENDTO`、`SENDMSG`、`CONNECT`、`BIND`、`UNLINK` -> `PROCESS_TO_OBJECT`
- `FORK`、`CLONE`、`CREATE_THREAD`、`SIGNAL`、`MODIFY_PROCESS` -> `PROCESS_TO_PROCESS`
- `RENAME`、`LINK`、`FLOWS_TO`、`TEE`、`SPLICE`、`SHM`、`UPDATE` -> `OBJECT_TO_OBJECT`
- `BOOT`、`EXIT`、`WAIT`、`CLOSE`、`OPEN`、`LSEEK`、`DUP`、`FCNTL`、`OTHER` -> `SKIP`

特别注意：

- `EXECUTE` 被强制视为 `OBJECT_TO_PROCESS`

这背后的语义是：

- 执行一个文件，本质上是“文件内容影响进程”

### 6.5 真正加边时如何决定方向

`mapEvent(...)` 中的方向规则如下：

- `OBJECT_TO_PROCESS`：`predicate -> subject`
- `PROCESS_TO_OBJECT`：`subject -> predicate`
- `PROCESS_TO_PROCESS`：`subject -> predicate`
- `OBJECT_TO_OBJECT`：`predicate -> predicate2`

也就是说，当前图的边方向代表的是“依赖/影响流向”，可以理解为：

```text
原因实体 -> 结果实体
```

例如：

- 文件被读取：文件 -> 进程
- 进程写文件：进程 -> 文件
- 网络数据接收：网络 -> 进程
- 网络发送：进程 -> 网络

### 6.6 `EXECUTE` 还会额外补一条父子进程边

当事件是 `EXECUTE` 时，除了加“可执行文件 -> 新进程”这条边，还会调用 `addParentEdge(...)`：

- 如果当前 subject 有 `parentSubject`
- 则补一条 `parent process -> child process`
- 事件名会被写成 `EXECUTE_PARENT`

这一步很重要，因为它把“文件执行语义”和“进程谱系语义”同时保留下来了。

### 6.7 边上存了什么

每条加入图中的边是一个 `EventEdge`，包含：

- `source`
- `sink`
- `id`
- `startTime`
- `endTime`
- `type`
- `event`
- `size`
- `weight`
- `timeWeight`
- `amountWeight`
- `structureWeight`

对 Theia/CDM 路径来说：

- `type` 由 `CdmEventMapper.edgeType(...)` 生成，例如 `PtoF`、`FtoP`、`PtoN`、`NtoP`、`PtoP`
- `event` 保存原始 CDM 事件名的大写归一化版本
- `startTime` 和 `endTime` 目前都用同一个事件时间戳
- `size` 取事件里的 `size`

## 7. 构图完成后，Theia 和 sysdig 进入统一图模型

一旦 `CdmGraphBuilder.build()` 返回，主流程拿到的是：

```java
DirectedPseudograph<EntityNode, EventEdge>
```

后续所有算法都只看这张统一图，不再关心它最初是来自：

- sysdig 文本
- 还是 Theia/CDM Avro

### 7.1 节点统一成 `EntityNode`

`EntityNode` 只承载三大类实体：

1. `Process`
2. `FileEntity`
3. `NetworkEntity`

节点有三个关键属性：

- `ID`
- `signature`
- `reputation`

其中 `signature` 是后续所有查找、POI 匹配、入口输出、图展示的核心键。

### 7.2 初始声誉值默认是 0.5

Theia 构图时 `CdmGraphBuilder` 给新节点的默认 reputation 是：

```text
0.5
```

之后才会在传播前根据 `highRP` / `lowRP` 重置。

### 7.3 图导出时的视觉约定

`IterateGraph` 在导出 DOT/SVG 时使用：

- 进程：方框
- 文件：椭圆
- 网络：平行四边形

并且：

- POI 节点会用红色填充
- 入口节点会用黄色填充
- 边标签显示的是 `event`
- 节点标签显示的是 `signature [reputation]`

## 8. 如果不是 Theia，而是 sysdig 文本，前半段会怎么走

为了完整理解当前项目，必须知道 sysdig 路径和 Theia 路径在统一之前差在哪。

### 8.1 sysdig 文本格式

项目默认吃的是用如下模板导出的 sysdig 文本：

```bash
sysdig -r capture.scap -p "%evt.rawtime.s.%evt.rawtime.ns;;%evt.cpu;;%proc.name;;%proc.pid;;%evt.dir;;%evt.type;;%proc.cwd;;%evt.latency;;%evt.args"
```

也就是每行前 9 个字段用 `;;` 分隔：

1. timestamp
2. cpu
3. process
4. pid
5. direction
6. event
7. cwd
8. latency
9. args

### 8.2 `SysdigOutputParserNoRegex` 如何把一行文本变成事件

代码位置：

- `src/main/java/logparsers/SysdigOutputParserNoRegex.java`

它的核心思路是：

1. 读每一行
2. 把前 9 个字段拆出来
3. 如果是 syscall 进入事件，放进 `incompleteEvents`
4. 如果是 syscall 返回事件，用 `end.timestamp - latency` 反推出 start timestamp
5. 用 `(startTimestamp, event, cwd)` 找到对应的 enter 记录
6. 再按 syscall 类型做语义派发

如果没找到 enter 事件，会生成一个 dummy start 兜底，因此当前实现对日志不完整有一定容错。

### 8.3 sysdig 实体提取规则

`extractEntities(...)` 会把一条日志对应到：

- `res[0]`：永远是进程实体
- `res[1]`：可能是文件、网络，也可能为空

提取逻辑主要依赖 `Utils`：

- `extractFileandSocket(args)`：从 `fd=...` 里解析文件或 socket
- `extractProcessFile(args)`：从 `filename=...` 里解析可执行文件
- `extractSize(args)`：从 `res=...` 里取返回值作为 size

### 8.4 sysdig 事件语义靠 `Fingerprint -> SystemCall` 派发

sysdig 路径的一个核心设计是：

- 不直接按 syscall 名做处理
- 而是构造 `Fingerprint(event, startEntityClass, endEntityClass)`

例如：

- `read + FileEntity`
- `sendmsg + NetworkEntity`
- `execve + FileEntity`

再映射到对应 `SystemCall` 处理器。

这个处理器会把原始 syscall 产生成 5 类事件表：

- `PtoFEvent`
- `FtoPEvent`
- `PtoPEvent`
- `PtoNEvent`
- `NtoPEvent`

最后 `GetGraph.GenerateGraph()` 再把它们统一转换成 `EventEdge` 加进 JGraphT 图中。

### 8.5 sysdig 路径的一个当前实现细节

`ProcessTheOriginalParserOutput` 里有一个 `reverseSourceAndSink()`，看起来想根据信息流纠正网络边方向，但当前 `GetGraph.GenerateGraph()` 并没有调用它。

所以现阶段 sysdig 路径里的网络边方向主要依赖：

- 全局配置中的 syscall 分类
- `accept` / `fcntl` 等专门 handler

这和 Theia 路径“先做 CDM 语义映射再定方向”是不同的。

## 9. 真正的溯源主链：`ProcessOneLogCMD_19.run_exp_backward`

代码位置：

- `src/main/java/pagerank/main/ProcessOneLogCMD_19.java`

Theia 图和 sysdig 图在这里正式汇合。

主流程按顺序做这些事：

1. 自动补 `detectionSize`
2. 后向切片 `BackTrack`
3. 因果保持压缩 `CausalityPreserve`
4. 边权重计算 `BackwardPropagate_pf`
5. 后向传播 `PageRankIterationBackward`
6. 候选入口提取与排序
7. 前向验证与前后向融合
8. 完整核心图导出
9. LLM 过滤和连通性修补

下面逐段拆开。

## 10. 第一步：自动补 `detectionSize`

如果 `.property` 里没有显式给出 `detectionSize`，程序会先从原始图中自动估计。

位置：

- `GetGraph.extractDetectionSizeFromGraph(...)`

逻辑是：

1. 找到 POI 节点
2. 优先看它的入边
3. 取其中最大的 `size`
4. 如果没有入边，再看出边

这个值后面用于计算 amount weight。

对 Theia 来说，这个大小来自 CDM event 的 `size` 字段；对 sysdig 来说通常来自 `res=` 返回值。

## 11. 第二步：后向切片 `BackTrack`

代码位置：

- `src/main/java/pagerank/algorithm/BackTrack.java`

核心方法：

- `backTrackPOIEvent(String str)`

### 11.1 后向切片的目标

它不是一上来就在整张图上跑排序，而是先缩图：

- 从 POI 出发，只保留所有“可能影响到 POI”的祖先节点和边

这一步往往能把图规模大幅压缩。

### 11.2 算法细节

当前实现是一个带时间阈值的 BFS：

1. 找到 POI 节点
2. 取 POI 的“最后一次操作时间”作为起始时间阈值
3. 从 POI 开始，沿入边反向遍历
4. 只有当 `edge.startTime <= 当前阈值` 时，这条边才保留
5. 对源节点设置新的时间阈值：

```text
min(当前边 endTime, 当前节点阈值)
```

这表示：

- 一个祖先节点只有在时间上早于其后继导致 POI 的那次操作时，才被认为仍然可能构成因果前因

### 11.3 结果

切片结果保存在：

- `backTrack.afterBackTrack`

`summary.json` 里会记录：

- `BackTrackVertexNumber`
- `BackTrackEdgeNumber`
- `BackTrackTimeCost`

## 12. 第三步：因果保持压缩 `CausalityPreserve`

代码位置：

- `src/main/java/pagerank/algorithm/CausalityPreserve.java`

输入是后向切片图，输出是压缩后的图 `afterMerge`。

### 12.1 当前支持的模式

- `standard_cpr`
- `causal_strict`
- `full_merge`
- `endpoint_aggregation`
- `windowed_sequence`
- `no_merge`
- `fd`
- `sd`

### 12.2 `windowed_sequence`

这是比较直观的窗口合并：

- 同一 `event`
- 同一 `source`
- 同一 `target`
- 相邻两条边的时间间隔不超过 `cpr_time_window`

则做 merge。

merge 后：

- 保留最早 `startTime`
- `endTime` 更新到最新
- `size` 累加

### 12.3 `causal_strict`

这个模式比单纯时间窗更保守。

它不仅要求边类型一致，还会结合：

- backward check
- forward check

确保合并不会破坏局部因果结构。

### 12.4 `endpoint_aggregation` / `full_merge`

这是更激进的合并：

- 只按端点 `(source, target)` 聚合
- 忽略细粒度时间窗
- 合并后 `event` 会被置为 `NullAfterMerge`

因此压缩率高，但语义损失也更大。

### 12.5 `fd` 和 `sd`

这是当前项目对论文风格 reduction 的实现。

`fd` 对应 dependence-preserving reduction，关键思路包括：

- 按时间顺序处理事件
- 维护版本状态 `VersionState`
- 做 REO / RNO / 2-node CCO 风格的可约化判断

`sd` 在 `fd` 基础上进一步加入源集合约束：

- 用 `SourceSetState` 维护祖先来源集合
- 若 `sink` 的来源集合已经覆盖 `source` 的来源集合，则可丢弃当前边

此外还受全局配置影响：

- `fd_window_size`
- `sd_source_set_limit`

### 12.6 `no_merge`

直接返回后向切片图，不做压缩。

### 12.7 结果

`summary.json` 会记录：

- `CPRVertexNumber`
- `CPREdgeNumber`
- `CPRTimeCost`
- `CPRMode`
- `CPRTimeWindow`

## 13. 第四步：边权重计算 `BackwardPropagate_pf`

代码位置：

- `src/main/java/pagerank/algorithm/BackwardPropagate_pf.java`

这是当前系统把“边是否更像攻击链关键依赖”量化出来的地方。

### 13.1 三个基础特征权重

每条边先计算三个基础权重：

1. `timeWeight`
2. `amountWeight`
3. `structureWeight`

#### 13.1.1 `timeWeight`

公式本质是：

```text
log(1 + 1 / |edge.endTime - POITime|)
```

越接近 POI 时间的边，时间权重越高。

其中 `POITime` 当前取的是图中所有边 `endTime` 的最大值，也就是“切片图里的最晚事件时间”。

#### 13.1.2 `amountWeight`

公式本质是：

```text
1 / (|edge.size - detectionSize| + 0.0001)
```

也就是说：

- 边的数据量越接近检测点观测到的数据量，权重越高

#### 13.1.3 `structureWeight`

当前核心逻辑是看 `sink` 的结构特征：

```text
outDegree(sink) / inDegree(sink)
```

并且如果 `sink` 属于 seed source，会给特殊强化。

这个特征试图表达：

- 某些结构位置更像“关键扩散点”或“因果桥”

### 13.2 `weight_mode=nonml`

这是手工融合版本。

如果数据量不可用，就用：

- `0.5 * time + 0.5 * structure`

如果数据量可用，就用近似均分：

- `0.334 * time + 0.333 * structure + 0.333 * amount`

然后按每个源节点的出边做归一化。

### 13.3 `weight_mode=clusterall`

这是当前全局配置默认值，也是 Theia 示例运行时实际走的路径。

它的步骤更复杂：

1. 先计算三类基础权重
2. 对边特征做标准化
3. 用 `MultiKMeansPlusPlusClusterer` 把边聚成两类
4. 用 FDA/LDA 风格的投影向量做三维到一维降维
5. 根据种子边分布方向调整投影向量符号
6. 把投影结果线性缩放到 `(0, 1+]`
7. 再写回为 `edge.weight`

这一步的目标不是直接做分类，而是让三维特征在“更像攻击路径”和“更像背景边”之间自动学出一个融合方向。

### 13.4 其他 `weight_mode`

当前还有：

- `clusterlocal`
- `clusterlocal_dec`
- `nonoutlier`
- `localtime`
- `localamount`
- `localstruct`
- `fanout`
- `nonmlrandom`

它们要么是只保留某一维特征，要么是局部聚类，要么是基线随机权重。

## 14. 第五步：初始化节点声誉并做后向传播

### 14.1 初始化 `reputation`

`initialReputation(...)` 的规则是：

- `highRP` 中的节点 -> `1.0`
- `lowRP` 中的节点 -> `0.0`
- 其余如果入度为 0 -> 也先设为 `0.0`

因为 `Experiment.digestConfig()` 会自动把 `POI` 加入 `highRP`，所以 POI 在传播前会被设为 `1.0`。

### 14.2 传播公式

真正的传播在：

- `PageRankIterationBackward(...)`

对每个非 seed 节点 `v`，用它的所有出边更新：

```text
rep(v) = Σ rep(sink) * edge.weight
```

这里之所以看“出边”，是因为当前图方向是：

```text
原因 -> 结果
```

所以要把 POI 的高可疑性沿因果链反向传给祖先节点，就必须让祖先节点从自己的下游后继那里吸收声誉。

传播会一直迭代到：

- 总波动小于 `1e-5`
- 或达到 3000 轮

这一步结束后，每个节点都有一个新的 `reputation`，表示它作为攻击前因的可疑程度。

## 15. 第六步：候选入口提取与排序

### 15.1 原始候选入口怎么定义

入口候选提取在：

- `IterateGraph.getCandidateEntryPoint(...)`

规则如下：

1. 网络节点：全部视为候选入口
2. 进程节点：如果它没有来自网络节点或进程节点的入边，则视为候选入口
3. 文件节点：如果不在 `default_mid_rp` 背景库里，且入度为 0，则视为候选入口
4. 如果传入了 `detectionSignature`，则 POI 本身会被排除

这个规则本质上是在找：

- 外部进入点
- 执行链起点
- 没有明显上游的文件源

### 15.2 按类别排序

`BackwardPropagate_pf.getForwardStarts(...)` 会把这些候选入口拆成三类：

1. process
2. network
3. file

然后分别按 `reputation` 降序排序。

### 15.3 当前真正拿去生成核心图的数量

`ProcessOneLogCMD_19.filter_graph_by_forward_category(...)` 当前会对每一类只取前 3 个。

也就是说，默认完整核心图最多会把：

- 3 个进程入口
- 3 个网络入口
- 3 个文件入口

合在一起生成一张总图。

这一点非常重要，因为：

- `EntryPointsNumber` 可能很大
- 但最终生成 `complete_provenance_graph` 的并不是所有入口，而是按类别截断后的 top-3

### 15.4 `entry=` 当前实现中的实际作用

`entry=` 配置项目前主要用于：

- 出现在 `entry_points.json` 的 `HighlightedEntryPoints`

但不会直接替换“按类别 top-3 自动选择”的核心图生成逻辑。

所以如果你在实验里手工填了 `entry=`，当前实现并不会自动让这些入口全部进入最终图，这一点需要特别注意。

## 16. 第七步：前向验证与前后向融合

### 16.1 前向验证为什么要做

后向切片只能说明：

- 某个节点可能影响 POI

但它不能保证：

- 把该节点当作“攻击入口”之后，能在当前因果图里正向走到 POI

所以还要再做一次前向验证。

### 16.2 `ForwardAnalysis.forwardLimitedByTime(...)`

它会从一个候选入口出发做前向遍历，但有两个约束：

1. 只能沿出边走
2. 只能保留 `out.startTime < POITime` 的边

并且对于继续扩展的边，还要求：

- 该边开始时间不能早于当前节点在结果图中已有入边的最早开始时间

这是一种时间一致性约束，避免在前向验证时引入明显不可能的时间逆序路径。

### 16.3 多入口融合：`combineBackwardAndForwardForMultipleStarts(...)`

当前实现已经修正过一个关键问题：

- 前向分析不再在原始全图 `original` 上做
- 而是在当前反向切片/压缩后的 `this.graph` 上做

这样做的好处是：

- 前向子图和后向子图来自同一套边集合
- 做交集时不会因为“边签名不一致”而产生死端

处理步骤是：

1. 对每个已选入口在 `this.graph` 上做前向验证
2. 取所有前向图的边并集
3. 用这个并集去过滤当前后向图
4. 得到既能从入口正向到达、又能后向连到 POI 的核心子图

### 16.4 连通性兜底

如果过滤后仍然存在孤立分量，`ensureConnectivity(...)` 会尝试：

- 以包含最高声誉节点的主分量为主
- 从当前图中补入必要路径
- 尽量把剩余分量桥接到主分量

这一步是为了保证结果图在可解释性上不是碎片化的。

## 17. 第八步：完整核心图导出

当多入口融合完成后，程序会导出一张包含所有已选入口的完整溯源图：

- `graphs/complete_provenance_graph.svg`

同时还会记录：

- `CompleteProvenanceVertexNumber`
- `CompleteProvenanceEdgeNumber`

这张图就是当前系统在 LLM 介入前的“确定性核心图”。

## 18. 第九步：LLM 过滤

代码位置：

- `src/main/java/pagerank/algorithm/LLMGraphFilter.java`

### 18.1 何时执行

只在全局配置里 `llm_enabled=true` 且接口参数完整时执行。

### 18.2 过滤前先保存快照

会把核心图写成：

- `snapshot_<case>_graphs.json`

由 `LLMFilterSnapshotIO.writeSnapshot(...)` 完成。

快照里保存了：

- 节点
- 边
- 权重
- 入口列表
- POI

这使得 LLM 过滤可以单独重放，不用重新跑整套图构建。

### 18.3 发送给 LLM 的内容

LLM 输入不是 DOT，而是结构化 JSON，包括：

- `nodes`
- `edges`

每条边至少带：

- `edge_id`
- `source`
- `target`
- `event_type`
- `timestamp`

同时 prompt 中还会加入：

- 入口候选
- POI
- 图统计摘要

### 18.4 期望 LLM 返回什么

当前解析器主要从返回文本中抽取两项：

- `EDGES: [id1, id2, ...]`
- `ENTRY_NODES: [...]`

### 18.5 程序端兜底校验

LLM 结果不会被直接信任，而是继续经过这些检查：

1. 返回的边 ID 是否真的存在于原图
2. 过滤后的图是否还能找到 POI
3. 候选入口是否真的能连到 POI
4. 是否还存在孤立弱连通分量

如果某个入口在原图里根本没有到 POI 的有向路径，它会被丢弃。

如果图断开，程序还会自动补桥。

如果 LLM 调用失败或返回为空，则直接回退到未过滤的核心图。

### 18.6 结果输出

会额外生成：

- `graphs/llm_raw_graph.svg`
- `graphs/llm_filtered_graph.svg`
- `llm_interaction_<case>_graphs.log`

`summary.json` 里还会补充：

- `LLMFilteredVertexNumber`
- `LLMFilteredEdgeNumber`
- `LLMVertexCompressionRatio`
- `LLMEdgeCompressionRatio`

## 19. 结果目录里通常会看到哪些文件

以一个案例目录为例，常见输出包括：

- `<case>.log`
- `summary.json`
- `entry_points.json`
- `snapshot_<case>_graphs.json`
- `llm_interaction_<case>_graphs.log`
- `graphs/complete_provenance_graph.svg`
- `graphs/llm_raw_graph.svg`
- `graphs/llm_filtered_graph.svg`

这些文件分别回答：

- 控制台过程是什么
- 各阶段规模和耗时是多少
- 候选入口怎么排的
- LLM 输入图长什么样
- LLM 最后留下了什么

## 20. 结合 `Transparent_Computing_Engagement_5_Dataset\Data\theia` 走一遍真实执行链

如果你运行：

```powershell
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar ./test ./test/result theia-e5-official-2.cdm
```

当前实现的实际链路是：

1. `ExperimentRunnerCmd` 识别这是 `.cdm`
2. 读取 `test/theia-e5-official-2.cdm`
3. 从 `Transparent_Computing_Engagement_5_Dataset/Data/theia` 里找  
   `ta1-theia-1-e5-official-2.bin*.gz`
4. 用 `CdmAvroGzipReader` 流式打开这些分卷 Avro/GZip 文件
5. 第一遍缓存 subject / file / netflow / ipc 等对象
6. 第二遍仅处理时间窗内的 `RECORD_EVENT`
7. 把事件统一映射成 `EntityNode + EventEdge` 图
8. 根据 `test/theia-e5-official-2.property` 里的  
   `POI=/home/admin/.vnc/ta1-theia-target-1:1.log`
   启动后向切片
9. 对切片图做当前全局配置指定的 CPR 压缩
10. 按当前 `weight_mode` 计算边权重
11. 从 POI 出发把可疑度向祖先节点反向传播
12. 选出 process/network/file 三类入口候选并排序
13. 每类取前 3 个，做前向验证
14. 与后向图做交集，得到完整核心溯源图
15. 可选地再用 LLM 做二次过滤
16. 把图、入口排序、统计和日志全部输出到结果目录

## 21. 当前实现里几个很容易忽略、但非常关键的细节

### 21.1 Theia 构图后，POI 必须匹配“构图后的签名”，不是原始 UUID

例如：

- 文件 POI 要写文件路径
- 网络 POI 要写 `local:port->remote:port`
- 进程 POI 要写 `cid@uuid8name`

不能直接把原始 CDM 里的 UUID 填进 `.property` 里。

### 21.2 当前核心图默认不是“全部候选入口”

虽然 `EntryPointsNumber` 可能很大，但最终真正进入核心图生成的通常只有每类前 3 个。

### 21.3 `entry=` 目前更像“日志提示”，不是“强制入口白名单”

如果你后续想把 `entry` 改造成“强制纳入前向验证的入口列表”，当前主流程还需要进一步改。

### 21.4 sysdig 和 Theia 的差别主要集中在构图前半段

一旦进入 `DirectedPseudograph<EntityNode, EventEdge>`，后面的：

- BackTrack
- CPR
- weight
- propagation
- entry ranking
- forward validation
- LLM filtering

基本完全共享。

这意味着如果你后续要研究：

- 更好的压缩算法
- 更好的边权重
- 更好的入口打分
- 更好的核心图生成

这些改动几乎都可以同时服务 sysdig 和 Theia 两条输入链。

## 22. 一句话概括当前项目对 Theia 数据的完整处理方式

当前项目对 `Transparent_Computing_Engagement_5_Dataset\Data\theia` 的处理，本质上是：

```text
用 .cdm manifest 指定时间窗和文件集合
-> 直接流式读取 Theia CDM Avro/GZip
-> 先缓存实体、再映射事件
-> 构造统一的 EntityNode/EventEdge 依赖图
-> 从 POI 做后向切片
-> 做因果保持压缩
-> 计算边权重并反向传播可疑度
-> 提取候选入口并排序
-> 用前向可达性验证入口到 POI 的路径
-> 融合前后向结果生成核心溯源图
-> 可选地再用 LLM 做最后一轮过滤和连通性修补
```

如果后续你要继续改进 Theia 场景下的效果，最值得重点看的实现文件依次是：

- `ExperimentRunnerCmd.java`
- `CdmGraphManifest.java`
- `CdmGraphBuilder.java`
- `CdmEventMapper.java`
- `ProcessOneLogCMD_19.java`
- `BackTrack.java`
- `CausalityPreserve.java`
- `BackwardPropagate_pf.java`
- `ForwardAnalysis.java`
- `LLMGraphFilter.java`
