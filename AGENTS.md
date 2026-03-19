# DepImpact - 网络攻击溯源系统

## 项目概述

本项目复现了论文 **"Back-Propagating System Dependency Impact for Attack Investigation"** 中的网络攻击溯源系统。该系统通过分析系统审计日志（如Sysdig），构建进程、文件、网络实体之间的依赖图，并使用后向影响传播算法识别攻击入口点。

系统新增 **LLM过滤模块 (LLMGraphFilter)**，可调用大语言模型（如OpenAI、NVIDIA DeepSeek等）进一步分析溯源图，识别真正的攻击路径并过滤背景噪音。

## 快速开始

```bash
# 1. 编译项目
mvn clean package

# 2. 运行实验=
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar \
    ./test \
    ./test/result \
    attack.txt
```

**命令行参数**：
- `args[0]`: 日志文件所在目录路径
- `args[1]`: 结果输出目录路径
- `args[2]`: 日志文件名（多个用分号分隔，如"wget.txt;curl.txt"）

## 核心概念

### 1. 实体类型 (Entity Types)
- **Process (进程)**: `Process.java`，标识系统进程
- **File (文件)**: `FileEntity.java`，标识文件路径
- **Network (网络)**: `NetworkEntity.java`，标识网络连接 (IP:Port -> IP:Port)

### 2. 事件类型 (Event Types)
系统依赖图中的边（事件）分为五种：
- **PtoP (Process to Process)**: 进程间通信 (execve系统调用)
- **PtoF (Process to File)**: 进程写文件 (write/writev系统调用)
- **FtoP (File to Process)**: 文件被进程读取 (read/readv系统调用)
- **PtoN (Process to Network)**: 进程发起网络连接 (sendto/write/sendmsg系统调用)
- **NtoP (Network to Process)**: 网络连接被接收 (read/recvmsg/recvfrom系统调用)

## 核心算法流程

```
日志文件 → 解析事件 → 构建依赖图 → BackTrack后向切片 → CPR因果压缩 → 权重计算 → PageRank传播 → 识别入口点
```

| 步骤 | 文件 | 功能 |
|------|------|------|
| Step 1 | `GetGraph.java` | 从Sysdig日志解析事件，构建有向伪图 |
| Step 2 | `BackTrack.java` | 后向切片，从POI反向遍历保留因果子图 |
| Step 3 | `CausalityPreserve.java` | 因果保持压缩，合并时间窗口内连续操作 |
| Step 4 | `BackwardPropagate_pf.java` | 特征权重计算 + PageRank传播 |
| Step 5 | `ForwardAnalysis.java` | 前向分析，验证因果路径 |
| Step 6 | `LLMGraphFilter.java` | LLM图过滤，识别真正攻击路径 |

### 权重计算模式 (mode参数)

通过根目录的 `depimpact.properties` 中的 `weight_mode` 设置，默认 `clusterall`：

| 模式 | 说明 |
|------|------|
| `nonml` | 手动权重分配 |
| `clusterall` | 全局聚类 + FDA降维 |
| `clusterlocal` | 局部聚类 + FDA降维（论文核心） |
| `localtime` | 仅时间权重 |
| `localamount` | 仅数据量权重 |
| `localstruct` | 仅结构权重(扇出) |
| `fanout` | 扇出权重 |

## 输入格式

### 1. 日志文件 (.txt)
Sysdig格式的系统审计日志，包含系统调用事件。示例目录：`data/attack_log/`

### 2. 配置文件 (.property)
与日志同名的配置文件，以 `.backward` 结尾表示反向溯源分析。示例目录：`data/attack_properties/`

**配置项说明**：
```properties
POI = /tmp/malicious_file.txt          # 检测点(恶意文件/网络连接)
highRP = 192.168.1.100:44444->...      # 高可疑实体(reputation=1.0)
lowRP = 192.168.1.1:80->...,/bin/ls    # 低可疑实体(reputation=0.0)
midRP = /lib64/libc.so.6,...            # 背景噪音实体(可选)
detectionSize = 1024                    # 检测到的数据量
criticalEdge = edge1;edge2              # 关键边(用于评估)
```

### 3. 全局配置文件 (depimpact.properties)
系统级配置统一放在根目录的 `depimpact.properties` 中，包括 LLM 图过滤配置、Weight mode、本地 IP、默认 midRP 和 syscall 白名单等。

```properties
weight_mode=clusterall
llm_enabled=true                       # 是否启用LLM过滤
base_url=https://integrate.api.nvidia.com/v1  # LLM API端点
api_key = your-api-key                 # API密钥
model = nvidia/llama-3.3-nemotron-super-49b-v1  # 模型名称
temperature=0.1                        # 温度参数
max_tokens=20480                       # 最大token数
```


