# DepImpact - 网络攻击溯源系统

## 项目概述

本项目复现了论文 **"Back-Propagating System Dependency Impact for Attack Investigation"** 中的网络攻击溯源系统。该系统通过分析系统审计日志（如Sysdig），构建进程、文件、网络实体之间的依赖图，并使用后向影响传播算法识别攻击入口点。

系统新增 **LLM过滤模块 (LLMGraphFilter)**，可调用大语言模型（如OpenAI、NVIDIA DeepSeek等）进一步分析溯源图，识别真正的攻击路径并过滤背景噪音。

## 核心概念

### 1. 实体类型 (Entity Types)
- **Process (进程)**: 由 `Process.java` 表示，标识系统进程
- **File (文件)**: 由 `FileEntity.java` 表示，标识文件路径
- **Network (网络)**: 由 `NetworkEntity.java` 表示，标识网络连接 (IP:Port -> IP:Port)

### 2. 事件类型 (Event Types)
系统依赖图中的边（事件）分为五种：
- **PtoP (Process to Process)**: 进程间通信 (execve系统调用)
- **PtoF (Process to File)**: 进程写文件 (write/writev系统调用)
- **FtoP (File to Process)**: 文件被进程读取 (read/readv系统调用)
- **PtoN (Process to Network)**: 进程发起网络连接 (sendto/write/sendmsg系统调用)
- **NtoP (Network to Process)**: 网络连接被接收 (read/recvmsg/recvfrom系统调用)

### 3. 核心算法流程

```
日志文件 → 解析事件 → 构建依赖图 → BackTrack后向切片 → CPR因果压缩 → 权重计算 → PageRank传播 → 识别入口点
```

#### Step 1: 图构建 (GetGraph.java)
- 从Sysdig日志中解析系统调用事件
- 根据事件类型创建5种事件边
- 构建有向伪图 (DirectedPseudograph<EntityNode, EventEdge>)

#### Step 2: BackTrack后向切片 (BackTrack.java)
- 从检测点(POI - Point of Interest)开始
- 反向遍历，只保留能够到达POI的节点和边
- 得到因果子图，大幅减少分析规模

#### Step 3: 因果保持压缩 (CausalityPreserve.java)
- 合并时间窗口内(默认10秒)相同主体和客体的连续操作
- 在保持因果关系的前提下压缩图规模

#### Step 4: 特征权重计算 (BackwardPropagate.java)
系统支持多种权重计算模式(通过mode参数选择):
- `nonml`: 手动权重分配 (时间0.5 + 结构0.5，或 时间0.1 + 结构0.4 + 数据量0.5)
- `clusterall`: 全局聚类 + FDA降维
- `clusterlocal`: 局部聚类 + FDA降维 (论文核心方法)
- `nonoutlier`: 排除离群点后聚类
- `localtime`: 仅时间权重
- `localamount`: 仅数据量权重
- `localstruct`: 仅结构权重(扇出)
- `fanout`: 扇出权重
- `nonmlrandom`: 随机权重(基线对比)

#### Step 5: PageRank式传播 (BackwardPropagate_pf.java)
- 初始化: 高可信实体(highRP)设为1.0，低可信实体(lowRP)设为0.0
- 反向迭代传播恶意度分数
- 使用阻尼因子(damping factor = 0.85)

#### Step 6: 入口点识别
- 获取得分最高的节点作为候选入口点
- 结合前向分析验证因果路径

#### Step 7: LLM图过滤 (LLMGraphFilter.java)
- 将溯源图序列化为文本格式，构建Prompt
- 调用LLM API（如OpenAI、NVIDIA DeepSeek等）分析因果关系
- 从LLM响应中提取需要保留的边ID
- 构建过滤后的精简溯源图，仅保留真正的攻击路径

## 项目结构

