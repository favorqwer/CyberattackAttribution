

## 1. 它为什么要做 CTI Denoising

作者认为，真实世界里的 CTI 报告和训练时合成出来的 synthetic CTI 很不一样：

* synthetic CTI 比较干净、结构清晰；
* 真实 CTI 往往很长，包含很多噪声，比如网页元数据、广告、背景说明、组织介绍等；
* 这些噪声会掩盖真正和攻击行为有关的线索。

论文把这个问题定义成 Challenge C3：
**CTI 报告太 noisy、太 verbose，妨碍 threat hunting 利用其中的攻击知识。** 

所以 CTI denoising 的目标不是“让报告更好读”，而是：

**让报告更适合后续检索、匹配、威胁狩猎。** 

---

## 3. 它具体做什么：三阶段 CoT 流程

论文正文 4.3 节把 CTI denoising 分成 3 个推理阶段：

### 第一阶段：Entity Identification

先扫描整篇 CTI，抽取核心系统实体。
作者给的例子是恶意 payload，但从附录 prompt 看，真正保留的实体被严格限制为三类：

* **Processes**
* **Files**
* **Sockets**

这一步非常关键，因为它相当于在长篇报告里先做“实体过滤”：

* 保留和系统行为直接相关的对象；
* 丢掉 threat actor 名字、组织名、API 名、背景术语等无关实体。

也就是说，这一步的核心不是“尽量提取更多信息”，而是**只保留后续能和 provenance graph 对齐的对象**。

---

### 第二阶段：Interaction Extraction

有了实体之后，第二步是找这些实体之间的行为关系。

论文正文说，这一步不仅抽取**显式**交互，还会根据上下文推断**隐式**交互。

附录 prompt 又把这一过程进一步规范化了：

* 以 process 为主语；
* 从前一步识别出的对象中找它的作用对象；
* 把动作映射到一个固定集合里：
  `read, write, connect, send, receive, create, delete, modify, clone, execute`
* 最终形成明确的 **Subject-Action-Object** 行为三元组。

这一步本质上是在做：

**自然语言攻击报告 → 行为图式表示**

例如一大段“攻击者上传 plink.exe 建立端口转发，再用 nc.exe 传输文件”的文字，经过这一步后，会被压成若干行为三元组，如：

* process A execute plink.exe
* process A connect 202.6.172.98
* process B send allgone.zip

论文没有在正文里列出完整实例输出，但从 prompt 设计和 Figure 1 的例子可以看出，它想要的就是这种“行为链化”的结果。

---

### 第三阶段：Knowledge Distillation

最后一步不是简单把前两步结果罗列出来，而是要把这些交互**整合成简洁、按时间排序的攻击叙事**。

这里有两个重点：

**第一，要求 concise。**
也就是只保留攻击相关的核心行为，不保留冗余背景。

**第二，要求 temporally ordered。**
也就是把动作按照攻击发生顺序组织起来，形成“攻击链”。

附录 prompt 甚至规定了输出形式：
最后要写成一个 summary sentence，把同一进程的多步动作串起来，例如：

> Process [Name] [Action] [Object], [Action] [Object], and [Action] [Object].

所以最后的产物不是普通摘要，而是：

**一种高度压缩、时间有序、以行为为中心的攻击摘要。**

---

## 4. 为什么它要用 CoT，而不是一句“请总结一下”

这是论文里很重要的点。

作者没有把任务交给 LLM 做一个黑箱 summary，而是显式使用了 **Chain-of-Thought reasoning framework**，把 denoising 分解成多个逻辑子任务，模拟 threat analyst 的分析流程。

这么做有几个明显好处：

### 4.1 降低自由生成带来的漂移

如果只说“总结这篇 CTI”，模型可能会输出：

* 攻击背景
* 组织信息
* 威胁组织介绍
* 漏洞背景
* 防御建议

但这些对 provenance graph 匹配并不一定有用。
拆成三步后，模型被强制聚焦在：

**实体 → 行为 → 攻击链**

这个路径上。

### 4.2 让输出更接近图匹配需求

后续匹配对象不是“文章主题”，而是系统行为图。
因此，真正重要的是：

* 出现了哪些进程/文件/IP
* 它们之间发生了什么关系
* 顺序是什么

CoT 的三阶段设计，正好对齐这个需求。

### 4.3 更像人工分析师工作流

作者明确说，这个设计是在**模拟威胁分析师的分析流程**。
人工看 CTI 时也通常是：

