# AGENTS.md

## 基本沟通要求

- 始终使用中文回答用户问题。
- 本项目是对论文 `Back-Propagating System Dependency Impact for Attack Investigation.pdf` 中系统的复现与扩展，后续修改应围绕论文方法、当前 Java 实现和实验可复现性展开。
- 任何改动都应优先服务两个目标：减小溯源图规模、提高攻击溯源准确性。

## 项目定位

- 项目名称：DepImpact / reptracker。
- 技术栈：Java 17、Maven、JGraphT、Graphviz。
- 输入：Sysdig 导出的系统调用日志 `.txt`，以及同名的实验配置 `.property`。
- 输出：反向溯源、中间统计、入口点排序、核心溯源图、LLM 过滤后的溯源图等结果。
- 论文核心思想：从检测点/POI 进行后向依赖传播，评估系统实体对检测点的影响，结合权重、PageRank 式传播和前向验证得到攻击调查所需的核心依赖图。

## 当前重点优化方向

后续 agent 需要理解：用户希望在现有复现系统上进行方法改进，而不是单纯重构代码。重点包括：

1. 修改和优化因果保持压缩算法。
2. 修改边的特征权重设计与融合方式。
3. 优化核心溯源图生成流程：
   - 提取潜在攻击源实体集合，判定候选入口。
   - 依据传播得分评估优先级，排序候选入口。
   - 执行前向验证，检查入口至检测点的因果可达性。
   - 融合前向与后向分析结果，生成核心溯源图。
4. 增加或完善 LLM 溯源图过滤模块。

这些优化的共同目的：在保持关键因果链完整的前提下，进一步压缩溯源图规模，并提高入口点识别、路径保留和攻击解释的准确性。

## 关键文件与职责

- `src/main/java/pagerank/main/ExperimentRunnerCmd.java`：命令行入口，读取全局配置和实验配置，批量运行日志案例。
- `src/main/java/pagerank/main/ProcessOneLogCMD_19.java`：单个日志的核心处理流程，串联 BackTrack、CPR、权重计算、PageRank、入口点选择、前向验证、核心图生成和 LLM 过滤。
- `src/main/java/pagerank/algorithm/BackTrack.java`：从 POI/检测点进行后向切片，得到可达检测点的依赖子图。
- `src/main/java/pagerank/algorithm/CausalityPreserve.java`：因果保持压缩算法入口，当前支持 `windowed_sequence`、`standard_cpr`、`causal_strict`、`fd`、`sd`、`full_merge`、`endpoint_aggregation`、`no_merge` 等模式。
- `src/main/java/pagerank/algorithm/IterateGraph.java`：图遍历、边权重计算、PageRank 式传播、候选入口提取和结果导出。
- `src/main/java/pagerank/algorithm/BackwardPropagate_pf.java`：后向传播、候选入口排序、前向入口列表生成，以及前后向融合生成核心溯源图。
- `src/main/java/pagerank/algorithm/ForwardAnalysis.java`：从候选入口进行前向验证，检查入口到 POI 的因果可达路径。
- `src/main/java/pagerank/algorithm/LLMGraphFilter.java`：LLM 溯源图过滤模块，负责图序列化、提示词构造、模型调用、边/入口解析和连通性兜底。
- `src/main/java/pagerank/main/LLMFilterSnapshotIO.java`：核心图快照读写，用于复用 LLM 过滤输入。
- `src/main/java/pagerank/main/LLMFilterRunner.java`：独立运行 LLM 过滤快照的入口。
- `src/main/java/pagerank/config/GlobalConfig.java`：读取 `depimpact.properties` 中的全局配置。
- `src/main/java/pagerank/entity/EventEdge.java`：依赖边实体，包含时间、事件类型、数据量和权重字段，是边特征改造的基础类。
- `depimpact.properties`：全局运行配置，包括 `weight_mode`、`cpr_mode`、`cpr_time_window`、`fd_window_size`、`sd_source_set_limit`、LLM 配置、系统调用白名单和默认背景实体。

## 核心处理流程

修改核心逻辑前，优先沿以下调用链理解系统：

1. `ExperimentRunnerCmd.main` 解析运行参数。
2. `ExperimentRunnerCmd.runExperiment` 调用 `ProcessOneLogCMD_19.run_exp_backward`。
3. `BackTrack.backTrackPOIEvent` 从 POI 生成后向切片。
4. `CausalityPreserve.applyMode` 对后向切片做因果保持压缩。
5. `IterateGraph` 根据 `weight_mode` 计算边权重并执行传播。
6. `BackwardPropagate_pf.getForwardStarts` 提取和排序候选入口。
7. `ForwardAnalysis` 对候选入口执行前向可达性验证。
8. `BackwardPropagate_pf.combineBackwardAndForwardForMultipleStarts` 融合前后向结果，生成核心溯源图。
9. `LLMGraphFilter.filterGraph` 可选地进一步过滤核心溯源图。