## 项目结构

```
src/main/java/
├── pagerank/
│   ├── algorithm/                      # 核心算法实现
│   │   ├── GetGraph.java              # 图构建入口
│   │   ├── BackTrack.java             # 后向切片
│   │   ├── CausalityPreserve.java     # 因果压缩
│   │   ├── BackwardPropagate_pf.java  # 权重计算+PageRank
│   │   ├── ForwardAnalysis.java       # 前向分析
│   │   ├── IterateGraph.java          # 图导出
│   │   └── LLMGraphFilter.java        # LLM过滤
│   ├── entity/                        # 实体类
│   │   ├── EntityNode.java            # 图节点
│   │   ├── EventEdge.java             # 图边
│   │   ├── Process.java               # 进程实体
│   │   ├── FileEntity.java            # 文件实体
│   │   ├── NetworkEntity.java         # 网络实体
│   │   └── *Event.java                # 5种事件类型
│   ├── provider/                      # 数据提供器
│   ├── main/                          # 主入口
│   │   ├── ExperimentRunnerCmd.java   # 命令行入口
│   │   ├── Experiment.java            # 配置解析
│   │   └── ProcessOneLogCMD_19.java   # 实验流程
│   └── config/
│       └── MetaConfig.java            # 系统配置
└── logparsers/                        # 日志解析
    ├── SysdigOutputParser.java        # Sysdig解析器
    └── systemcalls/                   # 系统调用定义
```

## 关键类说明

### 主入口类
- **ExperimentRunnerCmd**: `src/main/java/pagerank/main/ExperimentRunnerCmd.java:41`
  - main方法：程序入口，解析命令行参数
  - run2方法：主实验执行流程

### 核心算法类
- **GetGraph**: `src/main/java/pagerank/algorithm/GetGraph.java`
  - GenerateGraph(): 解析Sysdig日志，构建依赖图
  - getJg(): 获取构建的图

- **BackwardPropagate_pf**: `src/main/java/pagerank/algorithm/BackwardPropagate_pf.java`
  - calculateWeights_*(): 权重计算方法
  - PageRankIterationBackward(): PageRank传播迭代

- **LLMGraphFilter**: `src/main/java/pagerank/algorithm/LLMGraphFilter.java`
  - buildPrompt(): 构建LLM提示词
  - extractEdgeIdsFromResponse(): 从LLM响应提取边ID
  - filterGraph(): 执行图过滤

### 实体类
- **EntityNode**: `src/main/java/pagerank/entity/EntityNode.java` - 图节点封装
- **EventEdge**: `src/main/java/pagerank/entity/EventEdge.java` - 图边/事件
- **Process/FileEntity/NetworkEntity**: 进程/文件/网络实体

### 配置类
- **MetaConfig**: `src/main/java/pagerank/config/MetaConfig.java` - 系统配置
  - localIP: 本地IP地址列表
  - ptopSystemCall/ptofSystemCall/ftopSystemCall/ptonSystemCall/ntopSystemCall: 系统调用白名单

## 输出结果

运行后在结果目录生成：
- `*_stats`: 各阶段节点/边数量、时间消耗统计
- `BackTrack_*.dot`: 后向切片子图
- `AfterCPR_*.dot`: CPR压缩后的图
- `Weight_*.dot`: 带权重的最终图
- `*_entry_points.json`: 识别出的入口点列表
- `results/`: 完整溯源图(.dot + .svg)
- `llm_interaction_*.log`: LLM API调用日志
- `llm_filtered_graph_*.svg`: LLM过滤后的精简溯源图

## 依赖库

- **JGraphT**: 图数据结构
- **Apache Commons Math3**: 聚类、降维(FDA)
- **Graphviz-Java**: 图可视化
- **JSON-Simple**: JSON处理
- **Spring Boot**: 项目框架

## 扩展开发指南

1. **权重计算**: 修改 `BackwardPropagate_pf.java` 中的 `calculateWeights_*` 方法
2. **传播算法**: 修改 `PageRankIterationBackward` 方法
3. **入口点识别**: 修改 `getForwardStarts` 和 `getCandidateEntryPoint` 方法
4. **添加新事件类型**: 在 `EventEdge.java` 添加构造函数，在 `GetGraph.java` 添加处理逻辑
5. **LLM过滤**: 修改 `LLMGraphFilter.java` 中的 `buildPrompt` 方法调整Prompt策略

## 样例数据

- 日志文件: `data/attack_log/complex_one_longer.txt`
- 配置文件: `data/attack_properties/complex_one_longer-backward.property`
