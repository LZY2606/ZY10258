# 优化语义见证台 (Optimization Semantics Witness Bench)

对受限 SSA 片段的重写前后做**有界符号验证**，并生成可重放反例的本地服务。
技术栈：Kotlin + Ktor + SQLite + 单页 Web UI。

## 构建与运行

```bash
# 安装（编译打包）
mvn -q -DskipTests package

# 演示（先跑测试，再启动服务）
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5598'
# 打开 http://127.0.0.1:5598 ，页面标题为「优化语义见证台」
```

可选参数：`--port <端口>`（默认 8080）、`--db <路径>`（默认 `data/witness.db`，`:memory:` 表示纯内存）。

## 数据口径（语义约定）

- **固定位宽整数**：所有整数按选定位宽（8/16/32/64）取模 2^w 环绕；常量解析同样按位宽截断
  （如 `const 256` 在 `i8` 下为 0），不借助宿主大整数语义偷换环绕。
- **溢出旗标**：`nsw`/`nuw` 在「遵守」模式下溢出即产生**毒化值 (poison)**；「忽略」模式下按环绕处理。
- **毒化值**：超宽移位（移位量 ≥ 位宽）、带旗标溢出都会产生 poison；poison 经大多数运算传播，
  `select` 未选中的臂不传播，`freeze` 把 poison 变为确定性的任意值（本实现取 0），
  对 poison 分支或返回 poison 视为**未定义行为 (UB)**。
- **浮点**：`STRICT` 模式输入域包含 NaN、±Inf、±0、次正规数；`FINITE_ONLY` 仅有限值。
  浮点结果按位比较（`-0.0 ≠ +0.0`，NaN 与 NaN 视为相等）。
- **循环**：按块访问次数计**展开上限**，超限该输入记为 unknown，绝不算作等价。

## 求解状态（严格互斥）

| 状态 | 含义 |
|---|---|
| `PROVEN_EQUIVALENT` 已证明等价 | 输入空间在预算内**穷举**完毕且无失配、无 unknown |
| `COUNTEREXAMPLE` 发现反例 | 存在输入使两侧按精炼口径不一致；附输入、路径条件、中间值、两侧结果 |
| `RESOURCE_LIMIT` 资源上限（未知） | 空间过大只能采样、预算耗尽或展开上限命中 —— **unknown 与等价严格分开** |
| `UNMODELED` 语义未建模 | 片段含未建模操作（如外部调用），不猜测其语义 |

## 翻译精炼口径

只在**两侧都有定义**的输入上比较结果；一侧未定义时按所选口径判定，绝不把任意值当作反例：

- `TARGET_REFINES_SOURCE`（目标精化源，默认）：源未定义的输入不约束目标；
  源有定义时目标必须有定义且相等，否则为反例（`target-ub` 或 `value-mismatch`）。
- `STRICT_BOTH_DEFINED`（双侧均有定义）：任一侧未定义而另一侧有定义即为反例
  （`source-ub` / `target-ub`）。

## 反例与缩减

反例包含：输入（固定位宽原始编码）、路径条件（块级轨迹与分支方向）、按执行顺序的中间值、
两侧结果与 UB 原因。**缩减**在保持同类型失配的前提下把输入贪婪地替换为更简单的值；
原始反例始终保留（运行记录中 `originalCe` 与 `minimizedCe` 并存）。

## 重放方式

1. **页面重放**：在「运行记录」中查看历史配置，用相同配置再次「验证全部重写对」即可复现
   （验证是确定性的：穷举顺序固定，采样使用固定种子）。
2. **导出/导入复核**：「导出 JSON」下载全部运行记录；「清空数据库」后「导入 JSON」
   即可原样恢复，用于跨环境复核。也可直接调用 API：
   - `GET /api/export` → 导出全部运行记录
   - `POST /api/import`（请求体为导出的 JSON）→ 重新导入
   - `POST /api/reset` → 清空
   - `POST /api/verify` `{"pairId":"all","config":{...}}` → 运行验证并入库

## 固定 fixture

| id | 重写对 | 期望结论（i8 默认口径） |
|---|---|---|
| `add-double` | `x+x ⇒ x<<1` | 已证明等价 |
| `inc-cmp-wrap` | `icmp sgt (x+1), x ⇒ true` | 反例（x=127 环绕） |
| `inc-cmp-nsw` | 同上但 `add nsw` | 等价（源 UB 输入不约束目标）；忽略旗标时退化为反例 |
| `overshift-select` | 移位守卫 ⇒ 掩码移位 | 反例（k=位宽 超宽移位） |
| `poison-select-and` | `select c,x,false ⇒ and c,x` | 反例（目标传播毒化） |
| `fcmp-self` | `fcmp oeq f,f ⇒ true` | 严格模式反例（NaN）；仅有限值时等价 |
| `fadd-zero` | `fadd f,+0.0 ⇒ f` | 反例（f=-0.0） |
| `ext-call` | 外部调用 ⇒ 参数 | 语义未建模 |
| `loop-count` | 计数循环 ⇒ 返回 n | 展开上限不足时资源上限；足够时已证明等价 |

## 项目结构

```
src/main/kotlin/app/ir/       IR 定义与具体语义求值器（poison/UB/环绕/NaN）
src/main/kotlin/app/verify/   有界验证器、输入域枚举/采样、反例缩减
src/main/kotlin/app/fixtures/ 固定重写对（按位宽参数化）
src/main/kotlin/app/db/       SQLite 持久化（运行记录、导出/导入/清空）
src/main/kotlin/app/server/   Ktor HTTP API
src/main/resources/web/       单页操作界面
src/test/kotlin/app/          语义、验证器、持久化与端到端测试
```
