## 运行方式 (How to run)

在运行之前，需要准备好日志文件及其相关的属性文件。日志文件应以 `.txt` 结尾，属性文件应与相关日志同名，并以 `.backward` 作为关键字。日志和属性文件必须存放在同一个文件夹中。

```text
> cmd_inject.txt
> cmd_inject.backward.property 

```

### 通过 Java 运行

运行类 `ExperimentRunnerCmd`。具体参数如下：

1. **日志与属性文件的路径** (path to logs and property files)
2. **结果输出目录的路径** (path to directory for results)
3. **日志文件的名称** (names of the log file)
4. **模式 (mode)**：（代码中已经固定为clusterall）
* `nonml` — 手动投影 (manual projection)
* `clusterall` — 聚类所有边 (cluster all edges)
* `nonoutlier` — 排除离群点后进行聚类 (cluster without outliers)
* `clusterlocal` — 局部聚类 (local cluster)
* `localtime` — 仅按时间权重进行局部聚类 (cluster locally only by time weight)
* `localamount` — 仅按数据量权重进行局部聚类 (cluster locally only by amount weight)
* `localstruct` — 仅按结构权重进行局部聚类 (cluster locally only by structure weight)
* `fanout` — 仅按扇出值进行局部聚类 (cluster locally only by fanout value)



**示例：**
`./test ./test/result attack.txt`
