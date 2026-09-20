# FastCollection

高性能 Java 集合补充库（Java 17，依赖 [fastutil](https://fastutil.di.unimi.it/)）。

核心数据（JMH，详见下文「性能」）：

- **枚举原始 map（`map.enums`）**：命中读比 JDK `EnumMap` 快 21%、比哈希实现快 **3~4 倍**；计数器 `addTo` 每次调用**零分配**、2 倍于装箱写法；每键仅摊销一个数组槽（int 版 4 B），内存下限。
- **O2X 开放寻址容器（`fastutil`）**：探测三层短路（存储哈希 → 引用相等 → `equals`），同实例重复查询免 `equals`；equals 昂贵 + 大规模下 `containsKey` / `add` / `put` 领先 JDK **9~26%**；原始值版每条目 24 B，比装箱 `HashMap`（44 B）**省 45% 内存**。
- **并发缓存与驻留器（`cache`）**：单表开放寻址，读路径**无锁**（`VarHandle` acquire 探测），写路径以 **CAS 抢占探测序列的首个空闲槽**（写者之间完全并行，只有扩容时取独占）；提供 CHM 没有的身份键、策略键与弱值语义，弱值经周期清扫（扫槽位，不依赖 `ReferenceQueue`）回收，稳态无额外开销。

## 包结构

### `cache` — 线程安全缓存与对象驻留

- **`MapCache<K, V>`**：`putIfAbsent` 语义的并发缓存。除 CHM 基线的 `HashCache`（`computeIfAbsent`：工厂每键恰好执行一次，且不允许同键递归回调）外，各实现的工厂函数都在**所有锁之外**执行——并发下同一键可能被多个线程各算一次，先写入者胜出——因此允许递归回调本缓存解析依赖键；`getCacheRecursive` 是该保证的显式名字。工厂返回 `null` 时不入表，键保持缺失。
- **`Interner<T>`**：线程安全对象驻留器，相等对象收敛为同一规范实例（`HashInterner` 为 CHM 基线）。
- 两个正交维度组合出全部实现：**键语义**（`equals` / 自定义 `Hash.Strategy` / 身份）× **值强度**（强 / 弱引用）。
- 弱引用实现注册到全局 `CacheCleaner` 守护线程（10s 周期清扫）：清扫按实际活条目重建整表、丢掉 referent 已回收的节点，写入路径则直接复用已回收节点的槽位——没有 `ReferenceQueue` 记账。
- 每张表是一段**无锁开放寻址**结构（`OpenCacheTable`）：`volatile Object[] slots`，`mask` 由数组长度派生；读路径以 `VarHandle` 的 acquire 语义探测、不加锁；写入把节点 **compare-and-set 到探测序列的第一个空闲槽**，CAS 的期望值是**本次探测观察到的槽值**，因此竞争者先占槽会让 CAS 失败并重探，任何冲突都不会丢写、也不会重复插入。没有墓碑、也没有删除路径（`MapCache` 不提供 remove）：弱值缓存里已回收节点的槽位由写者直接 CAS 覆盖复用，`sweep()` 在独占 stamp 下按实际活条目数重建整表（丢死条目、活条目放不下才扩容）；表始终保留至少一个从未使用的 `null` 槽，且所有探测都有 `mask` 步上限。扩容先填充新表、再经 volatile 字段发布，且与写者之间用一把 `StampedLock` 排序（写者持共享 stamp，因此彼此并行；只有扩容/清空取独占）写入顺序与 `ConcurrentHashMap` 一致：先在共享 stamp 下**完成插入**，再按装载因子决定是否扩容（只有探测找不到任何空闲槽时才先扩容），这与 `ConcurrentHashMap` 在 transfer 期间阻塞写者是同一个道理。`clearCache()`/`sweep()` 清扫死条目同样无锁。`size` 是近似计数，只用于装载因子；因此**读探测和写探测一样有 `mask` 步数上限**——即便计数少算（它们是普通字段的非原子自增，并发下会丢更新）导致表被节点填满，读也只会走满一圈后返回 miss，绝不死循环（存在的键一定在整圈内被找到，不会误报缺失），而写路径走满一圈则强制扩容自愈。
- 构造函数只保留默认与 `createFunction` 重载：原来的 `concurrencyLevel` 参数**已随分段一并删除**（每张表只有一张表、没有分段可配，参数只会误导），因此这是**源码不兼容**变更；`HashCache` 作为 CHM 基线不变。

### `map` — 嵌套结构样板消除

- **`MultiMap<K, V>`**：`Map<K, Collection<V>>` 的封装，值集合自动创建、空集合自动回收。
- **`NestedMap<K1, K2, V>`** / **`NestedMultiMap<K1, K2, V>`**：两级键嵌套，逐级自动清理；`computeIfAbsent`、`forEach`（`TriConsumer`）等均无中间分配。

### `map.enums` — 枚举键原始类型 map

`Enum2XMap`（X = Int / Long / Double / Float / Boolean / Byte / Char / Short / Object）：

- 实现 fastutil 的 `Reference2XMap` 接口族：原始类型方法（`getInt` / `put(K, int)` / `removeInt` / `addTo` / `computeIfAbsent` / `mergeX` 等）、原始值集合（`IntCollection` / `IntIterator`）、快速迭代器（`FastEntrySet`，复用 entry 零分配）。
- 内部结构参考 JDK `EnumMap`：键 ordinal 直索引单数组，无哈希；原始版以零值标记空槽（存储零值即移除，缺失读返回可配置的 `defaultReturnValue`），对象版以 NULL 哨兵支持 null 值。
- 键全集经 `ClassValue` 缓存共享（每个枚举类只克隆一次，等价 JDK 内部的 `getEnumConstantsShared`）。

### `fastutil` — fastutil 扩展

`O2XOpenCacheHashMap` / `O2XOpenCustomCacheHashMap` / `OpenCacheHashSet` 系列：基于 fastutil 开放寻址 map 的缓存扩展。

## 性能

以下为 JMH 吞吐（ops/µs），单 fork、3×1s 预热、5×1s 迭代，±99.9% 置信区间，10% 以内视为噪声。绝对值仅在同机同 JVM 下有可比性，相对结论跨机成立。复现：`./gradlew jmh -Pbenchmark="<类名>"`，加 `-Pprof=gc` 获得分配数据。

> **环境说明**：`cache` 两张表（并发缓存、对象驻留器）是**单表 CAS 无锁开放寻址（v2）**重构后的实测（2026-09-20，JDK 25，单 fork、3×1s 预热、5×1s 迭代）；`map.enums` / `O2X` / 内存三节仍是 2026-08-22 的数据。同一轮里**未改动的 CHM 基线自身**也在波动（`intern` 128 键 101→103，而 `addIfAbsent` 128 键 86→56），说明本轮 JVM/机器状态与历史数据不同，**两张表内部可比、与其余小节不可直接比**。

### 枚举原始类型 map（`map.enums`）——特长：值全程零装箱

`Enum2IntMap` vs JDK `EnumMap`（装箱）vs fastutil `Reference2IntOpenHashMap`：

| 操作 | 键数 | Enum2IntMap | JDK EnumMap | fastutil |
|---|---:|---:|---:|---:|
| 命中读 `getInt` | 16 | **421** | 344 | 161 |
| 命中读 `getInt` | 64 | **414** | 342 | 97 |
| 缺失读（异构枚举键） | 64 | 507 | **502** | 82 |
| 覆盖写 `put` | 16 | **241** | 232 | 155 |
| 覆盖写 `put` | 64 | **241** | 229 | 93 |
| 累加 `addTo(k, 1)` | 64 | **254** | 126 \* | 94 |
| 全值迭代求和 | 16 | 19.0 | 20.9 | **33.0** |
| 全值迭代求和 | 64 | **17.9** | 14.7 | 12.7 |

\* JDK 无原始累加，以 `put(k, get(k) + 1)` 装箱模拟（现实等价写法）。

每次操作分配（`-prof gc`）：`addTo` —— Enum2IntMap **≈0 B**、fastutil ≈0 B、JDK 装箱写法 **16 B/op**（每次一个新 `Integer`）；命中读三者均 ≈0（JDK 因 `IntegerCache` 掩盖了装箱，值域超出 [-128,127] 后同样每次 16 B）。

原始类型的优势来自 **ordinal 直索引单数组 + 值路径零装箱**：

- 命中读比 JDK `EnumMap` 快约 21%，比哈希实现快 **3~4 倍**——一次数组访问，无哈希、无探测、无分配；
- 计数器模式 `addTo(k, 1)` 读改写一次完成，**2 倍于装箱模拟、2.7 倍于 fastutil**，且每次调用零分配；
- `computeIfAbsent` / `mergeX` / `putIfAbsent` / `replace` 等全部重写为单次数组访问的原始路径（不走 fastutil default 的 `getInt` + `containsKey` 多次探测）；`values()` 的 `IntIterator` 迭代同样零装箱；
- 吞吐与键数几乎无关（定长数组直扫）：fastutil 的迭代从 16 键到 64 键退化 2.6 倍、命中读退化 1.7 倍，`Enum2IntMap` 全程稳定；仅 16 键时 fastutil 的稀疏小表迭代占优（33.0）；
- 结构本身即内存下限：每键摊销一个数组槽（int 版 4 B），无哈希表、条目对象与装箱值。

### 并发缓存（`cache`）

`MapCache` 六实现（128 / 4096 键，ops/µs；单表 CAS 核心的实测，见上方环境说明）：

| 实现 | 读 `getIfPresent` 128 | 4096 | 命中 `getCache` 128 | 4096 | 写 `putIfAbsent` 128 | 4096 | 冷键 `getCache` 128 | 4096 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| HashCache（CHM 基线） | **132** | **67** | 89 | 53 | **74** | **44** | **94** | 40 |
| IdentityHashCache | 91 | 59 | 73 | 63 | 61 | 37 | 61 | 40 |
| CustomHashCache | 72 | 64 | 74 | **69** | 50 | 32 | 56 | 37 |
| WeakValueHashCache | 69 | 63 | 71 | 67 | 44 | 31 | 52 | 39 |
| WeakValueIdentityHashCache | 88 | 54 | **98** | 59 | 52 | 29 | 55 | **42** |
| WeakValueCustomHashCache | 68 | 60 | 73 | 64 | 43 | 33 | 56 | **42** |

- 读路径**无锁**：`VarHandle` acquire 探测开放寻址槽位，与 `ConcurrentHashMap` 的读同量级，同时提供 CHM 没有的身份键、策略键与弱值语义；
- 写路径把节点 compare-and-set 到探测序列的第一个空闲槽，期望值取本次探测观察到的槽值——冲突即重探，不丢写也不重复插入；写者之间完全并行，只有扩容/清空取独占 stamp（见包结构小节）；
- 没有墓碑：死条目（弱值已回收，对象弱值/原始类型弱值/弱驻留器一致）的槽位一律被写者直接 CAS 覆盖复用，两个写者都把它当候选时输的一方重探即看到赢家的活节点，不会双插入；
- 弱值由 `CacheCleaner` 周期清扫（扫槽位即可，不依赖 `ReferenceQueue`）回收，稳态不产生额外清扫开销；
- 单表无分段后，4096 键的读路径明显受益：`CustomHashCache` 的 `getCache` 命中读 **69** 反超 CHM 基线 53，`IdentityHashCache` / `WeakValueHashCache` / `WeakValueCustomHashCache` 也都达到基线的 1.1~1.3 倍；128 键仍以 CHM 基线最优。

### 对象驻留器（`cache`）

`Interner` 四实现（128 / 4096 键，ops/µs）：

| 实现 | `intern` 命中 128 | 4096 | `isPresent` 128 | 4096 | `addIfAbsent` 128 | 4096 |
|---|---:|---:|---:|---:|---:|---:|
| HashInterner（CHM 基线） | **103** | 52 | **164** | **70** | 56 | 40 |
| CustomHashInterner | 66 | 53 | 67 | 53 | **67** | **57** |
| WeakHashInterner | 64 | **54** | 63 | 53 | 65 | 54 |
| WeakCustomHashInterner | 60 | 52 | 66 | 61 | 62 | 56 |

除 CHM 基线的 `HashInterner` 外，三个实现都与缓存共用同一套无锁开放寻址骨架（`OpenCacheTable`），单键路径不再经过分段与拉链：**4096 键时 `addIfAbsent` 反超 CHM 基线 37~45%**（57 / 54 / 56 vs 40），`intern` 与基线持平（101~104%），`isPresent` 为基线的 76~88%；128 键时 `addIfAbsent` 也高出 11~21%，而 `intern` / `isPresent` 分别为基线的 58~64% 与 38~41%（CHM 小表的读命中由 `Node` 数组直接寻址，仍是最快的）。弱引用版本承担弱语义（弱引用 + 清扫/复用死节点槽位），与同族强引用版的差距已在 5% 以内，只多一次弱引用解引用与队列注册；稳态下不产生额外清扫开销。

### O2X 开放寻址缓存容器（`fastutil` 包）

`O2OOpenCacheHashMap` vs JDK `HashMap` vs fastutil `Object2ObjectOpenHashMap`（4096 条，ops/µs；探测为三层短路：存储哈希 → `==` 引用相等 → `equals`）：

| 操作 | 键类型 | O2O OpenCache | JDK HashMap | fastutil Open |
|---|---|---:|---:|---:|
| `containsKey` 命中 | String | 99 | **105** | 82 |
| `containsKey` 命中 | ExpensiveKey | **53** | 49 | 47 |
| `get` 命中 | String | 92 | **103** | 73 |
| `get` 命中 | ExpensiveKey | **50** | 49 | 46 |
| `put` 覆盖 | String | **73** | 60 | 59 |
| `put` 覆盖 | ExpensiveKey | **46** | 38 | 38 |

`OpenCacheHashSet`（4096 条）：`add` 已存在键 —— String **92** vs JDK 58 vs fastutil 85；ExpensiveKey **50** vs 40 vs 48。`contains` —— String 99 vs **105** vs 82；ExpensiveKey **53** vs 49 vs 48 ops/µs。

收益来源是探测链上的三层短路，每层都比 `equals` 便宜得多：

1. **存储哈希先行**（每槽缓存 `hashCode`）：哈希不符直接跳过，`equals` 只在候选槽上执行；rehash 复用存储哈希、免重算；
2. **`==` 引用相等**（同 JDK `HashMap` 的优化）：同一键实例重复查询时免 `equals`——缓存/驻留场景的典型访问模式，实测为此贡献 `get` +53%、`put` +18~22%（相对加入前的同库版本）；
3. 前两层都过才落到 `equals`，equals 昂贵的键（`ExpensiveKey`）与写路径（`put` +20~22%、`add` +26%）对 JDK 的优势最大；`String` 小规模下 JDK 凭短链与内联仍略领先。

`O2I` / `O2L` / … 系列同时继承 fastutil 全套原始方法，无装箱。

### 内存占用

结构常驻（`MapFootprintBenchmark`，`-prof gc` 构造并填充 1024 条的分配归一化，键值预分配只测结构本身）：

| 实现 | 每条目占用 | 相对 JDK HashMap |
|---|---:|---:|
| fastutil Object2IntOpenHashMap（原始值） | 16 B | -64% |
| **O2IOpenCacheHashMap**（原始值 + 缓存哈希） | **24 B** | **-45%** |
| JDK ConcurrentHashMap\<Integer, Integer\> | 40 B | -11% |
| JDK HashMap\<Integer, Integer\>（装箱） | 44 B | 基线 |

- 原始值是最大的内存杠杆：装箱 `Integer` 值每条目 16 B，直接把 JDK map 推高 80%+；
- O2X 的缓存哈希每条目占 8 B，是"跳过 `equals`"的直接代价——吞吐与内存可按场景取舍；
- `Enum2XMap` 不在此表内（数组定长语义不同）：每键摊销一个数组槽（int 版 4 B）+ 实例头，无哈希表无装箱，是所有实现中的内存下限；
- 操作级分配：`Enum2XMap` 全部原始路径实测 ≈0 B/op（对照 JDK 装箱写法 16 B/op，见枚举小节）。

## 构建与测试

```bash
./gradlew test                                   # 全量测试
./gradlew jmh -Pbenchmark="CacheBenchmark"       # JMH 基准（另有 InternerBenchmark 等）
./gradlew spotlessCheck                          # 格式检查（spotlessApply 应用）
```