先找对象，再找行为，再拼攻击链。

---

## 5. Prompt 其实约束得非常死

附录 C 和 Figure 7 显示，这个 prompt 不是泛泛而谈，而是强约束模板。

### 5.1 只允许三种实体

* Processes
* Files
* Sockets

并明确要求忽略：

* organizations
* threat actors
* API names
* general terms

这意味着它故意牺牲了很多“情报文本”的丰富语义，换来和系统图更直接的一致性。

### 5.2 动作集合是封闭的

动作必须映射到固定列表：

`read, write, connect, send, receive, create, delete, modify, clone, execute` 

这会带来一个重要效果：
不同写法的自然语言会被归一化成统一动作空间。比如：

* “downloaded”
* “retrieved”
* “fetched”

都可能被规约到某个统一行为类型里。

### 5.3 输出格式也是固定的

模型必须清楚输出：

* Step 1 的实体
* Step 2 的 triplets
* Step 3 的 summary sentence

所以这实际上是一种**结构化摘要**，而不是自由文本摘要。

---

## 6. 去噪后得到的结果长什么样

从论文描述推断，去噪后的结果有三层含义：

### 第一层：删噪

去掉网页模板、广告、背景知识、组织介绍等无关内容。

### 第二层：抽行为

把冗长叙述里的关键攻击动作抽出来，转成 process-file-socket 三元组。

### 第三层：串攻击链

把离散动作按时间逻辑整理成简洁叙事，供后续文本编码器和 multimodal encoder 使用。

所以你可以把 denoised CTI 理解为：

**“面向 provenance graph 对齐的行为版 CTI”**

而不是给人阅读的情报摘要。

---

## 7. 它和后续 threat hunting 怎么衔接

去噪后的 CTI 报告会进入两阶段 threat hunting：

### 第一阶段：粗粒度检索

用 text encoder 把每篇 CTI 编码成向量，存入向量库。
给定一个 provenance graph，先算它的 graph embedding，再从 CTI 库里召回 top-k 候选。

### 第二阶段：细粒度匹配

把召回的 CTI 候选和图一起送入 multimodal encoder，做更细粒度的 joint representation 和匹配判断。

CTI denoising 的意义就在于：
**让文本编码器和后续 cross-attention 看到的是“高密度攻击语义”，而不是网页噪声。**

否则第一阶段召回就可能偏掉，第二阶段 cross-modal matching 也会受噪声干扰。

---

## 8. 这个模块的实验效果

论文做了专门的消融实验：
把 CTI denoising 去掉，直接使用 noisy CTI report，和完整 APT-CGLP 比较。结果显示：

**denoising 在所有数据集上都让 Recall 提升超过 30%。** 

作者给出的解释是：

* 噪声会显著阻碍 CTI 的利用；
* denoising 提炼出了 concise、attack-relevant 的描述；
* 因而 precision 和 robustness 都得到了提升。

这个结果说明，CTI denoising 不是一个“可有可无”的小优化，而是整个系统性能提升的重要来源。

---

## 9. 这模块的优点和局限

### 优点

它最大的优点是**把开放式长文本问题，约束成了半结构化行为抽取问题**。
这让 LLM 更稳，也更贴合 downstream graph-text matching。

### 局限

但它也有明显代价：

#### 9.1 信息压缩可能过度

只保留 process/file/socket，很多攻击上下文会丢掉。
例如攻击者身份、漏洞名、战术意图，虽然对 analyst 有用，但被主动忽略了。

#### 9.2 动作集合有限

现实 CTI 里的行为远比 `read/write/connect/...` 丰富。
把复杂描述强行映射到固定动作集合，可能导致语义损失。

#### 9.3 仍有 hallucination 风险

论文在 discussion 中承认，LLM 仍然可能 hallucinate；只是作者认为由于任务比较封闭、偏理解型，这种风险相对可控。

---

## 10. 一句话概括 CTI Denoising

你可以把它理解成：

**用 LLM 把“面向人阅读的长篇威胁报告”，转换成“面向系统行为匹配的攻击行为摘要”。**

它不是普通 summarization，而是：

**实体筛选 + 行为抽取 + 攻击链蒸馏**。

如果你愿意，我下一条可以继续给你讲
**“这个 prompt 为什么能和 provenance graph 对齐起来”**，我可以直接把 CTI denoising 的输出和图里的节点/边一一对应地解释。
