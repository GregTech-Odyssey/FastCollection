# FastCollection

高性能 Java 集合补充库（Java 17，依赖 [fastutil](https://fastutil.di.unimi.it/)）。

核心数据（JMH，详见下文「性能」）：

- **枚举原始 map（`map.enums`）**：命中读比 JDK `EnumMap` 快 21%、比哈希实现快 **3~4 倍**；计数器 `addTo` 每次调用**零分配**、2 倍于装箱写法；每键仅摊销一个数组槽（int 版 4 B），内存下限。
- **O2X 开放寻址容器（`fastutil`）**：equals 昂贵的键 + 大规模下 `containsKey` / `add` **领先 JDK 40~45%**；原始值版每条目 24 B，比装箱 `HashMap`（44 B）**省 45% 内存**。
- **并发缓存与驻留器（`cache`）**：分段 `StampedLock` 读路径与 `ConcurrentHashMap` 同量级，写路径反超（111 vs 81 ops/µs）；弱引用清理写入路径顺手完成，稳态零开销。

## 包结构

### `cache` — 线程安全缓存与对象驻留

- **`MapCache<K, V>`**：`putIfAbsent` 语义的并发缓存。`getCache` 的工厂函数在写锁内执行（每键恰好一次）；`getCacheRecursive` 的工厂函数在所有锁之外执行，允许递归回调本缓存解析依赖键。
- **`Interner<T>`**：线程安全对象驻留器，相等对象收敛为同一规范实例。
- 两个正交维度组合出全部实现：**键语义**（`equals` / 自定义 `Hash.Strategy` / 身份）× **值强度**（强 / 弱引用）。
- 弱引用实现注册到全局 `CacheCleaner` 守护线程（10s 周期清扫死条目），写入路径同时顺手清理。
- 分段结构（`StampedLock` 段 + 链地址表）的公共骨架提取在 `Segmented` / `HashSegment` / `ChainNode`：冷路径（resize、clear、清扫）共享，热路径（探测与写入循环）留在各实现内以避免多态派发开销。

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

以下为 2026-08-22 全量回归数据：JMH 吞吐（ops/µs），单 fork、3×1s 预热、5×1s 迭代，±99.9% 置信区间，10% 以内视为噪声。绝对值仅在同机同 JVM 下有可比性，相对结论跨机成立。复现：`./gradlew jmh -Pbenchmark="<类名>"`，加 `-Pprof=gc` 获得分配数据。

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

`MapCache` 六实现（128 / 4096 键，ops/µs）：

| 实现 | 读 `getIfPresent` 128 | 4096 | 命中 `getCache` 128 | 4096 | 写 `putIfAbsent` 128 | 4096 | 冷键 `getCache` 128 | 4096 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| HashCache（CHM 基线） | **175** | **103** | **106** | **74** | 81 | 59 | **138** | 60 |
| IdentityHashCache | 84 | 65 | 83 | 63 | 97 | **68** | 82 | 55 |
| CustomHashCache | 94 | 70 | 80 | 68 | **111** | 61 | 70 | 61 |
| WeakValueHashCache | 88 | 65 | 68 | 61 | 101 | 54 | 73 | 59 |
| WeakValueIdentityHashCache | 80 | 60 | 78 | 60 | 88 | 52 | 69 | 50 |
| WeakValueCustomHashCache | 91 | 64 | 81 | 57 | 91 | 60 | 71 | 60 |

- 分段 `StampedLock` 实现的读路径与 `ConcurrentHashMap` 同量级（共享读锁、读间无竞争），同时提供 CHM 没有的身份键、策略键与弱值语义；
- 写路径上自研分段实现反超 CHM（`CustomHashCache` 111 vs 81）：putIfAbsent 语义免装箱检查、段锁粒度更细；
- 弱值版本相比同族强值版读路径仅低 3~9%（`get` 后多一次弱引用解引用）；
- 4096 键时各实现收敛到 50~74（缓存效应主导，锁开销已不是瓶颈）。

### 对象驻留器（`cache`）

`Interner` 四实现（128 / 4096 键，ops/µs）：

| 实现 | `intern` 命中 128 | 4096 | `isPresent` 128 | 4096 | `addIfAbsent` 128 | 4096 |
|---|---:|---:|---:|---:|---:|---:|
| HashInterner（CHM 基线） | **146** | **71** | **241** | **113** | **108** | 62 |
| CustomHashInterner | 83 | 50 | 80 | 71 | 106 | **82** |
| WeakHashInterner | 78 | 49 | 81 | 65 | 94 | 67 |
| WeakCustomHashInterner | 53 | 40 | 81 | 65 | 93 | 68 |

弱引用版本承担弱语义（死节点探测 + 写路径顺手清理），`isPresent` 与强引用版持平，`intern` 低 12~36%；稳态下不产生额外清扫开销。

### O2X 开放寻址缓存容器（`fastutil` 包）

`O2OOpenCacheHashMap` vs JDK `HashMap` vs fastutil `Object2ObjectOpenHashMap`（4096 条，ops/µs）：

| 操作 | 键类型 | O2O OpenCache | JDK HashMap | fastutil Open |
|---|---|---:|---:|---:|
| `containsKey` 命中 | String | **87** | 71 | 72 |
| `containsKey` 命中 | ExpensiveKey | **49** | 33 | 34 |
| `get` 命中 | String | 60 | **69** | 60 |
| `get` 命中 | ExpensiveKey | **32** | 33 | 33 |
| `put` 覆盖 | String | **60** | 55 | 57 |
| `put` 覆盖 | ExpensiveKey | **39** | 35 | 36 |

`OpenCacheHashSet`（4096 条）同场景 `add` 已存在键：String **82** vs JDK 53 vs fastutil 84；ExpensiveKey **52** vs 36 vs 46 ops/µs。

每槽缓存键哈希的收益随「equals 成本 × 探测链长度」放大：equals 昂贵的大规模场景下 `containsKey` / `add` **领先 40~45%**，String 场景 `containsKey`/`put` 亦领先 8~20%；纯 `get` 小规模下 JDK 凭短链与内联优化领先。`O2I` / `O2L` / … 系列同时继承 fastutil 全套原始方法，无装箱。

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
