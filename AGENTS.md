# AGENTS.md - Developer Guide for depimpact_source

## 项目背景

### 论文基础

本项目基于 **Back-Propagating System Dependency Impact for Attack Investigation** 论文中的系统进行改进。原始论文提出了一种基于图的后向传播攻击溯源方法，其核心思想是：

1. **系统依赖图构建**：将系统日志（如sysdig日志）解析为溯源图（Provenance Graph），节点代表实体（进程、文件、网络连接），边代表事件关系
2. **异常检测点（POI）**：用户指定攻击中已被检测到的异常点作为起点
3. **后向传播分析**：使用类似PageRank的迭代算法，从POI出发沿着依赖边向后追溯，找出攻击的根源
4. **权重计算**：使用多个特征（时间、数据流、节点度数/结构）计算边的权重，指导溯源方向

### 核心概念

- **Entity（实体）**：系统中的进程(Process)、文件(File)、网络连接(Network)
- **Event（事件）**：实体之间的交互，如进程读写文件、进程间通信、网络连接等
- **POI（Point of Interest）**：已知的攻击检测点，作为溯源的起点
- **Entry Point**：通过后向传播分析确定的可能攻击入口点
- **边权重**：决定溯源方向的概率分布，权重越高的边越可能是攻击路径

### 事件类型

系统支持5种事件类型（定义在`pagerank/`包中）：
- **PtoF (Process to File)**：进程操作文件
- **FtoP (File to Process)**：文件触发进程（如执行脚本）
- **PtoP (Process to Process)**：进程间通信
- **PtoN (Process to Network)**：进程发起网络连接
- **NtoP (Network to Process)**：网络连接到达进程

### 原始系统特征

原始系统使用三个特征计算边权重：
1. **时间权重(Time Weight)**：基于事件发生的时间顺序，高权重边发生在POI之前不久
2. **数据流权重(Amount Weight)**：基于传输的数据量，数据量大的边权重更高
3. **结构权重(Structure Weight)**：基于节点度数，连接多个节点的边权重更高

## 项目改进

本项目在原始论文基础上进行了两项重要改进：

### 改进1：异常评分特征

**改进文件**：`BackwardPropagate_pf.java`、`EventEdge.java`、`SysdigOutputParserNoRegex.java`

**改进内容**：
- 在日志解析阶段从原始日志中提取`anomaly_score`字段（由外部异常检测系统生成）
- 将异常评分作为第四个特征纳入边权重计算
- 权重计算公式（正常情况）：
  ```
  weight = 0.25*timeWeight + 0.25*structureWeight + 0.25*amountWeight + 0.25*anomalyWeight
  ```
- 数据量极小时的权重计算：
  ```
  weight = 0.33*timeWeight + 0.33*structureWeight + 0.34*anomalyWeight
  ```

**异常分数来源**：
- 日志格式：sysdig日志的第12字段，字段名为`anomaly_score`
- 日志解析器通过`extractAnomalyScore()`方法提取（见`SysdigOutputParserNoRegex.java:79-94`）

**效果**：使溯源算法能够优先追踪具有异常行为的边，提高攻击路径的准确性。

### 改进2：LLM语义过滤

**改进文件**：`LLMGraphUtils.java`、`ProcessOneLogCMD_19.java`

**改进内容**：
- 在完成图的后向传播分析后，生成完整的溯源图
- 使用LLM对溯源图进行语义分析，过除噪音边，保留与攻击相关的关键路径
- 过滤流程：
  1. 将图序列化为JSON格式（包含边ID、操作类型、源/目标实体、时间戳）
  2. 将JSON发送给LLM进行分析
  3. LLM返回需要保留的边ID列表
  4. 根据ID列表过滤图中边，删除不相关边和孤立节点

**关键设计**：
- 发送给LLM的JSON不包含异常分数和数据量，强制LLM仅基于语义（进程名、文件名）判断
- 保留高异常评分边的可视化标注（用于结果验证）

**效果**：大幅精简溯源图，便于安全分析人员人工审查和理解攻击路径。

## Project Overview

This is a Java Maven project (Spring Boot 2.7.18 based) for dependency impact analysis using graph-based algorithms. The main entry point is `pagerank.ExperimentRunnerCmd`.

## Build Commands