## 因果保持压缩改造原则

- 不要只追求边数减少；压缩后必须尽量保留从候选攻击源到 POI 的关键因果可达性。
- 新增 CPR 模式时，优先在 `CausalityPreserve.applyMode` 中添加模式常量和分支，并同步更新 `depimpact.properties` 注释、README 或相关说明。
- 避免在压缩中合并语义不同但时间接近的边，尤其要谨慎处理进程执行、网络收发、文件读写之间的方向和依赖语义。
- 如果引入更激进的合并策略，应保留可对照的 baseline，例如 `no_merge`、`windowed_sequence`、`fd` 或 `sd`。
- 压缩算法应记录压缩前后顶点数、边数、耗时和压缩率，便于和论文方法及旧实现比较。

## 边权重改造原则

- 边权重相关逻辑主要在 `IterateGraph.java`，边字段在 `EventEdge.java`。
- 修改权重时要区分时间权重、数据量权重、结构/扇出权重、系统调用语义权重、实体类型权重等特征。
- 新权重模式应通过 `weight_mode` 显式选择，避免直接覆盖现有模式导致实验不可复现。
- 对涉及归一化、标准化、异常值处理和出边归一的逻辑，必须检查是否会改变 PageRank 传播方向或放大背景噪声实体。
- 权重设计应优先提升攻击入口、异常网络连接、可疑进程执行和关键文件修改的排序质量，同时抑制库文件、系统配置、只读文件等背景实体。

## 候选入口与核心图生成优化原则

- 候选入口提取应结合传播得分、实体类型、边类型、时间位置、与 POI 的可达路径和是否属于背景实体。
- 排序时不要只看单一 reputation/传播分数；可以考虑路径长度、路径证据强度、跨实体类型转换、网络入口特征、进程祖先链等因素。
- 前向验证必须确认候选入口到检测点存在因果路径；如果为了连通性补边，应明确标记或记录补边来源。
- 核心图生成应尽量保留能够解释攻击链的最小必要路径，而不是简单导出完整后向切片。
- 任何过滤策略都应避免删除 POI、已选入口、关键桥接边和连接入口到 POI 的唯一因果路径。

## LLM 溯源图过滤模块原则

- LLM 过滤是后处理模块，不应替代确定性的因果可达性检查。
- 调用 LLM 前应尽量输入已经压缩和排序后的核心图，而不是完整原始图。
- Prompt 应要求模型输出结构化结果，例如保留边 ID、保留入口、删除理由和攻击链摘要。
- LLM 输出必须经过程序校验：边 ID 是否存在、入口是否存在、过滤后是否仍包含 POI、入口到 POI 是否连通。
- LLM 过滤失败时，系统应回退到未经过 LLM 过滤的核心溯源图，不能中断主实验。
- 不要把真实 API Key 写入代码或提交到仓库；优先通过配置文件占位符、环境变量或本地私有配置传入。

## 配置与运行

- Maven 构建：`mvn package`。
- 运行示例：`java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar ./test ./test/result attack.txt`。
- 多日志运行：第三个参数使用分号分隔，例如 `"attack.txt;cmd-inject.txt"`。
- 交互选择日志：`java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar ./test ./test/result --interactive`。
- 修改 `weight_mode`、`cpr_mode`、`cpr_time_window`、`fd_window_size`、`sd_source_set_limit` 后，应记录配置组合和结果指标。

## 实验与评估建议

- 优先保留并比较以下指标：后向切片规模、CPR 后规模、核心溯源图规模、LLM 过滤后规模、运行耗时、候选入口排名、关键路径是否保留。
- 对每个优化策略至少与一个 baseline 比较，例如 `no_merge`、`windowed_sequence`、`fd`、`sd` 或原论文对应模式。
- 如果新增指标，建议写入已有 JSON 输出结构，避免只打印到控制台。
- 修改算法后，应使用同一日志和同一 `.property` 配置对比改动前后的输出图和统计结果。

## 代码修改约束

- 保持现有包结构和命名风格，优先做小而集中的改动。
- 不要无关重构日志解析、实体模型或输出格式，除非它们直接阻塞上述优化目标。
- 修改公共流程时注意保持旧配置可运行，新增策略应默认关闭或通过配置显式启用。
- 不要删除已有实验模式、已有 JSON 字段或已有图输出，除非用户明确要求。
- 处理中文注释或 README 时注意文件编码，避免引入乱码。

## 安全与隐私

- `depimpact.properties` 中可能包含本地或真实 LLM API Key。后续 agent 不应在回答、日志或提交说明中泄露密钥内容。
- 若需要展示配置示例，使用 `your_api_key`、`${LLM_API_KEY}` 等占位符。
- 不要把大型数据集、生成结果目录或敏感日志加入版本控制。

