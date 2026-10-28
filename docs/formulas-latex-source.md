# 技术报告公式 LaTeX 源码台账

下列公式均由 LaTeX 源表达式转换为 DOCX 中的 Word 原生 OMML 公式对象；PDF 由该 DOCX 渲染生成。

## 式（3-1）

```latex
\mathrm{Commit}(y,u)=\mathrm{HumanConfirm}(y)\land\mathrm{ValidVersion}(y)\land\mathrm{ACL}(u,y)\land\mathrm{BusinessValid}(y)
```

其中y为候选行动，u为当前主体；四项分别表示人工确认、来源版本有效、主体具备操作权限以及业务字段与客户状态有效。任一条件为假时不得提交；该式不表示所有拒绝事件均已写入审计。

## 式（3-2）

```latex
D(u,c)=\{d\in D_{\mathrm{all}}:\mathrm{org}(d)=\mathrm{org}(u)\land\mathrm{customer}(d)=c\land\mathrm{ACL}(u,d)=1\}
```

其中u为当前主体，c为目标客户，d为候选证据，全部证据条目集合记为D_all；org与customer返回证据所属组织和客户，ACL(u,d)=1表示主体可读。三项同时成立的条目才进入D(u,c)。

## 式（3-3）

```latex
C(u,c,q)=\mathrm{Dedup}(\mathrm{Recent}_{m}(D(u,c))\cup\mathrm{TopK}_{\mathrm{sem}}(q,D(u,c)))
```

其中q为检索查询，m为近期记录上限，Recent选取近期记录，TopK_sem在同一授权域内返回语义相关的前K项，Dedup按sourceKey合并重复来源；C(u,c,q)是最终证据包。

## 式（3-4）

```latex
\mathrm{Legal}(y)=1\iff\mathrm{Citations}(y)\subseteq\mathrm{Keys}(C(u,c,q))
```

其中Citations(y)为输出引用键集合，Keys为当前证据包允许集合。Legal(y)=1只说明来源键未越界，不说明被引文本必然支持相邻陈述；内容支持性由Grounded F1和UCR另行评价。

## 式（3-5）

```latex
k=(\mu,\eta,\tau,\iota),\quad \mathrm{Count}(\mathrm{McpRecord},k)\leq 1
```

其中μ、η、τ、ι分别为membershipId、clientId、toolName和idempotencyKey。记录有效窗口内，同一k至多存在一条幂等记录；同键异argumentsHash返回冲突，PENDING重放返回MCP_REQUEST_IN_PROGRESS，成功重放复用已存结果。该式不承诺外部系统副作用的exactly-once。

## 式（5-1）

```latex
\mathrm{Recall}@K=\frac{\mathrm{card}(R\cap T_{K})}{\mathrm{card}(R)},\quad \mathrm{card}(R)>0
```

其中标注相关证据集合记为R，T_K为返回的前K项；只对card(R)>0的用例计算。K值、聚合方式和样本数随冻结配置报告。

## 式（5-2）

```latex
\mathrm{UCR}=\frac{N_{\mathrm{unsupported}}}{N_{\mathrm{all}}},\quad N_{\mathrm{all}}>0
```

其中N_unsupported为按预定义规则判定为无证据支持的陈述数，N_all为全部可判定陈述数；冻结评测必须公开切分规则、判定准则、分子和分母。