```bash
# Compile the project
mvn clean compile

# Build JAR (includes dependencies)
mvn clean package

# Run the application
java -jar target/reptracker-1.0-SNAPSHOT-jar-with-dependencies.jar <log_path> <result_path> <log_names>
```

## Test Commands

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=ClassName

# Run a specific test method
mvn test -Dtest=ClassName#methodName

# Run with verbose output
mvn test -X
```

## Project Structure

```
src/main/java/
├── pagerank/           # Main application logic
│   ├── ExperimentRunnerCmd.java   # CLI entry point
│   ├── IterateGraph.java         # Graph iteration
│   ├── BackwardPropagate.java    # Backward analysis
│   ├── ForwardAnalysis.java      # Forward analysis
│   ├── Entity.java               # Entity model
│   ├── EntityNode.java           # Graph node
│   ├── Event.java / EventEdge.java   # Event models
│   └── [EventTypes].java         # FtoPEvent, PtoFEvent, etc.
└── logparsers/         # Log parsing utilities
    ├── SysdigOutputParser.java
    └── systemcalls/    # System call handling

test/                   # Test data (not JUnit tests)
```

## Code Style Guidelines

### Naming Conventions

- **Classes**: PascalCase (e.g., `ExperimentRunnerCmd`, `BackwardPropagate`)
- **Methods**: camelCase (e.g., `calculateWeights`, `setDetectionSize`)
- **Variables**: camelCase (e.g., `graphFromLog`, `PathToLogs`, `dumpingFactor`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `POITime` uses camel but treat as constant)
- **Packages**: lowercase (e.g., `pagerank`, `logparsers`)

### Formatting

- **Indentation**: 4 spaces (no tabs)
- **Line length**: No strict limit, but prefer <120 chars
- **Braces**: Same-line opening brace for classes/methods
- **Imports**: Grouped by: java.*, org.*, other libraries, then static imports
- **No automated formatter**: Manually format code to match existing style

### Types

- Use primitive types where possible (`int`, `double`, `long`)
- Use wrapper classes for collections (`Long`, `Double` for autoboxing)
- Collections: `Map<K,V>`, `Set<T>`, `List<T>` over concrete implementations
- Use `HashMap`, `ArrayList` for implementation when instantiating

### Visibility

- Default (package-private) is acceptable for internal classes
- Use `public` for API classes and methods
- Use `private` for internal state

### Error Handling

- Use try-catch for expected exceptions
- Catch specific exceptions over generic `Exception`
- Use `e.printStackTrace()` for logging errors (existing pattern)
- Throw custom exceptions from `logparsers/exceptions/` for parsing errors

### Code Patterns

```java
// Preferred class structure
package pagerank;

import java.io.*;
import java.util.*;
import org.jgrapht.graph.DirectedPseudograph;

public class ClassName {
    // Fields first (package-private)
    DirectedPseudograph<EntityNode, EventEdge> graph;
    Map<Long, Double> weights;
    
    // Constructor
    public ClassName(DirectedPseudograph<EntityNode, EventEdge> input) {
        this.graph = input;
        this.weights = new HashMap<>();
    }
    
    // Public methods
    public void methodName() {
        // implementation
    }
    
    // Private methods
    private void helperMethod() {
        // implementation
    }
}
```

### Java Version

- Target: Java 8 (maven.compiler.source/target = 8)
- Avoid features requiring Java 9+

### Logging

- Use `System.out.println()` for general output
- Use `System.err.println()` for errors
- Consider using `java.util.logging` for complex scenarios

## Running the Application

```bash
# Example usage
mvn compile
java -cp target/classes:target/dependency/* pagerank.ExperimentRunnerCmd ./test ./test/result attack.txt
```

Required files:
- Log file: `attack.txt`
- Property file: `attack.backward.property` (same directory)

## Dependencies

Key libraries (see pom.xml):
- Spring Boot 2.7.18
- JGraPhT 1.1.0 (graph algorithms)
- GraalVM JS 22.3.2 (JavaScript engine)
- Apache Commons (lang3, math3)
- JSON Simple
- JUnit 5 (testing)

## Notes

- No linter/formatter configured - follow existing code style manually
- Some code contains Chinese comments - preserve them
- The codebase uses graph-based dependency tracking with PageRank-like algorithms