```
src/main/java/
├── pagerank/                    # 核心算法
│   ├── algorithm/                # 核心算法实现
│   │   ├── BackTrack.java           # 后向切片算法
│   │   ├── CausalityPreserve.java   # 因果保持压缩
│   │   ├── BackwardPropagate.java   # 权重计算(旧版)
│   │   ├── BackwardPropagate_pf.java # 权重计算 + PageRank传播
│   │   ├── ForwardAnalysis.java     # 前向分析
│   │   ├── GetGraph.java            # 从日志构建依赖图
│   │   ├── IterateGraph.java        # 图遍历和导出
│   │   ├── LLMGraphFilter.java     # LLM图过滤模块
│   │   └── NODOZE.java              # 无用模块(可忽略)
│   ├── entity/                   # 核心实体类
│   │   ├── Entity.java              # 实体基类
│   │   ├── EntityNode.java          # 图节点(封装Process/File/Network)
│   │   ├── EventEdge.java           # 图边(事件)
│   │   ├── EventEdgeWrapper.java    # 事件边封装类
│   │   ├── Event.java               # 事件基类
│   │   ├── Process.java             # 进程实体
│   │   ├── FileEntity.java          # 文件实体
│   │   ├── NetworkEntity.java       # 网络实体
│   │   ├── PtoPEvent.java           # PtoP事件实现
│   │   ├── PtoFEvent.java           # PtoF事件实现
│   │   ├── FtoPEvent.java           # FtoP事件实现
│   │   ├── PtoNEvent.java           # PtoN事件实现
│   │   └── NtoPEvent.java           # NtoP事件实现
│   ├── provider/                 # 数据提供器
│   │   ├── EntityAttributeProvider.java # 实体属性提供器
│   │   ├── EntityIdProvider.java     # 实体ID提供器
│   │   ├── EntityNameProvider.java   # 实体名称提供器
│   │   ├── EdgeAmountTimeProvider.java # 边数量时间提供器
│   │   └── EventEdgeProvider.java   # 事件边提供器
│   ├── main/                    # 主程序入口
│   │   ├── Experiment.java          # 实验配置解析
│   │   ├── ExperimentRunnerCmd.java # 主入口(命令行)
│   │   └── ProcessOneLogCMD_19.java # 实验流程(含LLM调用)
│   └── config/                  # 配置文件
│       └── MetaConfig.java          # 系统配置(本地IP、系统调用白名单)
└── logparsers/                  # 日志解析
    ├── SysdigOutputParser.java  # Sysdig日志解析器
    ├── SysdigOutputParserNoRegex.java # 无正则表达式版本
    ├── Utils.java               # 工具类
    ├── exceptions/              # 异常处理
    │   ├── UnknownEventException.java
    │   ├── ParentNotSeenException.java
    │   ├── InvalidLogFormatException.java
    │   └── EventStartUnseenException.java
    └── systemcalls/             # 系统调用定义
        ├── SystemCall.java
        ├── SystemCallFactory.java
        ├── Action.java
        └── Fingerprint.java
```

## 输入格式

### 1. 日志文件 (.txt)
Sysdig格式的系统审计日志，包含系统调用事件。

### 2. 配置文件 (.property)
与日志同名的配置文件，以 `.backward` 结尾表示反向溯源分析。

配置项说明:
```properties
POI = /tmp/malicious_file.txt          # 检测点(恶意文件/网络连接)
highRP = 192.168.1.1:80->...,/bin/ls  # 高可信实体(系统进程/正常IP)
lowRP = 192.168.1.100:44444->...       # 低可信实体(可疑IP/临时文件)
midRP = /lib64/libc.so.6,...            # 中可信实体(可选，扩展白名单)
detectionSize = 1024                    # 检测到的数据量
criticalEdge = edge1;edge2              # 关键边(用于评估)
```

### 3. LLM配置文件 (llm.properties)
可选配置文件，用于启用LLM图过滤功能。

配置项说明:
```properties
base_url = https://api.openai.com/v1  # LLM API端点
api_key = sk-xxxxx                       # API密钥
model = gpt-4o                           # 模型名称(如gpt-4o、deepseek-chat等)
```

支持多种LLM服务:
- **OpenAI**: `base_url=https://api.openai.com/v1`
- **NVIDIA DeepSeek**: `base_url=https://integrate.api.nvidia.com/v1`

## 运行方式

```bash
# 编译项目
mvn clean package

# 运行实验
# 参数: <日志路径> <结果目录> <日志文件名>
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar \
    ./input/logs_fine \
    ./output \
    wget.txt
```

或在IDE中运行 `pagerank.ExperimentRunnerCmd` 类，参数格式:
```
<日志路径> <结果目录> <日志文件名(多个用分号分隔)>
```

## 输出结果

运行后在结果目录生成:
- `*_stats`: 各阶段节点/边数量、时间消耗统计
- `BackTrack_*.dot`: 后向切片子图
- `AfterCPR_*.dot`: CPR压缩后的图
- `Weight_*.dot`: 带权重的最终图
- `*_entry_points.json`: 识别出的入口点列表
- `results/` 目录: 完整的溯源图(.dot + .svg)
- `llm_interaction_*.log`: LLM API调用日志(Prompt和Response)
- `llm_filtered_graph_*.svg`: LLM过滤后的精简溯源图

## 关键配置 (MetaConfig.java)

```java
localIP = {"127.0.0.1"}           # 本地IP地址
ptopSystemCall = {"execve"}        # P2P事件系统调用
ptofSystemCall = {"write","writev"} # P2F事件系统调用
ftopSystemCall = {"read","readv"}  # F2P事件系统调用
ptonSystemCall = {"sendto","write","writev", "sendmsg"} # P2N事件系统调用
ntopSystemCall = {"read", "recvmsg", "recvfrom","readv"} # N2P事件系统调用
```

## 依赖库

- **JGraphT**: 图数据结构
- **Apache Commons Math3**: 聚类、降维(FDA)
- **Graphviz-Java**: 图可视化
- **JSON-Simple**: JSON处理
- **Spring Boot**: 项目框架

## 扩展开发

如需修改核心算法:
1. 权重计算: 修改 `BackwardPropagate_pf.java` 中的 `calculateWeights_*` 方法
2. 传播算法: 修改 `PageRankIterationBackward` 方法
3. 入口点识别: 修改 `getForwardStarts` 和 `getCandidateEntryPoint` 方法
4. 添加新事件类型: 在 `EventEdge.java` 添加构造函数，在 `GetGraph.java` 添加处理逻辑
5. LLM过滤: 修改 `LLMGraphFilter.java` 中的 `buildPrompt` 方法调整Prompt策略，或修改 `extractEdgeIdsFromResponse` 方法优化边ID提取逻辑
