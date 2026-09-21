# Create Package Innovation — 技术文档

本文档记录模组的技术架构、实现方式、开发过程中遇到的关键陷阱，以及后续可能的演进方向。
面向读者：想接手开发的贡献者、想学习"如何用 Mixin 修改 Create 模组行为"的开发者。

> **本文档与上游的关系（先读这一段）**
>
> 本模组移植自 [god-damn-repackager](https://github.com/cshawny/god-damn-repackager)（MIT，作者 cshaw），
> 但**不是**它的文档副本。本文每一句都描述本仓库 `src/main/java/**` 里**当前**实际存在的代码。
> - 上游的技术文档（下文称"上游 TECHNICAL.md"）是**只读参考**，本地路径
>   `..\github_repository\god-damn-repackager\TECHNICAL.md`。**永远不要**修改那个仓库。
> - **§ 编号契约**：本仓库代码注释里的 `§x.y` 引用最初指向**上游**的编号。本文档**保留上游的编号与主题**
>   （§2.2 / §3.1 / §3.5 / §3.7 / §3.8 / §3.9①~⑦ / §3.10 / §3.11 / §4.1 / §4.3 / §5 / §6），
>   使既有注释的引用继续有效；上游**没有**的主题一律追加为**新编号**（§3.12 起、§2.5、§2.6），
>   **绝不重排已有编号**。对照表见 §6.4。
> - 凡是本模组**刻意偏离上游**的地方，都在对应小节里用
>   "**⚠️ 与上游分歧（不要改回去）**"标出，并写明为什么改回去会出事。
> - 证据约定：本文引用 `Class.method`、擦除描述符、字节码偏移与 `javap` 输出。**没有实际验证过的
>   偏移/描述符一律不写**；无法从本机源码或字节码求证的事实，显式标注"**未验证**"。

---

## 1. 问题背景：Create 6.0 的理包机瓶颈

### 1.1 物流链路

Create 6.0 引入了全新的"包裹物流系统"。一个典型的自动化合成阵列长这样：

```
[总仓库]
   │  (玩家通过 Stockkeeper 下合成订单)
   ▼
蛙港(Frogport) ──把原料打成"碎片包裹"──> [输入保险库 Vault]
                                            │
                                  ┌─────────┼─────────┐
                                  ▼         ▼         ▼
                              理包机A    理包机B    理包机C   (Repackager, 红石块常开)
                                  │         │         │
                              整理成"一次合成所需的有序包裹"
                                  │         │         │
                              打包机(拆包) → 动力合成器 → 产物
```

理包机（`RepackagerBlockEntity`）的职责：**从容器抽出碎片包裹，按合成配方把它们重新
整理成"正好一份合成所需原料"的有序包裹**（例如合成箱子 = 8 个木板一个包裹），然后逐个发出。

### 1.2 瓶颈在哪

原版行为：**整张订单的碎片被"抢到"它的那一个理包机独占**，它一次性把整张订单 repackage 成
几十/几百个有序包裹，全部塞进自己的 `queuedExitingPackages` 队列，然后**每秒只发出 1 个**
（发送动画周期 20 ticks = 1 秒）。

所以：1000 个合成的订单 = 1000 秒，无论你挂了多少台机器。

> **关键澄清**：瓶颈不在"抢碎片"（碎片进入容器后几乎瞬间被某台机器抽走），而在
> **"一台机器慢慢消化它的输出队列"**。这个结论是本项目（及其上游）最费功夫得到的——上游文档
> §3.3 记录了验证过程。

### 1.3 本模组做的事

| 特性 | 一句话 |
|---|---|
| 共享包裹池 | 整理好的整批包裹不再进机器私有队列，而是进世界级 SavedData，按**容器身份**分桶；空闲机器每 tick 取 1 个发 |
| 部分重组 | 不等订单碎片到齐，已到碎片够合成至少一次就开工；余料托管存档，容器拆掉时如数爆出 |
| 全容器支持 | 身份判定不绑死具体类：多方块（保险库 / Item Silo / 流体罐）、网络型存储（Create: Storage）、任意单方块容器都能池化 |
| 漏抽兜底 | "池键最后出现在哪"的提示随存档持久化，重启后仍能判定容器是否还在，把漏抽的池爆成掉落物 |

---

## 2. 模组架构

### 2.1 代码结构

```
src/main/java/com/frnc/create_package_innovation/
├── CreatePackageInnovation.java   # @Mod 主类：MOD_ID / LOGGER / DEBUG_LOGGING（根包只留这一个入口类）
│
├── identity/                      # ★ 容器身份：把"哪个容器"解析成一个稳定的池键
│   ├── VaultIdentity.java         # 身份分派：三类容器 → 三条身份路线
│   ├── VaultGeometry.java         # 邻居/连通性扫描 + 区块安全存活探测(Liveness 三态)
│   ├── VaultExtraData.java        # record 载体：UUID 穿过 Create 的 Object 型 extraData 通道
│   ├── ContainerIdSupport.java    # ★ 容器无关的稳定 UUID 逻辑（extraData 三件套 / notifyMultiUpdated / NBT 读写）
│   ├── ContainerHintRegistry.java # 内存 half："池键最后出现在哪" + 是否多方块
│   ├── ContainerHintStore.java    # 磁盘 half：SavedData(gdr_container_hints)，独立文件
│   ├── VaultIdAccessor.java       # duck interface：读/写 BE 上的稳定 UUID
│   ├── NetworkAnchorAccessor.java # duck interface：网络锚点位置
│   └── FluidTargetAccessor.java   # duck interface：机器的流体存储位置
│
├── pool/                          # ★ 共享包裹池本身
│   ├── SharedPackagePool.java     # 世界级 SavedData(gdr_shared_package_pool)：per-容器共享包裹池，key=UUID
│   └── OrphanSweep.java           # @Mod.EventBusSubscriber：ChunkEvent.Load 时回收容器已消失的池
│
├── partial/                       # ★ 部分重组（订单接管 + 余料托管）
│   ├── PartialRepackager.java     # 部分重组核心：零副作用预扫描 + 部分趟 + 末班 vanilla repack() 收尾
│   └── PartialOrderTracker.java   # 世界级 SavedData(gdr_partial_order_tracker)：被接管订单的余料/消费进度/上下文
│
└── mixin/
    ├── RepackagerBlockEntityMixin.java    # @Inject attemptToRepackage HEAD（部分重组入口）
    │                                      # + @Redirect 其 List.addAll（整批入池）
    ├── PackagerBlockEntityMixin.java      # @Inject tick HEAD（接手私有积压 + 空闲取 1）
    │                                      # + @Redirect attemptToSend 的 List.add（打包机直接入池）
    ├── ConnectivityHandlerMixin.java      # @Inject ConnectivityHandler.splitMulti，**TAIL**
    ├── ItemVaultBlockEntityMixin.java     # 保险库适配器（extraData 三件套 + NBT + notifyMultiUpdated）
    ├── FluidTankBlockEntityMixin.java     # 流体罐适配器（**故意只有 NBT 两个 hook**，见 §3.18）
    ├── LevelChunkRemovalMixin.java        # 单方块容器移除时爆池（**唯一可 remap 的 mixin**）
    ├── PackageRepackageHelperInvoker.java # @Invoker：桥接 protected repackBasedOnRecipes
    └── compat/                            # 只有这里能出现可选模组的类
        ├── ItemSiloBlockEntityMixin.java        # Create: Connected 纵向保险库
        ├── StorageNetworkIdentifierMixin.java   # Create: Storage 网络锚点
        └── FluidPackagerBlockEntityMixin.java   # Create: FluidLogistics 流体打包机

src/main/resources/
├── create_package_innovation.mixins.json          # 主配置（required=true, defaultRequire=1）
├── create_package_innovation.compat.mixins.json   # 兼容配置（required=false, defaultRequire=0）
├── META-INF/mods.toml                             # 占位符由 processResources 展开
├── pack.mcmeta
└── Logo.png
```

**两个 mixin 配置的分工（不要合并）**

| | 主配置 | 兼容配置 |
|---|---|---|
| `required` / `defaultRequire` | `true` / `1` | `false` / `0` |
| 目标 | Create 与**原版**（`LevelChunk`） | 可选模组 |
| 注入失败时 | **启动期硬崩** | 只打警告 |

主配置**必须**硬失败：它含 `LevelChunkRemovalMixin`。若它注不进去，池照常工作但**没有任何东西会在
单方块容器被移除时把池爆出来**——物品静默丢失。宁可在启动时崩掉。兼容配置相反：没装 Create: Connected
的玩家不该因为一个可选模组启动失败。

> 新增 mixin 时**必须**同时登记到对应 json 的 `mixins` 数组，否则它编译进包但运行时从不触发
> （症状与 §3.1 完全一样）。

### 2.2 注入点总表

本模组共 **6 个注入 + 1 个 @Invoker**，分布在 7 个 mixin 类里：

| # | 注入 | 目标（擦除后） | 用途 |
|---|---|---|---|
| 1 | `@Inject(HEAD, cancellable)` | `RepackagerBlockEntity.attemptToRepackage(IItemHandler)V` | **部分重组入口**：零副作用预扫描；仅当部分/末班趟真的消费了碎片并产出包裹时才 `ci.cancel()`。完整订单 / 直通包裹 / 无可合成一律放行 vanilla |
| 2 | `@Redirect`（`List.addAll`，`ordinal = 0`） | 同上方法的 `List.addAll(Collection)Z` | **理包机整批入池**：winner repack 后不再填自己的 `queuedExitingPackages` |
| 3 | `@Inject(HEAD)` | `PackagerBlockEntity.tick()V` | **策略 A**：① 把该机私有积压整体交给池；② 空闲时从池取 1 个进私有队列。**注父类**，无 `instanceof` 守卫（见 §3.9①、§3.18） |
| 4 | `@Redirect`（`List.add`，**故意不写 ordinal**） | `PackagerBlockEntity.attemptToSend(List)V` | **普通打包机直接入池**：上游没有这个注入点 |
| 5 | `@Inject(TAIL)` | `ConnectivityHandler.splitMulti(BlockEntity)V` | **容器全拆时爆池**：先判 partial vs 全拆，只有确认全拆才 `drainAndDrop` |
| 6a | `@Inject(HEAD)` | `ItemVaultBlockEntity.notifyMultiUpdated()V` | 合裁决 + 惰性铸 UUID |
| 6b | `@Inject(RETURN)` ×2 | `ItemVaultBlockEntity.write/read(CompoundTag,Z)V` | UUID 的 NBT 持久化 |
| — | （extraData 三件套覆写） | `getExtraData/setExtraData/modifyExtraData` | 非 `@Inject`，是**接口方法覆写**（见 §3.11、§3.9⑨） |
| 7 | `@Invoker` | `PackageRepackageHelper.repackBasedOnRecipes(...)` | 部分趟调 vanilla 部分合成（`protected`，已 `javap -p` 确认为 `protected`） |

**兼容配置里的 3 个**（注入失败只警告）：

| 注入 | 目标 | 用途 |
|---|---|---|
| 全套（同 6a/6b + extraData 三件套） | Create: Connected `ItemSiloBlockEntity` | 纵向保险库适配器 |
| 纯覆写（无 `@Inject`） | Create: Storage `StorageNetworkIdentifier` | 暴露网络锚点位置 |
| `@Shadow` 字段 + 覆写 | Create: FluidLogistics `FluidPackagerBlockEntity` | 暴露 `fluidTarget` 面对的位置 |

**流体罐适配器**（`FluidTankBlockEntityMixin`）只做 `write`/`read` 两个 `@Inject`——**故意少两样**，理由见 §3.18。

> **为什么 `tick` 注入点在父类 `PackagerBlockEntity` 而非 `RepackagerBlockEntity`**：
> 已 `javap` 确认 `RepackagerBlockEntity` 的方法表里**没有** `tick()`（只有
> `unwrapBox` / `recheckIfLinksPresent` / `redstoneModeActive` / `attemptToSend` / `attemptToRepackage`），
> 它继承父类的 `tick`。Mixin 解析注入目标时按目标类**自身**的方法表查找，所以
> `@Mixin(RepackagerBlockEntity.class) @Inject(method="tick")` **无法注入**。详见 §3.9①。

所有注 Create 类 / 第三方模组类的 mixin 都带 `remap = false`（它们自己的名字从不被混淆）；
**只有 `LevelChunkRemovalMixin` 例外**——它注的是原版类，refmap 必须把
`removeBlockEntity` 翻译成生产环境（SRG）名，加 `remap = false` 会让它在生产环境注不进去。

### 2.3 核心算法：共享包裹池

核心数据结构是 `SharedPackagePool`——一个世界级 `SavedData`，按**容器身份 UUID** 分桶存
`Deque<BigItemStack>`。整个并行流程变成两步：

```
winner repack 整批（或普通打包机 attemptToSend 装好 1 个）
   └─→ 共享池 (SavedData, 按容器 UUID 分桶)              ← @Redirect 入池
         ↑
         └─→ 每 tick，空闲机器 poll 1 个 → 私有队列        ← tick HEAD 取件
               └─→ heldBox → 发送动画 → 下游 extractItem 清空  ← vanilla tick/extractItem，不动
```

**① 入池**

三条入池路径，全部汇到 `SharedPackagePool.deposit(UUID, List<BigItemStack>)`：

1. **理包机**：`RepackagerBlockEntityMixin` redirect `attemptToRepackage` 末尾的
   `queuedExitingPackages.addAll(boxesToExport)`（已 `javap` 确认该方法是**整类里唯一**一处
   `List.addAll`，在偏移 **295–300**：`295: getfield queuedExitingPackages` /
   `300: invokeinterface List.addAll`）。
2. **普通打包机**：`PackagerBlockEntityMixin` redirect 父类 `attemptToSend` 里**唯一**一处
   `queuedExitingPackages.add(...)`（已 `javap` 确认在偏移 **726: getfield queuedExitingPackages** →
   **739: invokeinterface List.add`**）。**上游没有这个注入点**，见 §3.18。
3. **任何来源的私有积压**：`tick` HEAD 的"上交"分支把 `queuedExitingPackages` 整体 deposit 后再清空
   ——这条是**生产者无关**的兜底：第三方打包机（如 FluidLogistics 从它自己的
   `ResourcePackagerEngine` 入队）没有可供 redirect 的单一接缝，但只要它把包裹放进私有队列，
   下一个 tick 就会被交给池。

`deposit` 只收 `count > 0` 的条目（`SharedPackagePool.java` 第 145 行），这是防"凭空造物"的最后一道闸（§3.10④）。

**② 按需取（策略 A）**

这是关键的安全设计——**不碰 heldBox 生命周期**（§3.8）。每次 `tick()` 开始前先跑我们的注入：

```java
Level level = self.getLevel();
if (level == null || level.isClientSide) return;

boolean hasQueue = !self.queuedExitingPackages.isEmpty();
boolean idle = self.animationTicks == 0 && self.heldBox.isEmpty() && !hasQueue;
if (!hasQueue && !idle) return;                 // 无事可做，省掉 key 解析

UUID vaultKey = VaultIdentity.vaultIdOf(self);  // ← 身份解析（§2.4）
if (vaultKey == null) return;
...
if (hasQueue) { /* 先 deposit 再 clear —— 见 §3.19 */ }
if (!idle) return;
BigItemStack pkg = pool.poll(vaultKey);
if (pkg != null) self.queuedExitingPackages.add(pkg);
// ↓ vanilla tick() 紧接着从队首取它：heldBox = queue[0]; animationTicks = 20; ...
```

**注意：这里没有 `if (!self.redstonePowered) return;`。** 这是与上游的**刻意分歧**，理由见 §3.9⑦。

**为什么安全**：注入只在队列空、`heldBox` 空、无动画时往队列尾部加 1 个包，vanilla tick 紧接着从
队首取它。私有队列始终 0~1 个元素，vanilla 取件逻辑看不到任何差异。`heldBox` 的被动清空协议
（§3.8）一行没动——零死锁风险。堵塞机（`heldBox` 非空）被守卫天然挡住，不取新包，活自动流向空闲
兄弟——**共享池天然就是动态均衡，不需要额外的再平衡逻辑**。

**③ count 语义（§3.5）**

`poll()` 按 `BigItemStack.count` 语义拆分：若队首 `count > 1`，只拆出 1 份
（`new BigItemStack(head.stack.copy(), 1)`），原 head 留在队首 `count--`；`count <= 1` 时整条弹出。
`drainAndDrop` 则按 `count` **全量** drop 同样多份。

### 2.4 容器身份分派（本模组新增，上游没有）

`VaultIdentity.vaultIdOf(PackagerBlockEntity)` 是**唯一的身份入口**，调用者包括
`RepackagerBlockEntityMixin`（入池）、`PackagerBlockEntityMixin`（取件）、`PartialRepackager.tryTakeover`。
它按容器种类分**三条路**：

```
vaultIdOf(packager)
  │
  ├─ 0. 先看流体：packager instanceof FluidTargetAccessor
  │       → 用它的 createPackageInnovation$fluidTargetPos()，而不是 targetInventory（§3.15）
  │     （普通打包机走不到这条，行为与上游逐字一致）
  │
  ├─ 1. 目标 BE instanceof IMultiBlockEntityContainer（多方块容器）
  │       → controller BE 上挂的稳定 UUID（VaultIdAccessor）
  │       → **没有适配器 mixin → 返回 null（不池化），绝不退化成位置键**（§3.12）
  │       → 顺手 ContainerHintRegistry.remember(..., multiblock = true)
  │
  ├─ 2. identifier instanceof NetworkAnchorAccessor（网络型存储）
  │       → positionKey(网络锚点位置)（§3.13）
  │
  └─ 3. 其他一切（原版箱子/木桶、任何 mod 的单方块容器）
          → positionKey(维度 + 坐标)，并先用 Create 的 InventoryIdentifier 归一化
            Pair/Single（原版双箱子两半必须得到同一个键）（§3.13）
```

**三方容器 → 三套「拆除清理」路径**（与身份一一对应）：

| 容器 | 身份 | 谁负责爆池 |
|---|---|---|
| 多方块（保险库 / Item Silo / 流体罐） | BE 上的稳定 UUID | `ConnectivityHandlerMixin`（`splitMulti` **TAIL**）+ 邻居/连通性判定 |
| 网络型存储 | 网络锚点位置键 | `LevelChunkRemovalMixin`：拆箱子算出的键不匹配（不动池）；拆控制器正好命中锚点键（爆池） |
| 任意单方块容器 | 位置键 | `LevelChunkRemovalMixin`（`LevelChunk.removeBlockEntity` HEAD） |

**关键性质**：单方块容器**不需要写任何适配 mixin**，任何 mod 的储物方块开箱即用——因为位置键
完全由 `level.dimension()` + `BlockPos` 派生，不碰那个 BE 的类。

### 2.5 核心算法：部分重组（上游 §2.4 的移植）

原版 `isOrderComplete` 是 all-or-nothing 闸门：碎片网格（LinkIndex × Index 二维）不齐，理包机不动工。
碎片经运输网络异步流式到达，大订单"等最后一片"窗口长。本模组让理包机在**已到碎片够合成至少一次**
时即开工。设计的关键字节码依据（已复验）：

- `repackBasedOnRecipes` **天生支持部分合成**——它按 `min(订单数, 材料够的次数)` 合成并原地消耗材料池，
  all-or-nothing 只存在于 `isOrderComplete` 一层
- **每个碎片都携带完整订单上下文**（`PackageOrderWithCrafts`，含全部配方与数量）——任意一片到达即知整单要合成什么
- **材料守恒自动限制总合成数**——stockkeeper 发货总量 == 订单总量，跨趟累计合成数被材料卡住，
  无需跟踪"已合成次数"
- vanilla `repack()` 是**订单收尾**语义（合成 + 余料全部导出），部分趟不能调它

**生命周期**（`PartialRepackager.tryTakeover`，由 `attemptToRepackage` HEAD 注入触发）：

```
碎片到达 → [预扫描] 有订单完整? → 是 → 放行 vanilla(零分叉)
                     ↓ 否
              有订单够合成≥1次? → 否 → 放行 vanilla(空转)
                     ↓ 是
              【接管】部分趟：repackBasedOnRecipes 合成 → 产物入共享池
                        余料存 PartialOrderTracker；按 orderId extract 碎片(消耗==删除)
                     ↓ 更多碎片到达 → 部分趟……(材料驱动)
                     ↓ 累计网格槽位全部消费完
              【末班】余料打包成内存伪碎片注入 helper → vanilla repack() 收尾
                     (合成剩余 + 余料按 vanilla 格式导出，末盒带完整 context) → 注销 tracker
```

预扫描对 vanilla 扫描循环的复刻是**逐偏移对应**的（通过 `javap` 复验）：

| 我们的预扫描 | vanilla `RepackagerBlockEntity.attemptToRepackage` 偏移 |
|---|---|
| `helper.clear()` | 0–4 |
| `inv.extractItem(slot, 1, true)` | 21–25 |
| `PackageItem.isPackage(extracted)` | 40–45 |
| `helper.isFragmented(...)` → 非碎片立即 `return false` | 51–60（vanilla 走 63–97 的直通分支） |
| `helper.addPackageFragment(...)` → 返回非 -1 立即 `return false` | 98–107（vanilla 在 108–113 `goto 122` 跳出循环） |
| 完整槽位循环、无 early break | 11–119（循环头 11 / 回边 119） |
| 按 orderId 删除碎片 | 147–215（`getOrderId` 在 189–195） |

**生命周期与池完全同构**（密钥同为容器 UUID，同生共死）：
`PartialRepackager.deposit(...)` → `SharedPackagePool`；`PartialOrderTracker` 跟着
`ConnectivityHandlerMixin` 一起 drain、跟着 `resolveMergeWinner` 一起迁移。

### 2.6 防物品复制 / 丢失

- **不复制**：整批 `deposit` 后只存在池里一份；`poll` 每次只拆出 1 份，`count` 严格递减
- **不丢失**：池是 `SavedData`，区块卸载/重进存档都保留（不随 BlockEntity 销毁）
- **打掉机器不爆池**：池跟着存档走，重放机器即恢复（§3.9④）
- **容器全拆才爆池**：`ConnectivityHandlerMixin`（多方块）/ `LevelChunkRemovalMixin`（单方块）
- **reshape 不爆不丢**：多方块的身份是 BE 上的 UUID，reshape 期间 key 完全不变（§3.11）
- **漏抽有兜底**：`OrphanSweep` + 持久化提示（§3.14）
- **部分重组的守恒保证**：消耗==删除（结构性，§3.10①②）、产物只出自 vanilla 方法（§3.10③）、
  `count<=0` 过滤（§3.10④）、余料只托管 SavedData（§3.10⑤）

---

## 3. 开发陷阱实录

这一节记录踩过的坑。**每一个都是真实踩过、耗费多轮迭代的坑。**

### 3.1 【最痛】ForgeGradle 6 的 Mixin 配置注册

**症状**：Mixin 类编译进了 jar，但运行时 `@Inject` 从不触发，且**没有任何报错**。
探针日志一行都没有，但主类的构造函数日志正常打印。

**根因**：ForgeGradle 6 的 `UserDevExtension` **移除了** `mixin {}` 配置块。
在 FG5 / 旧版本里，`minecraft { mixin { ... } }` 是注册自定义 mixin 配置的标准方式，但 FG6 不支持。
开发环境（`runClient`）下，Forge 从 sourceSet 加载 mod（不读 jar manifest 的 `MixinConfigs` 属性），
所以 mixin 配置根本没被注册。

**诊断关键**：run.log 里 `Remapping refMap ...` 列表只出现 flywheel/create/ponder 的 refmap，
**没有 `create_package_innovation.refmap.json`**。这是 mixin 配置没被加载的铁证。

**错误尝试**（都失败了）：
- `property 'mixin.config', 'xxx.mixins.json'`（run config 里）→ 无效
- `arg '-mixin.config', 'xxx.mixins.json'`（run config 里）→ `arg()` 方法不存在
- 项目顶层 `mixin {}` 块 → `Could not find method mixin()`
- `minecraft { mixin {} }` → `UserDevExtension` 无此方法

**正确解法**（本仓库现状，`build.gradle:1-30`）：引入 **MixinGradle 插件**（`org.spongepowered.mixin`）：

```groovy
plugins {
    id 'net.minecraftforge.gradle' version '[6.0,6.2)'
    id 'org.parchmentmc.librarian.forgegradle' version '1.+'
    id 'org.spongepowered.mixin' version '0.7-SNAPSHOT'   // ← 关键
}

mixin {
    add sourceSets.main, "${mod_id}.refmap.json"
    config "${mod_id}.mixins.json"            // 主配置
    config "${mod_id}.compat.mixins.json"     // 兼容配置（本模组新增，见 §2.1）
}
```

MixinGradle 在项目顶层重新引入 `mixin {}` 扩展，负责生成 refmap + 把配置接入 dev 环境。
**这是 FG6 + Mixin 的唯一可靠方式。**

**两个额外细节**（本仓库当前配置，别删）：

1. refmap 名必须与 `src/main/resources/create_package_innovation.mixins.json` 里的 `"refmap"` 字段一致
   （两边都是 `create_package_innovation.refmap.json`）。
2. jar manifest 里必须写 `MixinConfigs`，**两个配置都要列**：

   ```groovy
   'MixinConfigs': "${mod_id}.mixins.json,${mod_id}.compat.mixins.json"
   ```

   漏掉兼容配置 → 兼容 mixin 在**生产环境**（读 manifest，不读 sourceSet）静默不触发。

3. run 配置里必须开 refmap 重映射（Create 用了 MixinExtras），且要放在 `configureEach` 而不是只放
   `client`——`data` / `server` 运行配置同样会加载 Create，缺了它会在 mixin 应用阶段直接崩：

   ```groovy
   property 'mixin.env.remapRefMap', 'true'
   property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
   ```

### 3.2 【次痛】`refmap` 重复导致 jar 打包失败

**症状**：`Entry create_package_innovation.refmap.json is a duplicate`，`:jar` 任务失败。

**根因**：MixinGradle 的 `addMixinsToJar` 任务已经把 refmap 注入 jar；但之前手写的 `copyRefmap` 任务
又往 `build/resources/main/` 塞了一份，两份冲突。

**解法**：删掉手写的 `copyRefmap` 任务——MixinGradle 自己会处理 refmap 接入。
**教训**：加 MixinGradle 后，不要再用任何手动的 refmap 复制逻辑。

> 注意：删除任务定义后，残留的 refmap 文件还在 `build/resources/main/`，需要 `clean build` 才能彻底清掉。
> 普通 `build` 会因为缓存继续报错。

### 3.3 【诊断转折】"瓶颈在订单派发"是错误假设

**症状**：最初写的探针拦截 `LogisticsManager.broadcastPackageRequest`（总仓库的订单派发），
但探针从不触发，且游戏崩溃（`InvalidInjectionException`——参数类型 `Object` 不匹配实际的
`IdentifiedInventory`）。

**根因**：瓶颈定位错了。`broadcastPackageRequest` 是**总仓库**把订单派给打包机的逻辑，和玩家合成阵列里
的理包机**毫无关系**。理包机是红石常开、自主轮询的，根本不经过订单派发。

**教训**：修改一个模组前，必须用**探针实测**确认"你以为的瓶颈代码路径"真的是瓶颈。本项目（及其上游）
光是诊断瓶颈位置就花了 5+ 轮迭代（从"订单派发" → "碎片竞争" → "碎片死锁" → 最终定位到"输出队列独占"）。
每一步都是探针数据推翻了上一步的假设。

### 3.4 【术语混淆】Packager vs Repackager

Create 里有两个相似但不同的方块：
- `PackagerBlockEntity`（打包机）：把容器物品打包成包裹 / 拆包
- `RepackagerBlockEntity extends PackagerBlockEntity`（理包机）：合并碎片包裹成有序合成包裹

**源码铁律**（已 `javap` 确认）：`RepackagerBlockEntity` 的方法表是
`unwrapBox` / `recheckIfLinksPresent` / `redstoneModeActive` / `attemptToSend` / `attemptToRepackage`。即：

- **重写了** `attemptToSend` 和 `attemptToRepackage`，且 `attemptToSend` **不调 `super`**
  （字节码里没有 `invokespecial PackagerBlockEntity.attemptToSend`；它自己的逻辑在偏移 0–102，
  取件分支在 25–66）
- **不重写** `tick()`（继承父类）——这直接决定了 §3.9① 的注入方式
- `redstoneModeActive()` 被 override 成恒 `true`（那是"模式选择器"，**不是**红石门——见 §3.9⑦）
- 不参与链接站逻辑（`getPackager()` 显式排除它）

**本模组的用词约定**：本文与代码注释里，**打包机 = Packager（`PackagerBlockEntity`）**、
**理包机 = Repackager（`RepackagerBlockEntity`）**。二者身份互不包含，不要混用。

### 3.5 【数值理解】`BigItemStack.count` 才是真正的包裹数

**症状**：第一版分配逻辑按"列表元素个数"切分 `boxesToExport`，但日志显示
`distributing 1 packages`——明明是 60 个合成，怎么会只有 1 个包裹？

**根因**：`repackBasedOnRecipes` 对每个配方只生成**一个** `BigItemStack`，它的 `count`
字段才代表"这个包裹要发多少次"。60 个合成 = 1 个 `BigItemStack(box, count=60)`，
发送逻辑每次 `entry.count--`，归零才移除。

**解法**：所有消费点都按 `BigItemStack.count` 处理：

| 位置 | 按 count 的正确做法 |
|---|---|
| `SharedPackagePool.poll` | 队首 `count > 1` 时只拆出 1 份（`new BigItemStack(head.stack.copy(), 1)`）并让 `head.count--` |
| `SharedPackagePool.deposit` | 只收 `count > 0`（`count <= 0` 的盒子**绝不能发出**，见 §3.10④） |
| `SharedPackagePool.pending` | 待发**总份数** = Σ`count`，**不是**条目数 |
| `SharedPackagePool.drainAndDrop` | 每条按 `count` 爆出**同样多份**实体 |
| `PartialOrderTracker.drainAndDrop` | 余料按 `maxStackSize` 分批 |
| `PartialRepackager.countOf` | 日志里的包裹数 = Σ`count` |

### 3.6 【sibling 发现】固定半径扫描不可靠

**症状**（上游时代）：9 台理包机（3 面各 3 个）的保险库，只能找到 3~5 个兄弟。

**根因**：早期用"winner 周围半径 2 的立方体"扫描，但大保险库对角的理包机距离 >2。

**本模组现状**：**共享池架构根本没有 sibling 发现**——每台机器独立从池 poll，不需要知道兄弟是谁。
`InventoryIdentifier` 值相等作为正确性保障的历史用途（上游 §3.7）在**身份判定**上仍然相关，但不再是
"分活给谁"的依据。

> 历史诊断（0.3.0 时代适用）：当时用 `[GDR-DIST] ... across N repackager(s)` 排查 sibling 漏找。
> 本模组对应的日志前缀是 `[CPI-POOL]`（§6.1），且**没有**分布/再平衡日志。

### 3.7 能力实例身份匹配对缓存"代次"敏感（上游 0.2.0 问题，本模组继承其结论）

**症状**（上游 0.2.0）：在**已存在的存档里首次安装模组**（尤其服务器多人存档）后，一个保险库周围贴了
9 台理包机，下一张订单却只有 6 台工作；而同样的阵列设计在**新存档/单人生存**里完全正常。
把理包机拆掉**重新放一遍**就恢复正常。

**根因**：早期 sibling 校验用 `IItemHandler` 的 `==` 身份匹配。这个 `IItemHandler` 是
`InvManipulationBehaviour.getInventory()` 返回的 `targetCapability.orElse(null)`，而
`targetCapability` 是 forge 的 `LazyOptional` 缓存。保险库的 controller 块**首次被查询能力时**才构造出
能力实例，且这个 `LazyOptional` **不是永生的**：多方块结构在 chunk 卸载/重载、controller 切换、
capability invalidate 时会重建，重建后对外暴露的是**全新的 `IItemHandler` 实例**。
所以"老存档装新模组"时，各机器的缓存可能指向**不同代**的能力实例，`sibInv != myInv`，兄弟被丢弃。

**本模组的最终结论（三条，都是硬约束）**：

1. **绝不用 `IItemHandler ==` 判身份。**
2. **绝不用几何派生的键做跨 reshape 的身份**——`InventoryIdentifier.Bounds(BoundingBox)` 由
   `initCapability()` 按 BE **各自懒算**，reshape 期间不同机器会看到不同快照（这正是上游 0.5.0
   池被分裂 → 物品复制/丢失的根因，也是本模组改用 UUID 的原因，见 §3.11）。
3. **单方块容器用 `positionKey`**（维度+坐标派生的确定性 UUID），完全绕开能力实例代次问题。

> **为什么位置键对单方块容器安全**：位置键不引用能力实例、不引用 BE 的类、不随 chunk 卸载改变。
> 同一个方块槽位 → 同一个键。**对多方块容器它就是不安全的**（见 §3.12），这是本模组在
> `VaultIdentity` 里对两者分别处理的原因。

### 3.8 【关键背景】发送状态机依赖 heldBox 被动清空协议

**为什么记这一节**：任何未来想动发送状态机的改动（包括"让池直接当取件源"）都必须先吃透这里。

**tick 取件条件**（`PackagerBlockEntity.tick()`，Repackager 不重写、继承父类）。已 `javap` 确认的
关键偏移：`22: getfield animationTicks` / `35–36: getfield level` /
`128: putfield animationTicks`（取件后设 20）。

```
if (animationTicks == 0 && !level.isClientSide
    && !queuedExitingPackages.isEmpty() && heldBox.isEmpty()) {
    BigItemStack bis = queuedExitingPackages.get(0);
    heldBox = bis.stack.copy();
    bis.count--;
    if (bis.count <= 0) queuedExitingPackages.remove(0);
    animationInward = false;
    animationTicks = 20;   // CYCLE：1 包 / 20 tick（1 秒）
    notifyUpdate();
}
```

**heldBox 从不被机器自己清空**。`PackagerBlockEntity` 全文中对 `heldBox` 的写入只有：构造时 `EMPTY`、
tick 取件时 `copy()`、`attemptToSend` 直发时赋新包、NBT 读入。
**动画结束（`animationTicks` 减到 0）那一拍只调 `wakeTheFrogs()` + `setChanged()`，不清 heldBox。**

**heldBox 的清空是被动的**——靠下游库存通过 `PackagerItemHandler.extractItem` 抽走它。
`javap` 已确认 `PackagerItemHandler` 的全部行为：

```
extractItem(slot, amount, simulate):
    0-13:   if (animationTicks != 0) return EMPTY;      // 动画期间禁止抽
    14-21:  ItemStack local = blockEntity.heldBox;
    23-32:  if (!simulate) setStackInSlot(slot, EMPTY);
    35-37:  return local;

setStackInSlot(slot, stack):
    0-4:    if (slot != 0) return;
    5-10:   blockEntity.heldBox = stack;               // putfield heldBox —— 唯一的写点
    13-17:  blockEntity.notifyUpdate();
```

**含义**：vanilla 的"连续发包"完全依赖"下游库存会主动从机器槽位抽走 heldBox"。一旦下游抽不动
（库存满、无下游），heldBox 卡住非空，`heldBox.isEmpty()` 永假，tick 取件死锁——但 vanilla 靠
extractItem 兜底，所以正常场景下不会发生。

**持续发包靠 lazyTick**（周期 10 tick ≈ 0.5 秒，`SmartBlockEntity` 默认 `setLazyTickRate(10)`）：
`lazyTick` 在红石常开模式下每 0.5 秒调一次 `attemptToSend(null)`。`activate()`（红石上升沿）只在
开机触发一次并设 `buttonCooldown=40`（2 秒退避），之后稳态发送频率 = lazyTick 周期。

**对本模组的意义**：

- 我们**只在 tick HEAD 往队列尾部加 1 个包**（策略 A），vanilla tick 照常从队首取。
  heldBox 生命周期**一行没动**，零死锁。
- **绝不拦 tick 里 `get(0)` 的取件行**——那需要接管 heldBox 生命周期，风险高一个数量级。
- 堵塞机被 `!heldBox.isEmpty()` 守卫天然挡住，不取新包（这正是我们想要的"堵塞不喂"策略）。

### 3.9 【核心陷阱】共享池脱离 BlockEntity → 九个必须吃透的点

把包从"各机私有 `queuedExitingPackages`"搬到"世界级 `SharedPackagePool`(SavedData)"，
带来九个必须吃透的陷阱。

**① tick 必须注父类 `PackagerBlockEntity`，不能注 `RepackagerBlockEntity`**

已 `javap` 确认 `RepackagerBlockEntity` **不重写** `tick()`（继承父类）。Mixin 解析目标方法时按目标类
自身的方法表查找，Repackager 的方法表里没有 `tick`，所以
`@Mixin(RepackagerBlockEntity.class) @Inject(method="tick")` **无法注入**（annotation processor 会报
"Unable to locate target tick"）。唯一可行模式：

```java
@Mixin(value = PackagerBlockEntity.class, remap = false)  // 注父类
@Inject(method = "tick()V", at = @At("HEAD"))
private void createPackageInnovation$feedFromPool(CallbackInfo ci) {
    PackagerBlockEntity self = (PackagerBlockEntity) (Object) this;
    ...
}
```

所有注 Create 类的 mixin 都要 `remap = false`：注入的是 Create 自身方法（official mapping 下名字就是
字面名），不需要 searge refmap 重映射。不加 `remap = false`，annotation processor 会报
"Unable to locate obfuscation mapping for @Inject target tick"。

> **⚠️ 与上游分歧（不要改回去）**：上游在这个 handler 第一行写了
> `if (!(self instanceof RepackagerBlockEntity)) return;`（只服务理包机）。
> **本模组删掉了这个守卫**——普通打包机也要共享池。理由见 §3.19，改动本身见 §2.2 #3/#4。

**② 容器销毁钩子：注 public `splitMulti`，不能注 private `splitMultiAndInvalidate`**

容器销毁/reshape 的通用 chokepoint 是 `ConnectivityHandler.splitMultiAndInvalidate`，但它有两个可见性
障碍使其**无法从 mixin 注入**（已 `javap -p -s` 确认签名）：

```
public static <T extends BlockEntity & IMultiBlockEntityContainer> void splitMulti(T);
private static <T extends BlockEntity & IMultiBlockEntityContainer> void
        splitMultiAndInvalidate(T, ConnectivityHandler$SearchCache<T>, boolean);
```

- 方法是 `private static`。
- 第二个参数 `SearchCache<T>` 是 **package-private**（`ConnectivityHandler$SearchCache`，非 public）
  → 我们的代码**无法 import** 它。

**踩过的坑**：初版试图"只捕获第一个 `BlockEntity` 参数、跳过 SearchCache"来绕过可见性。**这是错的**——
Mixin 的 `@Inject` handler **必须匹配目标方法的完整参数列表**（可省略尾部参数，但不能跳过中间的）。
运行时报 `InvalidInjectionException: Expected (BlockEntity;SearchCache;Z;CallbackInfo;)V but found
(BlockEntity;CallbackInfo;)V`，游戏进世界即崩。那条 "Cannot find target method" 的 AP **警告不是良性
的**——它反映的是 AP 无法验证 private+package-private 方法，但运行时 Mixin 一样无法注入。

**正确的解法**：改注 **public `splitMulti(T)`**（break/wrench 的公开入口，擦除后参数只有一个 BlockEntity，
handler 用 `(BlockEntity, CallbackInfo)` 即可）。

> 注意区分：`splitMulti` 上**也有**一条 "Cannot find target method" 的 AP 警告，但那条是**误报**
> （AP 匹配不了交类型边界 `<T extends BlockEntity & IMultiBlockEntityContainer>`），
> **与上面那条不是一回事**。区分方法与完整论证见 §3.16。

**③ `isController()` / `getControllerBE()` 在 teardown 后是 footgun**

`isController()` 返回 `controller == null`。`removeController()` 会 null 掉 controller 字段，于是
teardown 后每个 part 都谎报自己是 controller。`getControllerBE()` 同样会退化成"返回 part 自己"，
而 part 的 `getWidth()/getHeight()` 字段被 `removeController` 重置成 1。

**绝不能用 `isController()`/`getControllerBE()` 做 teardown 时的 controller/几何判定。**
正确做法：**直接读 BE 上 mixin 注入的 UUID 字段**（`VaultIdAccessor`），它与几何完全无关。
`ConnectivityHandlerMixin` 就是这么做的：

```java
UUID vaultId = broken.createPackageInnovation$getVaultId();  // 直接读，不跳 getControllerBE
```

> 唯一一处仍然用 `isController()` 的地方是 `ContainerIdSupport.onNotifyMultiUpdated`——那里安全，
> 因为 UUID 逻辑与几何无关（`ContainerIdSupport.java` 第 101–105 行的 javadoc 说明了这一点）。

**④ 容器centric 归属：打掉机器不爆，打掉/扩建容器才爆**

共享池存在 SavedData 里，与 BlockEntity 独立。这导致一个根本行为变化（README 已告知玩家）：

- **打掉打包机/理包机** → vanilla `destroy()` 只 drop `heldBox` + 私有 `queuedExitingPackages`
  （策略 A 下私有队列只 0~1 元素，且积压每 tick 都会被"上交"到池）。**共享池不爆**，
  包安全留 SavedData，重放机器即恢复。
- **打掉多方块容器（任意方块）/ 扳手拆除** → `ConnectivityHandlerMixin`（注 `splitMulti` TAIL）触发，
  先判 partial vs 全拆（§3.11），只有**全拆**才 `drainAndDrop`，该键的池全爆成实体。
- **打掉单方块容器** → `LevelChunkRemovalMixin`（注 `LevelChunk.removeBlockEntity` HEAD）爆池（§3.20）。
- **扩建容器（reshape）** → UUID key 不变，**不爆不迁**（§3.11）。

**⑤ mixin 类里不能有 public/static 普通方法（运行时崩溃）**

**症状**：游戏启动崩溃，`InvalidMixinException: Mixin ... contains non-private static method
vaultBoundingBoxOf(...)`，`checkMethodVisibility` 报错。

**根因**：mixin 类的方法会被**合并进目标类**。初版把共享 helper 声明成某个 mixin 的 `public static` 方法，
想让另一个 mixin 调用它。但 Mixin transformer 拒绝把非 private 的 static 方法合并进目标类
（会污染目标类的 API 表面）。

**修复**：共享 helper 必须放**独立工具类**（非 mixin）。本模组因此有了 `VaultIdentity`、`VaultGeometry`、
`ContainerIdSupport` 三个 identity 包工具类；各 mixin 调用它们。**mixin 类里的 helper 方法必须是 `private`
（实例方法）**，跨 mixin 共享的逻辑一律抽到独立类。

> **public 实例方法是允许的**——那是 duck interface 契约的实现方式（§3.11 末段）。
> `@Invoker`/`@Accessor` 接口 mixin 是标准例外：方法被转成调用桥，**不合并**进目标类，
> 所以 `PackageRepackageHelperInvoker` 不受本条约束。

**⑥ reshape 迁移（上游 0.5.0 的历史方案，本模组已彻底废弃）**

> **本模组状态**：上游 0.5.0 用 `BoundingBox + notifyMultiUpdated + VaultBoxTracker.migrateKey` 做 reshape
> 迁移，0.5.1 就废弃了它。**本模组根本没有 `VaultBoxTracker` 这个类**，也没有任何 BoundingBox 派生键。
> 本节仅作历史背景保留，避免有人照上游文档"补回"这套逻辑。
>
> 为什么不能补回来：`InventoryIdentifier.Bounds` 由 `ItemVaultBlockEntity.initCapability()` **按 BE 各自
> 懒算**，reshape 期间不同机器连接的 BE 的 `invId` 字段可能反映**不同时刻**的几何。结果同一个容器的池
> 被 BoundingBox key 分裂成多份，引发**物品复制**（多个 key 各持一份相同数据）和**丢失**（drain 时只命中
> 一个 key）。上游的日志对账证据是"下单 192 包却发出去 256 包"。
>
> **正确方案见 §3.11。**

**⑦ tick HEAD 灌队列与红石闸门【⚠️ 本模组刻意与上游相反】**

**上游的写法**：在 `feedFromPool` 顶部加 `if (!self.redstonePowered) return;`，理由是"我们的 tick 注入
绕过了 lazyTick 的红石门，所以必须补回来"。

**本模组刻意不加这道闸门。** 这段判断写在 `PackagerBlockEntityMixin` 的 javadoc
（`PackagerBlockEntityMixin.java:78-89`，含 "⚠️ Deliberately NOT gated on redstonePowered"）。**不要"修回去"。**

**为什么上游的写法是错的（本模组的分析）**：

1. vanilla `tick()` **本身没有任何红石检查**——它无条件排空 `queuedExitingPackages`。
   已 `javap` 确认：`PackagerBlockEntity.tick()` 的字节码里对 `redstonePowered` **没有一次 `getfield`**；
   `attemptToSend` 里也没有。只有 `lazyTick`（偏移 20–26：`getfield redstonePowered` / `ifne` / `return`）
   和红石上升沿路径读它。
2. 红石只决定 `attemptToSend` **是否往队列里填包**（GATE 1），不决定"是否把队列里的包发出去"。
3. 所以给我们的灌入加红石门 = **让我们比 vanilla 更严**：一个包裹已经被某台机器交给池、之后那台机器的
   红石又被切断，那个包裹就**永远取不回来**——订单静默停摆，玩家看到的是"物品被吞了"。
4. 匹配 vanilla（队列里有就发，不管有没有红石）**是池能安全工作的前提**。

**真正保护堵塞机的仍然是 `heldBox` 守卫**（`!self.heldBox.isEmpty()` → `idle == false`），
与红石无关。这台机器堵塞时它不取新包，活自动流向空闲兄弟。

> ❌ **不要**改成 `if (!self.redstonePowered) return;`。
> ❌ **不要**用 `redstoneModeActive()` 当红石门——`RepackagerBlockEntity` 把它 override 成恒 `true`
> （它是"模式选择器"，不是红石门）。
>
> （本仓库 `AGENTS.md` §4 的禁区表里也列了这一条，指向 `PackagerBlockEntityMixin.java:78-89`。）

**⑧ 上游 v0.5.0 的 BoundingBox-keyed 时代已结束：本模组的 key 恒为 UUID**

`SharedPackagePool` / `PartialOrderTracker` 的 key **只可能是 `java.util.UUID`**，两种来源：

- 多方块容器 → BE 上 mixin 注入的**随机 UUID**（`UUID.randomUUID()`，经 `VaultIdAccessor` 读写）
- 一切非多方块容器 → `VaultIdentity.positionKey(level, pos)`，即
  `UUID.nameUUIDFromBytes("create_package_innovation:container@<维度>:<x>,<y>,<z>")`
  （UUIDv3 / MD5 name-based）

两者**同形**（都是 `UUID`），所以 SavedData 的 NBT 格式只有一种，**本特性无需存档迁移**
（`VaultIdentity.positionKey` 的 javadoc 明确写了这一点）。

**旧存档升级**：`load()` 会探测上游 0.5.0 的旧格式（vaultEntry 含 `"MinX"` 整型字段）→
打 WARN 并**清空**该 SavedData（alpha break）：

```
[CPI-POOL] detected legacy BoundingBox-keyed SavedData (N vault(s), ~M package(s)); clearing pool on upgrade...
[CPI-PARTIAL] detected legacy BoundingBox-keyed SavedData (N tracked order(s)); clearing on upgrade...
```

> 中文说明：检测到上游 0.5.0 的 BoundingBox-keyed 存档，升级时清空池/追踪器。在跑的订单会丢。
> 玩家应让在跑订单完成后再升级。**这是唯一一次格式 break，之后不会再有**（key 同形）。

**⑨ 边界：本模组没有 `VaultBoxTracker`，但多了一个"合并参与者"瞬态集合**

上游的 `VaultBoxTracker`（WeakHashMap 追 controller 的 last-known box）**已删除**。
取而代之的是 `SharedPackagePool.mergeParticipants`——一个**不持久化**的 `Set<UUID>`，
由 `ContainerIdSupport.onSetExtraData` 在 reshape/合并过程中登记，由新 controller 的
`notifyMultiUpdated` 消费后清空。**服务端单线程访问，无需同步。**
详见 §3.11 的"双容器合并"。

### 3.10 【核心陷阱】部分重组的五条不变量（违反任何一条都会丢/复制物品）

部分重组是在上游三次失败尝试之上成功的。以下不变量是它的安全基石，
**任何未来改动都必须逐条核对**：

**① 绝不在 `addPackageFragment` 层面触发 repack**

`addPackageFragment` 一返回非 -1，`attemptToRepackage` 的扫描循环立即 break（字节码偏移 108–113：
`goto 122`）——此时 `collectedPackages` 只收了部分碎片，但后续 extract 循环（偏移 147–215）按 orderId
删除**全库**碎片，消耗≠删除 = 丢物品。本模组的预扫描**跑满全槽、无 early break**，
只有 vanilla 自己检测到完整订单时才走 vanilla 路径（`return false` 放行）。
任何"提前触发"的优化都会重新打开这个洞。

**② 预扫描必须零副作用**

HEAD 注入里的预扫描只用 `extractItem(slot, 1, true)`（simulated），不写 `heldBox`、不做真实 extract、
不改库存 NBT。遇直通包裹（非碎片）或完整订单必须 `return false` 放行 vanilla——vanilla 会自己重扫并
正确处理。若预扫描带了副作用，vanilla 路径与我们的路径会双重消费。

> 细节：预扫描开头必须 `helper.clear()`。`helper` 是 BE 上的**长生命周期字段**，
> 可能还留着上一次 vanilla 调用的碎片（vanilla 在**它自己**的开头清，不是在我们之前清）。

**③ 部分趟只能调 `repackBasedOnRecipes`，末班才能调 `repack()`**

`repack()` 是订单收尾语义：合成 + **把余料全部导出**给订单地址。部分趟调它会把未来合成的材料提前
当余料寄走。只有末班（累计网格消费完毕）才能调 `repack()`——此时导出余料正是 vanilla 的 close-out 行为。
判断末班用 `TrackedOrder.wouldComplete`（累计槽位网格），**不是**用当前在场碎片。

**④ count≤0 的产物盒必须过滤，绝不可发出**

`repackBasedOnRecipes` 对每个配方都返回盒子，材料不够的配方 count=0——但盒子里仍装着 1 份配方材料
（pattern）。发出 count=0 的盒子 = 凭空造物（材料从未从池中消耗）。
`SharedPackagePool.deposit` 只收 `count > 0`，这是最后一道闸；`PartialRepackager.tryPartialPass` 在
`total == 0` 时直接 `return false`（让 vanilla 空转），也不会 `addAddress` 到 count≤0 的盒子上。
新增任何产物出口都必须沿用同样的过滤。

**⑤ 余料只能托管在 SavedData，不可重新塞回容器**

把余料重新打包成碎片塞回容器会撞上在途碎片的原始 (LinkIndex, Index) 编号——网格出现重复/空洞，
`isOrderComplete` 语义崩坏，且每 tick 的 extract/insert 搅动巨大。余料存 `PartialOrderTracker`(SavedData)，
容器销毁时爆成原材料实体。

> 末班的"伪碎片"是**内存对象**（`PartialRepackager.buildPseudoFragments`），只喂给 `helper.repack()`，
> **从不 insert 进容器**——这不违反本条。

### 3.11 【核心】容器身份：为什么必须是 BE 上的稳定 UUID

**为什么不用几何**：`InventoryIdentifier.Bounds` 由 `ItemVaultBlockEntity.initCapability()` **按 BE 各自
懒算**——reshape 期间不同机器连接的 BE 的 `invId` 字段可能反映**不同时刻**的几何（因为 `initCapability`
只在 `itemCapability.invalidate()` 后下次查询时才重算）。结果：同一个容器的池被分裂成多份 BoundingBox
key，机器分别取到不同的"快照"，引发**物品复制**（多个 key 各持一份相同数据）和**丢失**（drain 时只命中
一个 key）。上游的日志对账：下单 192 包却发出去 256 包，多出 64 包就是分裂的 key 各持一份的明证。

**Create 提供的官方钩子**：`IMultiBlockEntityContainer` 的 extraData 三件套
（`getExtraData`/`setExtraData`/`modifyExtraData`）——这是 Create 为"从旧 controller 把数据带到 parts，
再合并进新 controller"设计的通道。

#### UUID 怎么跨 split / reform / reshape 传递

已用 `javap` 复验 Create 6.0.8-289 的调用时机：

- **`ConnectivityHandler.splitMulti(T)`** 只是转调（偏移 0–6）：
  ```
  0: aload_0
  1: aconst_null
  2: iconst_0
  3: invokestatic splitMultiAndInvalidate(BlockEntity, SearchCache, boolean)V
  6: return
  ```
- **`splitMultiAndInvalidate`** 内部对每个 part，在**同一个方法里**依次做（偏移取自同一段字节码）：
  - `353: invokeinterface getExtraData()Ljava/lang/Object;`
  - `358: invokeinterface setExtraData(Ljava/lang/Object;)V`
  - `369: invokeinterface removeController(Z)V`

  即 `getExtraData` → `setExtraData` **都在 `removeController` 之前**。所以每个 part 在被 null controller
  之前继承了原 controller 的 UUID（写入 part 自己的 `cpi$vaultId` 字段）。
- **`tryToFormNewMultiOfWidth` 段**（同方法另一段，偏移 669 / 823 / 1036 / 1065 / 1074）：
  reform 后依次有 `getExtraData`、`modifyExtraData`、`notifyMultiUpdated`、`setExtraData`、
  `notifyMultiUpdated`——新 controller 上会调 `notifyMultiUpdated()`。

> 上游文档写的是 "offset 339-369" 与 "offset 665-1074"。本机复验得到的关键调用点是
> **353 / 358 / 369** 与 **669 / 823 / 1036 / 1065 / 1074**（同一方法内的偏移，随 `javap` 版本与
> `-c` 选项可能略有出入）。**以本仓库复验值为准。**

#### 三个适配器都只做同一件事

`ItemVaultBlockEntityMixin` / `ItemSiloBlockEntityMixin` 都只是**薄胶水**，全部逻辑在
`ContainerIdSupport`：

| 组成 | 内容 |
|---|---|
| `@Unique UUID cpi$vaultId` | 稳定身份，**存在 BE 上**（不是静态 map）——它要写进这个 BE 自己的 NBT，且必须与 BE 同生共死 |
| `@Unique Set<UUID> cpi$observedIds` | 本次 `notifyMultiUpdated` 之前经 `setExtraData` 观察到的 UUID（合并检测用，**不持久化**） |
| `implements VaultIdAccessor` 的两个 public 桥方法 | `createPackageInnovation$getVaultId()` / `...$setVaultId(UUID)` |
| `getExtraData()` | 返回 `new VaultExtraData(cpi$vaultId)` |
| `setExtraData(Object)` | → `ContainerIdSupport.onSetExtraData(...)`：登记 observed + 全局 `noteMergeParticipant` + 自己没 UUID 时采纳 |
| `modifyExtraData(Object)` | 原样返回 `data`（vanilla 在 self 上调，对我们无合并语义） |
| `@Inject(HEAD) notifyMultiUpdated()V` | → `onNotifyMultiUpdated(...)`：惰性铸 UUID + 合并裁决 |
| `@Inject(RETURN) write/read(CompoundTag,Z)V` | → `onWrite/onRead`：NBT 持久化，键 `GDR_VaultId` |

**NBT 键刻意沿用上游拼写 `GDR_VaultId`**（`ContainerIdSupport.VAULT_ID_KEY`）：它已经写进玩家存档，
改名会让所有已放置容器的 UUID 变成不可达孤儿（池条目挂在旧 UUID 上，容器读不出旧值）。
每个 BE 只读自己的 tag，所以跨容器类型共用同一个键名是安全的。

> **⚠️ 绝不重命名持久化 NBT 键**。当前四个：`GDR_VaultId`（`ContainerIdSupport.java:67`）、
> `gdr_shared_package_pool`（`SharedPackagePool.java:68`）、`gdr_partial_order_tracker`
> （`PartialOrderTracker.java:57`）、`gdr_container_hints`（`ContainerHintStore.java:52`）。
> 键**名**是硬约束；键**值**是 `UUID.randomUUID()` 生成的，不是定值。

#### 双容器合并（罕见但要正确处理）

两个独立容器 A 和 B 合并成一个更大的多方块时：vanilla 的 `splitMultiAndInvalidate` 对每个 part 调
`setExtraData`，但 parts 来自两个不同容器——A 的 parts 继承 uuidA，B 的 parts 继承 uuidB。
新 controller（formMulti 选出来的那个）只看到它自己继承的那个 UUID。

**解法：全局 merge-participant set。**

```java
// ContainerIdSupport.onSetExtraData：每次收到 UUID 就登记到全局集合
SharedPackagePool.get(server).noteMergeParticipant(incoming);
PartialOrderTracker.get(server).noteMergeParticipant(incoming);

// 新 controller 的 notifyMultiUpdated：
UUID winner = pool.resolveMergeWinner(tracker);   // 集合 < 2 → 清空 + 返回 null（单容器 reshape，no-op）
if (winner != null && !winner.equals(current)) {  // 集合 ≥ 2 → 最小 UUID 赢
    acc.createPackageInnovation$setVaultId(winner); // 迁移所有输家的池 + tracker，然后采纳赢家
    self.setChanged();
}
```

赢家取**最小 UUID**（确定性）。日志：

```
[CPI-POOL] two-vault merge resolved: winner=<uuid>, migrated N package(s) to winner
[CPI-PARTIAL] migrated M tracked order(s) on vault merge <old> -> <new>
```

#### 部分拆 vs 全拆的判定（上游 0.5.1 第三次修复，本模组继承 + 加固）

`ConnectivityHandlerMixin` 不能无条件 drain——拆容器一个 part 时，剩余 parts 会重新 form 一个更小的
multiblock，**通过 extraData 继承同一个 UUID**，库内碎片留在剩余 parts 里，理包机继续 craft。
如果在 partial break 时 drain，池被清空但订单还在跑，下次碎片扫描会重新接管订单、重新 craft——
**物品复制**。

**判定链（三段，见 §3.16 与 §3.17）**：

```
1. VaultGeometry.anySiblingVaultWithUuidExists(level, pos, uuid)         // ±11 立方体，O(23³)≈12k
      → 找到同 UUID 幸存部件 → partial，return（不 drain）
2. VaultGeometry.adoptUuidOnSurvivingParts(level, pos, block, uuid)      // ±1 邻居，顺手写回 UUID
      → 找到同方块同类型幸存部件 → partial，return
3. VaultGeometry.anyContiguousPartNearby(level, pos, block, uuid, true)  // 连通性行走（面相邻）
      → 找到 → partial，return
   ↓ 三段都说"没了"
   SharedPackagePool.drainAndDrop(...) + PartialOrderTracker.drainAndDrop(...) + ContainerHintRegistry.forget(...)
```

**为什么不信任 `getControllerBE()`**：teardown 过程中 controller 字段会被 null（§3.9③ footgun），
`getControllerBE()` 返回 part 自己，而 part 的 `getWidth()/getHeight()` 字段被 `removeController`
重置成 1——基于这些字段的 box scan 会算成 1×1×1，完全错过真实几何。**直接按 UUID 扫邻居不依赖任何
几何字段，最可靠。**

**时序前提**：这段判定必须在 `splitMulti` 的 **TAIL** 跑（§3.16），否则第 1 段必然落空。

#### mixin 包路径陷阱（运行时崩 `IllegalClassLoadError`）

`VaultIdAccessor` 这类 duck interface **必须放在普通包**（本仓库是 `identity/`；`com.frnc.create_package_innovation` 是根包，接口不在那里，更不能在任何 `mixin` 包），
**不能放 `com.frnc.create_package_innovation.mixin`**。Mixin 子系统**禁止任何代码直接引用注册在 mixin
包里的类**——启动崩溃：

```
IllegalClassLoadError: ... is in a defined mixin package ... cannot be referenced directly
```

本模组三个 duck interface 都在 identity 包：`VaultIdAccessor`、`NetworkAnchorAccessor`、`FluidTargetAccessor`。

> **已知的既存边界情形，不要照抄**：`PartialRepackager`（partial 包）`import` 了
> `com.frnc.create_package_innovation.mixin.PackageRepackageHelperInvoker`。
> `@Invoker` 接口不被合并进目标类（它被转成调用桥），所以按现状工作；但这是**普通包引用 mixin 包**的
> 边界情形。新代码不要模仿——需要桥接时优先把接口放到别处。

#### mixin 类里 public 实例方法的边界

§3.9⑤ 禁止 mixin 类里有 **public static** 方法（合并进目标类会污染 API 表面）。但 **public 实例方法**
是允许的——尤其是用来满足 duck interface 契约（mixin 类 `implements SomeInterface` + 在 mixin 类里定义
interface 的 public 方法，合并后目标类自动 implements 该 interface）。本模组所有适配器里的
`createPackageInnovation$getVaultId()` / `setVaultId()` / `getExtraData()` / `setExtraData()` /
`modifyExtraData()` 都是这种模式。

> 为什么不用 `@Accessor` 代替 duck interface：Mixin 的 APT **无法解析**"由同一编译期里另一个 mixin
> 注入的字段"（APT 时那个字段在原类里还不存在）。所以这里只能用 duck interface。

---

### ↓ 以下为上游文档没有的新增小节（编号从 §3.12 起，避免重排上游编号）

### 3.12 【新增】身份不再绑死保险库：三条路线与"无适配器必须返回 null"

**背景**：上游只认 Create 的保险库（`ItemVaultBlockEntity`）。本模组的 `VaultIdentity.vaultIdOf`
支持**任何**容器，分三条路线（完整分派图见 §2.4）。

**路线 1：多方块容器 → BE 上的稳定 UUID**

```java
if (be instanceof IMultiBlockEntityContainer container) {
    BlockEntity controllerBe = container.getControllerBE();
    if (controllerBe == null) controllerBe = be;      // footgun 守卫：part 可能汇报 controller == self
    if (!(controllerBe instanceof VaultIdAccessor acc)) return null;   // ← 关键
    UUID id = acc.createPackageInnovation$getVaultId();
    if (id == null) {                                 // 刚放下 / 刚加载，notifyMultiUpdated 还没跑
        id = UUID.randomUUID();                       //   按需铸造，让 deposit/poll 能继续
        acc.createPackageInnovation$setVaultId(id);
        controllerBe.setChanged();
    }
    ContainerHintRegistry.remember(level.getServer(), id, level, vaultPos, true);  // multiblock = true
    return id;
}
```

**⚠️ 与上游分歧（不要改回去）**：`return null` 这一行是**故意**的。

**没有适配器 mixin 的多方块容器，必须返回 `null`（= 不池化），绝不能"退化成位置键"当兜底。**

为什么：位置键**对多方块不是 reshape 稳定**的。一个 3×3×3 的保险库加高一层，controller 的坐标可能不变
但部件集合变了；拆掉中间一层，同一个逻辑容器会被算成两个不同的位置键。用位置键给多方块分桶 =
把包裹搁浅在永远没人取的 key 上（静默丢物品）。保险库之所以能用 UUID，正是因为
`ItemVaultBlockEntityMixin` 给它注入了 UUID 字段；**没有这个字段的容器就没有 reshape 安全的身份，
所以正确答案是"不池化、走 vanilla"**，而不是"凑一个键出来"。

**路线 2：网络型存储 → 网络锚点位置键**

`identifier instanceof NetworkAnchorAccessor` 时，用锚点（网络控制器）位置派生位置键。
细节与理由见 §3.13。

**路线 3：其他一切 → 归一化后的位置键**

先用 Create 自己的 `InventoryIdentifier` 归一化，再派生位置键。细节见 §3.13。

**为新容器写适配器的最小工作量**

- **单方块容器，任何 mod**：**什么都不用写**，开箱即用（路线 3）。
- **多方块容器**：写一个约 60 行的适配器 mixin——
  `@Unique UUID cpi$vaultId`（需要合并语义时再加 `@Unique Set<UUID> cpi$observedIds`）、
  `implements VaultIdAccessor` 的两个 public 桥方法、extraData 三件套（**除非该 BE 已占用该通道**，
  见 §3.15）、以及 `notifyMultiUpdated`/`write`/`read` 三个 `@Inject`——每个都只委托给
  `ContainerIdSupport`。然后把新类登记进对应的 mixin json（§2.1）。
  现存参考实现：`mixin/compat/ItemSiloBlockEntityMixin.java`。

### 3.13 【新增】单方块容器的位置键：为什么必须先归一化，以及双箱子陷阱

**位置键的构造**（`VaultIdentity.positionKey`）：

```java
String raw = "create_package_innovation:container@" + level.dimension().location() + ':' + x + ',' + y + ',' + z;
return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));   // UUIDv3（MD5 name-based）
```

三个刻意的性质：

1. **与多方块 UUID 同形**（都是 `UUID`）→ `SharedPackagePool` / `PartialOrderTracker` 的 NBT 格式
   不用变，**本特性无需存档迁移**。
2. **带命名空间前缀** → 位置键永远不会与随机铸出的多方块 UUID 撞上（派生方式完全不同）。
3. **维度隔离** → 不同维度的相同坐标永远不会共享一个池。

**为什么必须先过 `InventoryIdentifier` 归一化**：`VaultIdentity` 会用
`tb.getIdentifiedInventory().identifier()` 把位置**归一化**成 Create 眼中的"那个库存"：

| identifier 类型 | 处理 |
|---|---|
| `InventoryIdentifier.Pair(first, second)` | 取 `first.compareTo(second) <= 0 ? first : second`（**确定性挑一半**） |
| `InventoryIdentifier.Single(pos)` | 用 `single.pos()` |
| 其他 / null | 用 `targetInventory.getTarget().getConnectedPos()` 的原始位置 |

**陷阱：原版双箱子。** Create 把双箱子报告成 `InventoryIdentifier.Pair(半A, 半B)`，两半得到**同一个**
identifier。**如果直接拿机器面对的那个方块位置当键，双箱子的两半会拿到两个不同的键**，
池被劈成两半——症状是"明明有好几台机器贴着同一个箱子，却只有一台在工作"。
归一化之后，贴在哪一半上都映射到同一个键。

**网络型存储：为什么用锚点位置而不是 identifier 本身**

Create: Storage 的 Simple Storage Network 对**网络的每一个方块**（箱子、控制器、接口）都返回**同一个**
`InventoryIdentifier`——一个 `StorageNetworkIdentifier` record，组件是 `(controllerPos, memberPositions)`。

- **不能用它当键**：`memberPositions` 是**成员派生**的，往里加/取一个箱子它的值就变了。
  拿它当池键会像上游 0.5.0 的 BoundingBox 一样**分裂或搁浅**池。
- **用 `controllerPos`（锚点）**：成员变化时锚点不动，是稳定位置。

`StorageNetworkIdentifierMixin` **只做一件事**：

```java
public BlockPos createPackageInnovation$networkAnchorPos() {
    return ((StorageNetworkIdentifier) (Object) this).controllerPos();
}
```

**由此免费得到的三条正确行为**（全部由普通位置键机制自动推出）：

1. **同一网络的所有方块映射到同一个池** → 贴在**不同箱子**上的多台机器也共享同一份工作。
2. **拆网络里的箱子**：`LevelChunkRemovalMixin` 算出那个箱子的位置键，与锚点键**不匹配**
   → `drainAndDrop` 找不到条目 → **什么都不爆**。正确（控制器还在，池还活着）。
3. **拆控制器**：`LevelChunkRemovalMixin` 算出的位置键**正好等于**锚点键 → **整池爆出**。正确。

**为什么这个 mixin 反过来也证明了"接口放普通包"的价值**：`NetworkAnchorAccessor` 在 identity 包，
提到的东西全是原版类型（`BlockPos`），**永远可加载**；`StorageNetworkIdentifier` 这个可选模组的类
只出现在 `mixin/compat/` 里。没装 Create: Storage 的玩家不会 `NoClassDefFoundError`。

### 3.14 【新增】孤儿提示持久化 + `OrphanSweep`：跨存档重启的漏抽兜底

**要解决的问题**：正常路径是事件驱动的（`LevelChunkRemovalMixin` 管单方块，`ConnectivityHandlerMixin`
管多方块全拆）。但总有残留漏洞：某个我们没 hook 的移除路径、或者一次漏抽之后游戏立刻崩了。
**没有兜底的话，那些条目会永远躺在 SavedData 里——包裹和余料被静默丢失，而不是掉回给玩家。**

**两个 half**：

| 类 | 角色 |
|---|---|
| `ContainerHintRegistry` | 内存 half。`Map<MinecraftServer, Map<UUID, Hint>>`（外层 **WeakHashMap**，所以换世界不会泄漏） |
| `ContainerHintStore` | 磁盘 half。独立 SavedData，id **`gdr_container_hints`** |

`Hint` 是 `record (ResourceKey<Level> dimension, BlockPos pos, boolean multiblock)`：

- `multiblock == true` → 这个键是多方块 controller 的 UUID，**附近任何**幸存部件都能让它活着
- `multiblock == false` → 这个键**就是**位置，那个槽位重新有 BE 就说明它还活着

**写频率陷阱（本模组的核心工程细节之一）**：`ContainerHintRegistry.remember(...)` 是**每台机器每 tick
都会调用**的（`VaultIdentity.vaultIdOf` 顺手调）。如果每次都落盘，就是每 tick 一次磁盘写。
所以 `remember` 先比较：**只有 hint 真的出现或变化时才 `ContainerHintStore.put`**：

```java
Hint previous = hints(server).put(key, hint);
if (!hint.equals(previous)) ContainerHintStore.get(server).put(key, hint);   // ← 只在变化时落盘
```

第一个 tick 之后，重复记录同一个位置只是一次 map 比较，什么都不做。

**为什么 `OrphanSweep` 挂在 `ChunkEvent.Load` 而不是服务器启动**：

判断"容器还在不在"必须去看那个位置的世界状态，而 `Level.getBlockEntity`
**会同步加载未加载的区块**。服务器启动时几乎所有区块都是卸载的，所以启动扫描要么强制加载整个世界，
要么什么也判断不出来。挂在区块加载上，那个区块**按定义已经在内存里**，每次查询都只是 map 访问——
而且每个条目会在它的区块下次加载时被重新探测，所以"暂时判不了"的条目只是晚一点解决。

**扫描逻辑**（`OrphanSweep.onChunkLoad`）：

```
1. 只处理 ServerLevel + LevelChunk
2. hints 为空 → return（绝大多数区块加载走这条）
3. 只挑出 dimension 相同、且 (pos.x>>4, pos.z>>4) 等于本次加载区块的 hint
4. 便宜判定优先：chunk.getBlockEntity(pos)
     - 非 null 且 !multiblock  → 槽位被占 → 池可达 → continue（不爆）
     - 非 null 且 multiblock 且 BE 的 UUID == key → 就是它 → continue
     - 非 null 但被别的方块占了 → 落到完整探测（UUID 可能还在扫描半径内的别处）
5. multiblock 的键走两段存活探测（都**不强制加载区块**，都**只读**）：
     a. VaultGeometry.multiblockLivenessNear(...)      // ±11 立方体内的区块安全版
     b. 若 GONE → VaultGeometry.contiguousLivenessNear(...)   // 连通性行走版
     ALIVE / UNKNOWN → continue（UNKNOWN = 有候选区块没加载，等下次区块加载再判）
6. 判定为 GONE → drainAndDrop（池 + 追踪器）+ ContainerHintRegistry.forget(...)
```

**为什么 `b` 必须存在**：见 §3.17——`±11` 半径是按保险库几何定的，对"高度来自配置"的流体罐不够用，
会把**活着的**容器的池爆掉。

**为什么提示必须持久化**：提示曾经只在内存里，那留了一个洞——重启之后所有提示都没了，一个"被我们
没 hook 的路径移除了容器"或"漏抽后立刻崩了"的池**永远无法解决**，包裹烂在 SavedData 里。
持久化提示关掉了这个洞，因为 `OrphanSweep` 在**每次区块加载**时都会重跑，而现在它有位置可以探测了
（`ContainerHintRegistry.hints` 首次访问时从磁盘播种）。

**孤儿兜底的日志是唯一一条无条件打印的**（不受 `DEBUG_LOGGING` 控制），因为它是"物品被救回来了"
这种值得知道的事：

```
[CPI-POOL] orphan sweep dropped a pool whose container is gone (dim=<维度>, pos=<坐标>)
```

另外 `ContainerHintStore.load` 遇到解析不了的维度字符串**只打警告、跳过该条**，绝不让整个存档加载失败：

```
[CPI-POOL] ignoring a container hint with an unparsable dimension '<id>'
```

**格式安全承诺**：提示住在**它们自己的** SavedData 文件里，`SharedPackagePool` 与
`PartialOrderTracker` **格式未变**，所以已有世界的池条目保留它们的键。

### 3.15 【新增】流体打包机：身份要取 `fluidTarget`，不是 `targetInventory`

**问题**：`VaultIdentity.vaultIdOf` 是按 `targetInventory` 找容器的——这对所有物品机器都对。
但 Create: FluidLogistics 的流体打包机是一个 `PackagerBlockEntity`，它的 `targetInventory`
**只是它的物品面**（它的过滤器甚至明确排除了便携流体接口），而它**真正服务的存储**是它那个独立的
`fluidTarget`（一个 `TankManipulationBehaviour`）面对的流体罐。

按物品面计身份的话，流体存储要么解析到某个**毫不相关的物品容器**的键，要么**根本解析不出键**
（流体罐不提供物品能力给 `targetInventory`）。**这正是流体打包机此前从不参与池化的原因。**

**解法：第三个 duck interface**（`identity/` 包，`FluidTargetAccessor`）：

```java
public interface FluidTargetAccessor {
    BlockPos createPackageInnovation$fluidTargetPos();   // null = 回退到普通物品目标
}
```

实现放在 **compat** 配置里（`FluidPackagerBlockEntityMixin`）：

```java
@Mixin(value = FluidPackagerBlockEntity.class, remap = false)
public class FluidPackagerBlockEntityMixin implements FluidTargetAccessor {
    @Shadow public TankManipulationBehaviour fluidTarget;     // 字段名由 fluidlogistics_version 钉住

    @Override
    public BlockPos createPackageInnovation$fluidTargetPos() {
        TankManipulationBehaviour behaviour = fluidTarget;
        if (behaviour == null) return null;
        BlockFace target = behaviour.getTarget();
        return target == null ? null : target.getConnectedPos();
    }
}
```

**`VaultIdentity` 里的两个关键约束**：

```java
BlockPos fluidPos = r instanceof FluidTargetAccessor fluid
        ? fluid.createPackageInnovation$fluidTargetPos()
        : null;
boolean fluidSource = fluidPos != null;
...
// 流体时跳过 identifier 归一化：
if (!fluidSource && tb != null) {
    var identified = tb.getIdentifiedInventory();
    identifier = identified == null ? null : identified.identifier();
}
```

1. **只有实现了 `FluidTargetAccessor` 的机器走流体路径**，别的打包机逐字走原路。
2. **流体来源时绝不能做 identifier 归一化**——那个 identifier 描述的是某个**物品**容器（或什么都没有），
   用它会给流体罐的池键到**错误的方块**上。此时身份就是流体目标的原始位置。
   **正确性优先于归一化。**

> **为什么用 `@Shadow` 而不是 cast**：把字段引用留在 mixin 局部，万一字段名变了，
> 失败模式（`@Shadow` 找不到字段）在日志里一目了然。
> **可选依赖**：本 mixin 在 compat 配置里（`required: false`, `defaultRequire: 0`），
> 没装 FluidLogistics 的整合包会整份跳过这个配置。

### 3.16 【新增】`splitMulti` 必须注在 `TAIL`，以及那条"良性"的 AP 警告

#### 一、TAIL，不是 HEAD【⚠️ 与上游分歧，这是本模组修掉的上游 bug】

**上游的写法**：`@Inject(method = "splitMulti(...)V", at = @At("HEAD"))`。

**本模组的写法**：**`at = @At("TAIL")`**。理由是字节码事实（§3.11 已给出复验值）：

1. `ConnectivityHandler.splitMulti(T)` 的实现**只是转调**——偏移 0–6：
   `aload_0; aconst_null; iconst_0; invokestatic splitMultiAndInvalidate(BlockEntity, SearchCache, boolean)V; return`。
2. **"幸存的方块从旧 controller 继承 UUID"这一步发生在 `splitMultiAndInvalidate` 里面**——
   偏移 353（`getExtraData`）→ 358（`setExtraData`），且都在 369（`removeController`）之前。

**所以在 HEAD 时，幸存方的 `cpi$vaultId` 仍然是 `null`**：
`VaultGeometry.anySiblingVaultWithUuidExists(level, brokenPos, vaultId)` 是按"UUID 相等"找兄弟的，
幸存方的 UUID 还是 null，这个查找**必然落空** → 判定为"全拆" → **爆池**。
而池爆了、订单还在跑 → 下次碎片扫描重新接管 → 重新 craft 一批 → **物品复制**。
这就是上游 0.5.1 第一版上线后立刻暴露的那个 regression。

> 上游看起来"能工作"只是因为**拆到没有 UUID 的部件时会提前 `return`**（它的 handler 在
> `vaultId == null` 时直接返回），那是**巧合，不是判定**。

**改到 TAIL 之后**：extraData 传递已完成，幸存方已持有同一 UUID，判定才真正可靠；
同时也保证重新成形后的多方块沿用同一 UUID（池 key 不变，不会变成孤儿）。

**⚠️ 不要改成 HEAD。** 本仓库 `AGENTS.md` §4 的禁区表也列了这一条。

#### 二、那条 AP 误报，以及为什么它与 §3.9② 的那条**不是一回事**

编译时注解处理器会对下面这行报一条警告：

```
Cannot find target method "splitMulti(Lnet/minecraft/world/level/block/entity/BlockEntity;)V"
```

**这条是误报，可以忽略。** 理由（三条，都可自行复验）：

1. **AP 匹配不了交类型边界形参**。目标方法的真实签名是
   `<T extends BlockEntity & IMultiBlockEntityContainer> void splitMulti(T)`——
   AP 处理不了这种 intersection type bound。
2. **擦除描述符与注解逐字一致**。`javap -p -s` 输出：
   ```
   public static <T extends BlockEntity & IMultiBlockEntityContainer> void splitMulti(T);
   ```
   擦除后就是 `(Lnet/minecraft/world/level/block/entity/BlockEntity;)V`。
3. **类里只有这一个 `splitMulti`（无歧义）**，所以运行时 Mixin 能正常注入。

**⚠️ 但不要把所有 "Cannot find target method" 都当良性。**
§3.9② 里那条针对 **private `splitMultiAndInvalidate`** 的警告**不是**误报——它是因为
`SearchCache` 是 package-private 而**真的注不进去**（运行时会
`InvalidInjectionException`）。区分方法：**看 `javap -p -s` 的擦除描述符是否与注解逐字一致、
且类里是否只有一个同名方法**。两条都满足才是误报。

### 3.17 【新增】连通性行走兜底：为什么 `±11` 半径不再够用，以及它只能"救"不能"杀"

**背景**：`VaultGeometry.MAX_CONTAINER_RADIUS = 11` 是从**保险库**几何倒推出来的
（`getMaxWidth() == 3`、`getMaxLength() == 3*3`，所以长轴最多 9 格）。对保险库和 Item Silo 足够。

**它不够用的地方**：Create 的**流体罐**高度来自 `fluidTankMaxHeight` 服务端配置
（默认 **32**），底面积 3×3，所以合法的大罐体可以有 **~288 个部件**，远远超过任何固定半径。
**一个高罐子从半腰被拆时，所有幸存部件都可能落在这个扫描立方体之外**
→ 判定为"完全没了" → **一个活着的容器的池被爆掉**。

> **未验证**：`fluidTankMaxHeight` 的配置名与默认值 32 取自本模组 javadoc 的论断
> （`VaultGeometry.java` 第 130–131 行的注释）；本机没有 Create 的 all-jar / 源码可复核该配置项，
> 故此处标注。**这不影响结论**——结论本身是"半径是硬编码的、而容器尺寸不是"，只要存在任何一个
> 尺寸可配的容器，兜底就有必要。

**解法：不看几何，跟着容器走。** 两个方法（`VaultGeometry.anyContiguousPartNearby` 与
`contiguousLivenessNear`）改用**部件到部件**的遍历：

- **只走面相邻（6 个方向），绝不走对角**。多方块部件总是面贴面连接的，
  一个**对角**相邻的同方块是**另一个容器**，绝不能继承我们的 UUID。
  （`adoptUuidOnSurvivingParts` 的 ±1 立方体**包含**对角——它被保留在原样，
  但**连通性行走才是精确版本**。）
- **安全阀 `MAX_WALK_POSITIONS = 4096`**：最大的合法容器是流体罐在配置上限（~3×3×32 ≈ 288 部件），
  4096 足够宽裕，同时保证在损坏或意外数据上也能终止。
- **种子是 `origin` 的六个邻居**，不是 `origin` 本身——`origin` 是**刚被移除**的那个方块
  （所以它不再是部件了）。被拆的多方块的**每个**幸存部件都面邻那个被移除的方块，而剩余部件又
  互相连通，所以从邻居播种能到达整个幸存结构——**包括"这次移除把容器劈成两块残余"的情况**。

**两条关键语义（都很讲究）**：

1. **任何活着的同方块 `VaultIdAccessor` 部件都算"找到"（→ 不 drain），即使它带有不同的 UUID。**
   这是**保守**的答案，也保持了旧的 ±1 行为。
2. **行走只"穿过"带有我们的 UUID 或完全没有 UUID 的部件**，所以它**永远不会**游走到一个无关容器上
   并把我们的 UUID 写进去。遇到"已有别的 UUID"的部件：计为幸存（保守，同旧行为），但**不进入、不写入**。

**为什么必须"写入"（adopt）而不只是"看见"**：没有 UUID 的部件会被写上我们的 UUID。
不写的话，重新成形的多方块会铸一个**新** UUID，把池搁浅在旧键上。
**对 extraData 通道已被 Create 占用的容器（流体罐）来说，结构行走是它们通往 reshape 安全的唯一路径**
（见 §3.18）——因为它们根本收不到 propagated UUID。

**"只能救、不能杀"是本设计的核心约束**：

> 两段结构检查（`anyContiguousPartNearby` / `contiguousLivenessNear`）**只在半径检查已经说了
> "没了"之后**才被咨询，所以它们**只可能阻止一次错误的 drain，绝不可能导致一次 drain**。

**`adoptUuidOnSurvivingParts` 为什么用 ±1 而不是全半径**（同一族的第三个方法，在 `splitMulti` 钩子里
作为第 2 段）：

- **多方块是连续的**，所以移除一个方块之后，同多方块的任何幸存部件都与它**相邻**——小半径足够。
- 小半径还能让它**远离**同类型但**离得较远**的无关容器（Create 会把相邻的同种容器合并成一个多方块，
  所以**不相邻的**就是另一个容器，绝不能继承我们的 UUID）。

它的作用是修补 TAIL 方案的一个残余漏洞（"传播被跳过"）：`splitMultiAndInvalidate` 在它的
`getControllerBE()`（或 level）守卫失败时会**提前 return**，那种情况下它从不把旧 controller 的
extraData 推给幸存部件。那些部件的 UUID 保持 `null`，于是第 1 段看不到它们，
一个**仅仅部分被拆**的多方块就会被当成全拆——池被爆掉。
（正常游玩里很难命中：需要 controller BE 无法解析，比如它的区块没加载。）

### 3.18 【新增】流体罐适配器：两处刻意的省略与一个必须接受的代价

`FluidTankBlockEntityMixin` **故意比保险库适配器少两样**。它的 javadoc 标题就是
"Two deliberate omissions (do not "fix" these)"。**不要"补全"它。**

#### 省略 1：不覆写 extraData 三件套

**因为 `FluidTankBlockEntity` 自己已经占用了 Create 的 extraData 通道。**
已用 `javap -c` 在 Create 6.0.8-289 上复验：

```
public java.lang.Object getExtraData();
    0: aload_0
    1: getfield      window:Z
    4: invokestatic  java/lang/Boolean.valueOf:(Z)Ljava/lang/Boolean;
    7: areturn

public void setExtraData(java.lang.Object);
    0: aload_1
    1: instanceof    java/lang/Boolean
    4: ifeq          18
    7: aload_0
    8: aload_1
    9: checkcast     java/lang/Boolean
   12: invokevirtual java/lang/Boolean.booleanValue:()Z
   15: putfield      window:Z
   18: return
```

即：`getExtraData()` 返回 `Boolean.valueOf(window)`，`setExtraData` 把一个 `Boolean` 写回它的
`window` 字段。**我们如果在这里声明三件套，就会静默顶掉 Create 自己的实现**，
破坏罐子的窗口状态传播。

**推论：通道不可用**，罐子的 UUID 只能靠 `VaultGeometry.anyContiguousPartNearby` 的
**结构 adopt 行走**（§3.17）在拆分时写回幸存部件。

#### 省略 2：不挂 `notifyMultiUpdated`

**为什么保险库可以在那里铸 UUID，而罐子不行**：在保险库上，extraData 会在重新成形的 controller 的
`notifyMultiUpdated` **之前**把旧 UUID 交给幸存部件，所以在那里铸 UUID 是安全的。

**罐子没有这个交接**。如果我们挂在 `notifyMultiUpdated` 里铸：重新成形的 controller 在我们的
split 钩子跑之前**就已经持有新 UUID**，而 adopt 行走**拒绝覆写非 null 的 UUID**
（§3.17 语义 2），于是池就**搁浅在旧键上**。

**所以罐子只在首次使用时惰性铸造**（`VaultIdentity` 在 controller 上读到 `null` 时才铸，
见 §3.12 的路线 1），把位置留给 adopt 行走去填。这**同时也解释了为什么这个 mixin 只有
`write`/`read` 两个 `@Inject`**。

#### 必须接受的代价（文档化，别当成 bug 去"修"）

**两个罐子合并时，不会像两个保险库那样被调和。** 保险库有全局 merge-participant set + 最小 UUID 裁决
（§3.11"双容器合并"）；罐子没有这个机制（它们没有 extraData 交接，也就没有 `noteMergeParticipant`
的调用点）。**输掉的那个键的池会由 `OrphanSweep` 作为掉落物回收**，而不是被迁移。
结果是物品**不丢**，但会掉在地上，而不是无缝并入赢家。

#### 另一个已知边界

UUID 只由 `write` 写，**不由 `writeSafe` 写**。所以被**装置（contraption）**吸走的罐子回来后是
**新键**（旧池随后由 orphan sweep 作为掉落物爆出）。这与保险库适配器的行为一致。

### 3.19 【新增】打包机路径入池：父类 `@Redirect` + 生产者无关的"上交" + at-least-once 顺序

**上游只池化理包机**（`feedFromPool` 里有个 `instanceof RepackagerBlockEntity` 早退守卫）。
**本模组让普通打包机也共享池**——收益是：一张大订单不再被交给一台机器然后那台机器每秒只发一个包，
它的邻居在干看着。

**三件套，全部挂在父类 `PackagerBlockEntity` 上**：

**(A) 上交（hand-over）—— 生产者无关的规则。**
`tick` HEAD 里，只要 `!queuedExitingPackages.isEmpty()` 就把整个私有队列交给池，然后清空：

```java
if (hasQueue) {
    List<BigItemStack> batch = new ArrayList<>(self.queuedExitingPackages);
    pool.deposit(vaultKey, batch);
    self.queuedExitingPackages.clear();
    self.setChanged();
    // [CPI-POOL] handed over N queue entr(ies) from packager at ...
}
```

**为什么需要这条**：vanilla 打包机可以直接在 `attemptToSend` 上 hook，理包机在 `attemptToRepackage` 上
hook，但**第三方打包机可以从它自己的类里入队**（FluidLogistics 就是这么做的，从它的
`ResourcePackagerEngine` 和它的理包机子类）——**对这类机器没有单一的接缝可以 redirect**。
改成"排空队列"意味着：**不管是谁生产的，只要它把包裹放进私有队列，就会进入共享。**

**(B) 取 1 个（poll）—— 策略 A。** 见 §2.3②。**上游的 `instanceof Repackager` 守卫被删掉了，
这正是本改动的要点。**

**(C) 直接入池（deposit）。**
父类 `attemptToSend` 是普通打包机入队它组装好的东西的地方；把那个插入 redirect 到池里，
批次**从一开始就不会变成私有的**（不然 (A) 要等一个 tick 才会接手）。
已复验目标：`attemptToSend(List)` 里**唯一**一处
`queuedExitingPackages.add(...)`（偏移 **726: getfield queuedExitingPackages** →
**739: invokeinterface List.add**）。

**⚠️ 故意不写 `ordinal`。** `RepackagerBlockEntityMixin` 的 `List.addAll` 写了 `ordinal = 0`；
这里**不写**——将来 Create 若在这个方法里加第二个 `List.add`，Mixin 会因**歧义响亮失败**，
而不是静默 redirect 错的那个调用。

**为什么不会双重处理理包机**：`RepackagerBlockEntity` **覆写了** `attemptToSend` 且**不调 `super`**
（已 `javap` 确认：子类方法自己的逻辑在偏移 0–102，取件分支在 25–66，全程没有
`invokespecial PackagerBlockEntity.attemptToSend`）。所以**父类级的 redirect 对它根本不跑**——
理包机继续通过它自己的 `RepackagerBlockEntityMixin` 里那条 `attemptToRepackage` 的 `addAll` redirect 入池。
**(A)/(B) 两半对理包机同样生效，这是有意为之的。**

**at-least-once 顺序：先 deposit，后 clear。**

```java
pool.deposit(vaultKey, batch);          // ← ① 先入池
self.queuedExitingPackages.clear();     // ← ② 后清私有队列
```

**反过来的顺序会打开一个窗口：这批包裹既不在队列里、也不在池里。**
按当前顺序，万一两步之间出问题，最坏情况是**重复**一个包裹（可恢复，而且因为两个结构都在内存里，
只可能发生在同一个 tick 内）——**绝不会丢一个**。

**红石**：整条 (A)/(B)/(C) 都**没有红石门**。理由见 §3.9⑦。

### 3.20 【新增】单方块容器：为什么注 `LevelChunk.removeBlockEntity` 而不是 `BlockEntity.setRemoved`

**目标**：单方块容器被移走时爆池（§2.4 表格第三行）。**看起来很明显的接缝是
`BlockEntity.setRemoved()`——但它是错的。**

**`setRemoved()` 在区块卸载时也会被调用**：`LevelChunk.clearAllBlockEntities()` 遍历 BE map 并对
**每一个**条目调它。

> **未验证**：这条原版行为取自本模组 javadoc 的论断（`LevelChunkRemovalMixin.java:29-35`，注明
> "bytecode-verified against 1.20.1-47.4.10"）。本机没有可用的**映射过的原版 Minecraft jar**
> （只有 `mcp/1.20.1-20230612.114412/joined/downloadClient|server` 的混淆 jar 与 Forge 的
> `-sources.jar`），因此**本次未能独立复核该字节码**。此处按现状标注为未验证，
> **不编造偏移**。（若要复核：解出 `forge-1.20.1-47.4.10_mapped_*` 的 `net/minecraft/world/level/chunk/LevelChunk.class`
> 后 `javap -p -c`，看 `clearAllBlockEntities()` 是否对每个条目调 `setRemoved`。）

**在 `setRemoved()` 里 drain 的后果是灾难性的**：玩家每次走远离开那个容器，池里的包裹就全被爆到地上。

**`LevelChunk.removeBlockEntity(BlockPos)` 只在方块替换路径上被到达**
（`setBlockState` → `removeBlockEntity`）；卸载路径清空 map 但**不调它**。所以这个钩子**恰好触发一次**，
对应一次真正的方块移除，**不管是怎么移除的**（挖掉、爆炸、`/setblock`、world-edit、别的 mod）。

**实现要点**（`LevelChunkRemovalMixin`）：

```java
@Inject(method = "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"))
private void createPackageInnovation$onContainerRemoved(BlockPos pos, CallbackInfo ci) {
    ...
    // HEAD 时 BE 还在 chunk 的 BE map 里（map 移除是本方法的第一个动作），所以能查到它
    BlockEntity be = self.getBlockEntity(pos);
    if (be == null) return;
    if (be instanceof IMultiBlockEntityContainer) return;              // 多方块走 ConnectivityHandlerMixin
    if (self.getBlockState(pos).getBlock() == be.getBlockState().getBlock()) return;  // 同方块换回来：键不变，不爆
    ...
    UUID key = VaultIdentity.positionKey(level, pos);
    SharedPackagePool.get(server).drainAndDrop(key, level, pos);
    PartialOrderTracker.get(server).drainAndDrop(key, level, pos);
    ContainerHintRegistry.forget(server, key);
}
```

三条判断各有理由：

1. **`instanceof IMultiBlockEntityContainer` 直接 return**：多方块走 `ConnectivityHandlerMixin`，
   那里还要额外区分部分拆与全拆。而且它们的池是按 UUID 键的，在这里用位置查询本来就是空操作——
   这行是**显式**表达这个事实。
2. **同方块换回来不爆**：同一个容器方块被放回同一个位置时，位置键**没变**，池里的一切通过新的 BE
   依然可达。爆掉它反而是错的。
3. **`ContainerHintRegistry.forget(server, key)`**：键已经"解决"了，就从内存**和磁盘**一起清掉。
   不清的话那条持久化提示会一直留着，直到某次区块加载探测它发现什么都没有（那时它会自清理，
   但代价是在存档里留一条死条目）。

**remap 陷阱（唯一一个必须可 remap 的 mixin）**：

> 目标是**原版类**，所以这个 mixin **必须保持可 remap**（**不要**加 `remap = false`）——
> 生成的 refmap 会把 `removeBlockEntity` 翻译成它的生产环境（SRG）名。
> 本包里其它注明 `remap = false` 的 mixin 注的是 **Create** 类，它们自己的方法名从不被混淆；
> **那条理由不适用于原版目标**。给原版 mixin 加 `remap = false` 会让它在生产环境注不进去。

**为什么它在主配置里**（`required = true`, `defaultRequire = 1`）：如果它注不进去，池会照常工作，
但**没有任何东西会在单方块容器被移除时把它爆出来**——物品静默丢失。**响亮地失败是正确的结果。**

---

## 4. 后续开发方向

### 4.1 动态负载均衡（上游思路 B 的历史，本模组已完成）

**上游思路 A 的局限**：上游 0.3.0 的快照分配是"一次性快照"——在 repack 完成那一刻决定谁分多少，
再靠动态再平衡补静态性。两层逻辑各自有阈值、有 sibling 发现、有守恒校验，代码复杂。

**上游思路 B 评估了两条路径，最终 0.4.0 实现了路径 ②（真·共享池）。本模组完整继承路径 ②。**

#### 路径 ①：动态再平衡（上游 0.3.0 实现，0.4.0 已废弃，本模组从未有）

**思路**：不动发送状态机，只在 `attemptToSend` 头部加一道再平衡——把"队列最深者"队尾的一小部分包
偷给"队列最浅者"队尾。

**为什么上游 0.3.0 选它**：完全不碰 `tick()` / `heldBox` / `extractItem`（§3.8 被动清空协议），零死锁风险。

**为什么废弃**：路径 ②（共享池）用更低复杂度达成了相同效果（天然动态均衡，无需再平衡层）。
**本模组没有任何再平衡代码，也没有相关日志**——不要去找 `[CPI-REBAL]`，它不存在。

#### 路径 ②：真·共享池（本模组已实现）

**思路**：per-容器全局 `SharedPackagePool`（SavedData）。winner repack 的整批**入共享池**，
各机器 `tick()` 时从池里 poll。

**关键决策——策略 A（tick HEAD 灌队列）取代策略 B（拦 `get(0)`）**：

| | 做法 | 风险 |
|---|---|---|
| **策略 A（本模组采用）** | `@Inject` 挂 tick HEAD，空闲时往私有队列尾部 add 1 个包；vanilla tick 紧接着从队首取 | 私有队列 0~1 元素，heldBox 协议不动，**零死锁** |
| 策略 B（未采用） | `@Redirect` 拦 `get(0)`，池直接成取件源；须接管 heldBox 生命周期（§3.8） | 死锁/复制风险高 |

两种策略玩家体感几乎无别（都是空闲机动态取活），但策略 A 风险低一个数量级。
**偏离上游早期文档的字面描述是刻意的**——字节码已摸透 heldBox 被动清空协议，没必要走高危路。

**实现细节见 §2.3。注入点见 §2.2。**

### 4.2 其它可能方向

- **配置文件**：本模组目前**没有**自己的 `Config` 类（`CreatePackageInnovation` 的构造函数里那句
  `Config.SPEC` 只是给未来留的**注释**，不是可用的注册代码——`Config` 类**不存在**）。
  想加配置就从零写一个，并注意流体罐高度来自 **Create 自己的** `fluidTankMaxHeight`（§3.17）。
- **支持更多网络型存储**：AE2 / RS 目前仍按单个方块计身份（README 已写明）。补法就是写一个
  `NetworkAnchorAccessor` 的实现——照 `StorageNetworkIdentifierMixin` 的样子。
- **Fabric / NeoForge 移植**：Mixin 本体大部分可复用，但入口、注册、依赖坐标都要重写。
- **Create 版本跟进**：Create 更新后，`attemptToRepackage` / `attemptToSend` / `splitMulti` 的
  偏移与目标描述符可能变化。**需要针对新版本用 `javap` 重新验证每一个注入点**（方法论见 §6.3），
  尤其是 `splitMulti` 的 TAIL 语义前提（§3.16）与 `List.add` 的唯一性（§3.19）。

### 4.3 部分重组：历次失败路线与成功路线（上游经验的移植）

**状态：已实现并通过上游测试，本模组完整继承。** 本节记录三次失败尝试（教训仍是未来改动的红线），
再记录第四次成功的路线。实现架构见 §2.5，安全不变量见 §3.10。

**目标**：原版 `isOrderComplete` 是 all-or-nothing 闸门（碎片网格不全即 false）。碎片经运输网络
异步流式到达，大订单"等最后一片"窗口长。希望让理包机在已到碎片够合成至少一次时就开工。

#### 尝试 1（直接用 repackBasedOnRecipes 输出）— 物品复制 + 无限堆积

**做法**：`@Inject` `attemptToSend` HEAD，扫描容器碎片 → 调 `repackBasedOnRecipes` 生成合成包裹 →
自己扣减碎片 NBT → deposit 共享池。

**症状**：停掉运输链动力后，理包机输出"含大量铁锭、发往输入容器的包裹"，且工作异常、物品复制。

**根因**（三层叠加）：
1. `repackBasedOnRecipes` 输出是**孤儿碎片格式**（随机 orderId、无 address、带 Fragment tag）。
   `setOrder` 无条件写 Fragment tag。
2. 孤儿 box 被机器外抽后，因无 address 被路由**回到输入容器** → `addPackageFragment` 收下 →
   `isOrderComplete` 因随机 orderId 永远 false → 无限堆积。
3. 自己改库存 NBT 绕过 `VersionedInventoryWrapper` 版本号自增 → 库存快照不同步 → 重复处理 = 复制。

**教训**：`repackBasedOnRecipes` 的输出是 `repack()` 的中间产物，不能直接用。合成必须走完整 `repack()`
（它做 addAddress + 一致 orderId + 余料 fallback）。

#### 尝试 2（拦 addPackageFragment 返回值 + 复用原版 repack）— 触发过早

**做法**：用只读探针验证了拓扑隔离成立后，`@Inject` `addPackageFragment` RETURN：返回 -1 时，
调 `repackBasedOnRecipes` 判断"够不够合成"，够则覆盖返回值为 orderId → 触发原版 repack 路径。

**症状**：产物数量严重不符——下单 3 组铁块只出 2 组，10 组只出 3 组，"丢得越来越多"。

**根因**：`attemptToRepackage` 扫描循环逐个 slot 调 `addPackageFragment`。`@Inject RETURN` 在**第一次**
某 orderId 碎片进来、判断"够合成"后立即 `setReturnValue(orderId)` → 扫描循环
**偏移 108–113 立即 `goto 122` 跳出**。于是 `collectedPackages` 只收到 **1 个**碎片。
但原版 repack 后的第二轮 extract（**偏移 147–215**）删除**容器里所有** `getOrderId==orderId` 的碎片。
**repack 只消耗 1 片，extract 删了 N 片 → 丢失 N-1 片的内容物**。

**教训**：必须保证 `repack 消耗的碎片集合 == extract 删除的碎片集合`。在 `addPackageFragment`
层面触发，无法控制 `collectedPackages` 收齐所有碎片（扫描循环会被跳出打断）。

#### 尝试 3（未实现，分析阶段放弃）— 拟改注入 attemptToRepackage

**设想**：换注入点到 `attemptToRepackage`，扫描容器把所有同 orderId 碎片塞进 `collectedPackages`
后再触发 repack。但分析后发现仍有未验证细节（多机并发谁塞料、塞料与容器实时状态的一致性、
repack 对手动塞入碎片的处理）。**暂停实现，挂起需求。**

#### 尝试 4（成功）— 订单接管 + 余料托管 + 末班 vanilla 收尾

**关键转折**：第四次调研（全量字节码重读 `PackageRepackageHelper`/`PackageItem`/`PackagerBlockEntity`）
推翻了三个阻碍前三次尝试的认知：

1. **`repackBasedOnRecipes` 天生支持部分合成**——它按 `min(订单数, 材料够的次数)` 合成并原地消耗
   材料池。all-or-nothing 只在 `isOrderComplete` 一层，合成层本来就是渐进的。
2. **每个碎片都携带完整订单上下文**——打包机生成碎片时对每片 `setOrder(..., request.context())`。
   任意一片到达即知整单配方，无需等齐。
3. **材料守恒自动限制总合成数**——stockkeeper 发货总量 == 订单总量，跨趟累计合成数被材料卡住，
   无需跟踪"已合成次数"。

**路线**（详见 §2.5）：不撬 `isOrderComplete` 的闸门（完整订单照旧走 vanilla），只对**不完整**
订单进行"接管"——部分趟用 `repackBasedOnRecipes` 渐进合成、余料托管 SavedData、末班用内存伪碎片
回调 vanilla `repack()` 收尾。三个历史失败点全部结构性规避：

- 尝试 1 的孤儿格式/复制：产物只出自 vanilla 方法 + addAddress；库存只走 `extractItem` 公开 API
- 尝试 2 的消耗≠删除：预扫描跑满全槽无 early break，收集集合 == 删除集合（结构性保证）
- 尝试 3 的未验证细节：多机并发由服务器单线程串行化自然解决；余料一致性由 SavedData 托管

**为什么这个需求曾经这么难**：Create 的碎片/repack 机制有三个微妙耦合——
- `isOrderComplete` 是 all-or-nothing 闸门，部分触发必然打破它隐含的"一次性全处理"语义；
- `repack` 消耗 `collectedPackages`，但 extract 删容器，两者集合必须手动保证相等；
- 合成结果 box 带 Fragment，安全靠拓扑隔离（不回输入容器），任何扰动都可能打破。

第四次的洞察是：**不碰闸门，而是接管订单的整个生命周期**——闸门只服务 vanilla 路径，
被接管的订单由 tracker 的累计网格判定末班，合成与收尾全部回调 vanilla 自己的方法。

---

## 5. 开发环境复现

### 5.1 真实的构建面

- **本仓库没有 `g.sh`**（上游有）。构建用 `gradlew.bat` 或 IDE。
- **没有 alpha jar 分类器**：`build.gradle` / `gradle.properties` 里**没有** `archiveClassifier`、
  **没有** `mod_is_alpha`（上游有 `mod_is_alpha=true`）。不要再去找 `-alpha` 后缀的 jar。
- **没有** `PUBLISH_mmcmod.md` / `PUBLISH_curseforge.md` / `CREDITS.txt` / `changelog.txt`（上游有）。
  发布时**不要**按上游的结构去新建它们。
- **JDK 17**（`build.gradle` 的 `java.toolchain.languageVersion = JavaLanguageVersion.of(17)`）。
  必须是 17，不能是 21/22（否则编译期就会撞 `Unsupported class file major version 66` 之类的错误）。
- `org.gradle.daemon=false`：每次构建都起新 daemon，**慢是正常的**，不是卡住了。

### 5.2 产出的 jar 名（从 `gradle.properties` 推导）

> ✅ **已实测**（用户构建产物）：`build/libs/create_package_innovation-1.0.0.jar`（75,857 字节）。jar 内含
> `META-INF/mods.toml`、`Logo.png`（**在 jar 根**，正好对应 `logoFile="Logo.png"`）、两个 mixin json、
> `create_package_innovation.refmap.json` 与 `pack.mcmeta`；manifest 的 `MixinConfigs` 与版本号均正确；
> 产物内**不含**任何上游参考 jar（条目名匹配 `goddamnrepackager` 为 0 条）。

```
base { archivesName = mod_id }          // build.gradle → archivesName = create_package_innovation
version = mod_version                   // build.gradle → 1.0.0
```

→ **`build/libs/create_package_innovation-1.0.0.jar`**

无 `-forge`、无 `-alpha`、无 classifier。`mods.toml` 里显示的版本是
`${minecraft_version}-${mod_version}` = **`1.20.1-1.0.0`**。

**改版本号只需要改 `gradle.properties` 的 `mod_version`**——`mods.toml` **不用改**
（它写的是占位符，由 `processResources` 展开）。

> **Windows 上必须钉 UTF-8**：`tasks.named('processResources')` 里显式设了
> `filteringCharset = 'UTF-8'`。`expand()` 默认按平台编码读写，在中文 Windows（`file.encoding=GBK`）上
> 会把 `mods.toml` 里的中文全部搞坏，包括会显示在模组列表里的 `displayName` 与 `description`。
>
> 另一个 `mods.toml` 约束：**注释里不要写出"美元符号紧跟大括号"的写法**——`expand()` 走 Groovy 模板
> 引擎，会把它当表达式解析并报 `Unexpected input`，整个 `processResources` 任务失败。

### 5.3 关键依赖（见 `build.gradle` 与 `gradle.properties`）

| 依赖 | 版本 | 用途 |
|---|---|---|
| Minecraft | `1.20.1`（`minecraft_version_range=[1.20.1,1.21)`） | 目标版本 |
| Forge | `47.4.10` | 加载器 |
| Create | `6.0.8-289` | 被修改的模组（用 `:slim` + 显式 Ponder/Flywheel/Registrate） |
| Ponder / Flywheel / Registrate | `1.0.91` / `1.0.5` / `MC1.20-1.3.3` | `:slim` 不带的内嵌依赖 |
| MixinGradle | `0.7-SNAPSHOT` | Mixin 配置注册（FG6 必需，见 §3.1） |
| MixinExtras | `0.4.1` | Create 及其附属用了它 |
| Create: Connected | `1.2.3-mc1.20.1` | **可选**：Item Silo 适配器 |
| Create: FluidLogistics | `1.3.0-mc1.20.1` | **可选**：流体打包机适配器 |
| Create: Storage | `oSsfZYxj`（= 1.2.7，1.20.1 Forge） | **可选**：网络锚点适配器。用**版本 ID** 而不是版本号——它的 `1.2.7` 在 Forge(1.20.1) 与 NeoForge(1.21.1) 两个构建上完全相同，Modrinth 仓库按版本号无法区分 |
| JEI | `15.56.0.205` | 仅 dev 环境用（编译期 API + 运行期完整 jar），用于下合成订单测试 |
| Parchment 映射 | `2023.08.20-1.20.1` | 机械动力 6.0.x 用这个版本构建，附属模组沿用同一版本，否则 Create 的 mixin refmap 在开发环境无法正确应用 |

三个 Create 附属模组都只在 Modrinth 发布，用 `exclusiveContent` 把 `maven.modrinth` 这个 group 独占给
Modrinth（`forRepositories(fg.repository)` 是 ForgeGradle 的写法，少了它 Forge 自身的依赖会解析不到）。

`mods.toml` 里这三个是**可选依赖**（`mandatory=false`、`ordering="AFTER"`、`versionRange="[0,)"`）。
它们的 `modId` 是按各模组 jar 文件名推出来的，**换版本后请在游戏的模组列表里复核**：

```
create_connected-1.2.3-mc1.20.1-all.jar   -> create_connected
fxntstorage-1.2.7+mc-1.20.1-forge.jar     -> fxntstorage
fluidlogistics-1.3.0-mc1.20.1.jar         -> fluidlogistics
```

### 5.4 ⛔ 关于"AI 能不能自己编译"

本仓库的 `AGENTS.md` §2 记录了本机的一个硬约束：**用户的 Gradle home 在工作区之外**
（`E:\IntelliJ IDEA\ontology\gradle\caches`），而 agent 沙箱只允许写会话工作区，
所以 **`gradlew build` 在 agent 会话里跑不起来**。推论：

1. **保持每次改动小到能靠阅读验证。**
2. **每次编辑后静态自检**：`import` 是否都存在、被调方法签名是否与调用处一致、泛型是否对得上、
   `@Inject`/`@Redirect` 的 `method` 描述符是否与 `javap` 输出逐字一致。
3. **用户的 IDE 是唯一的编译闸门。** 交提案时明说"我无法编译，请你编译"。
4. **编译通过 ≠ Mixin 注入正确。** 注入点可以在编译期完全合法、运行时却注到错误的目标或用了错误的
   `ordinal`（§3.9、§3.19、§6.3）。
5. **行为改动必须附可执行的验证方案**：放什么方块、拆什么、看什么现象、日志里该出现哪一行。

---

## 6. 调试技巧

### 6.1 开启诊断日志

把 `CreatePackageInnovation.DEBUG_LOGGING` 改成 `true` 重新编译（默认 `false`）。
日志标记分两个前缀：**`[CPI-POOL]`** 与 **`[CPI-PARTIAL]`**。

**`[CPI-POOL]`（池的动作）**

| 日志行 | 触发点 | 说明 |
|---|---|---|
| `deposited N package(s) from <pos> (vault pending: M)` | `RepackagerBlockEntityMixin` 的 `addAll` redirect | 理包机整理后整批入池；`N` 按 Σ`max(1,count)` 计 |
| `deposited 1 package from packager at <pos> (vault pending: M)` | `PackagerBlockEntityMixin` 的 `List.add` redirect | **普通打包机**（或任何走父类 `attemptToSend` 的机器）装好 1 个直接入池 |
| `handed over N queue entr(ies) from packager at <pos> (vault pending: M)` | `tick` HEAD 的上交分支 | 任何来源的私有积压被交给池（§3.19A） |
| `fed 1 package to packager at <pos> (vault pending: M)` | `tick` HEAD 的取件分支 | 空闲机器取走 1 个（**不是** `repackager`——普通打包机也取） |
| `drained & dropped N package(s) at vault <pos>` | `SharedPackagePool.drainAndDrop` | 容器**全拆**，整池爆出。**部分拆不该出现这行**（§3.16） |
| `two-vault merge resolved: winner=<uuid>, migrated N package(s) to winner` | `SharedPackagePool.resolveMergeWinner` | 两个独立容器合并，输家池迁到赢家（罕见） |
| `orphan sweep dropped a pool whose container is gone (dim=…, pos=…)` | `OrphanSweep` | 漏抽兜底。**无条件打印**，不受 `DEBUG_LOGGING` 控制 |
| `detected legacy BoundingBox-keyed SavedData (N vault(s), ~M package(s)); clearing pool on upgrade...` | `SharedPackagePool.load` | **警告级**，升级清空（§3.9⑧）。无条件打印 |
| `ignoring a container hint with an unparsable dimension '<id>'` | `ContainerHintStore.dimensionOf` | **警告级**，坏存档条目不致命。无条件打印 |

**`[CPI-PARTIAL]`（部分重组）**

| 日志行 | 触发点 | 说明 |
|---|---|---|
| `crafted N package(s) for order O at <pos> (F fragment(s) consumed)` | `PartialRepackager.tryPartialPass` | 部分趟渐进合成了一批 |
| `final pass for order O at <pos>: N package(s) exported, tracker cleared` | `PartialRepackager.tryPartialPass` | 末班 vanilla `repack()` 收尾完成，tracker 注销 |
| `final repack failed for order O: <异常>` | `PartialRepackager.tryPartialPass` | **警告级**。`repack()` 抛异常时的防御分支：碎片与 tracker **原样保留**，vanilla 空转，下一 tick 重试。**这不是致命错误** |
| `drained N item(s) from M tracked order(s) at vault <pos>` | `PartialOrderTracker.drainAndDrop` | 容器全拆时爆余料（**与池 drain 同一时机**） |
| `migrated M tracked order(s) on vault merge <old> -> <new>` | `PartialOrderTracker.migrateKey` | 双容器合并时余料迁移 |
| `detected legacy BoundingBox-keyed SavedData (N tracked order(s)); clearing on upgrade...` | `PartialOrderTracker.load` | **警告级**，升级清空。无条件打印 |

**已随架构删除、不会再出现的日志**（上游 0.3.0/0.4.0 时代）：
`[GDR-DIST]`、`[GDR-REBAL]`、`SPLIT MISMATCH`、`REBALANCE MISMATCH`、`[GDR-PARTIAL] migrated M tracked
order(s)`（reshape 迁移版，本模组 reshape 不迁移，key 不变）。
**看到 `[GDR-` 开头的行说明装的是上游 jar，不是本模组。**

### 6.2 常见问题排查表

| 现象 | 可能原因 / 处理 |
|---|---|
| Mixin 不触发，**无报错** | MixinGradle 没配好，refmap 没生成（§3.1）。查 run.log 里有没有 `create_package_innovation.refmap.json` |
| 新增的 mixin 完全不起作用 | 忘了把类登记进对应 json 的 `mixins` 数组（§2.1） |
| `mixin class is invalid` / `Entry …refmap.json is a duplicate` | refmap 缺失或重复（§3.1、§3.2）。§3.2 的情况需要 `clean build` |
| 进世界崩溃 `InvalidInjectionException`（Expected …SearchCache… but found …） | 注了 private `splitMultiAndInvalidate` 且 handler 跳过了 SearchCache 参数。Mixin 要求 handler 匹配目标完整参数列表。改注 public `splitMulti`（§3.9②） |
| 编译期警告 `Cannot find target method "splitMulti(...)V"` | **误报，可忽略**——AP 匹配不了交类型边界。擦除描述符与注解逐字一致，且类里只有这一个 `splitMulti`（§3.16二）。**但**针对 `splitMultiAndInvalidate` 的同名警告**不是**误报（§3.9②） |
| 游戏启动崩溃 `non-private static method`（`checkMethodVisibility`） | mixin 类里有 public/static 普通方法。共享 helper 必须放独立工具类，或改成 `private` 实例方法（§3.9⑤） |
| 游戏启动崩溃（tick 相关） | tick 注入点写错：必须注父类 `PackagerBlockEntity`（§3.9①） |
| 游戏启动崩溃 `IllegalClassLoadError: … is in a defined mixin package` | 有代码直接引用了注册在 `mixin` 包里的类。duck interface 必须放**普通包**（如 `identity/`，§3.11 末段） |
| 游戏启动崩溃（打开仓库管理员时） | `@Inject` 参数类型和目标方法不匹配（§3.3） |
| **打包机/理包机"吞物品"、订单静默停摆** | 有人给池的灌入加回了 `if (!self.redstonePowered) return;`。**删掉它**——vanilla `tick()` 没有红石检查，加闸门会让我们比原版更严：已被交给池、之后红石又被切断的包裹永远取不回来（§3.9⑦） |
| 打包机无需红石也工作（觉得是 bug） | **这不是 bug**，是刻意与 vanilla 对齐（§3.9⑦）。发出去的包裹本来就是 vanilla 无条件排空队列的行为 |
| **包裹卡在一台机器的 `heldBox` 里、队列再也不动** | 下游抽不动（库存满 / 无下游 / 下游被拆）。`heldBox` 只能被下游 `PackagerItemHandler.extractItem` **被动**清空（§3.8）。**修下游，不要去改 heldBox**。注意这是 vanilla 行为，不是本模组引入的——但本模组的池会因此把活流向别处，所以整体吞吐仍在 |
| **下游堵塞时积压不消散** | 确认堵塞机被 `!heldBox.isEmpty()` 守卫挡住：开 DEBUG 看 `fed 1 package` 是否只出现在**非**堵塞机上（§2.3②） |
| **拆容器时整池被爆出来（本应部分拆）** | `splitMulti` 的注入点被改回了 `HEAD`（§3.16一）。必须留在 **TAIL**——HEAD 时幸存方 UUID 还是 null，"找同 UUID 兄弟"必然落空 |
| **拆容器一个方块后，机器继续合成、同时又爆出物品** | 同上。正确行为：**拆一个 part 不应出现 `drained & dropped` 日志**。若出现了，先查注入点是不是 TAIL，再查三段判定链（§3.11、§3.17） |
| **高流体罐从半腰被拆，池被爆出来** | §3.17 的连通性行走兜底没跑（或第三段判定被删掉）。`±11` 半径对"高度来自配置"的罐子不够用 |
| **容器明明还在，池却被爆出来（漏抽兜底误判）** | `OrphanSweep` 把 `Liveness.UNKNOWN` 当成了 `GONE`。区块没加载时必须判 UNKNOWN 并留到下次区块加载（§3.14）。看日志里有没有 `orphan sweep dropped` |
| **完全没有任何池化（`[CPI-POOL]` 一行都没有）** | 身份解析返回了 `null`，三条路都可能是原因：① 目标不是容器 / `getTarget()` 还是 null（`targetInventory` 未就绪）；② **多方块容器没有适配器 mixin** → 故意返回 null 不池化（§3.12）；③ 客户端侧（`level.isClientSide`）。**不要**为了让 ② 有输出就去加"退化成位置键"的兜底——那是丢物品 |
| **流体打包机不池化** | Compat 配置没生效（FluidLogistics 没装 / 版本不匹配 / 类没在 `mixin/compat/`），或 `fluidTarget` 还没链接上目标（`getTarget()` 返回 null → `createPackageInnovation$fluidTargetPos()` 返回 null → 回退到物品面）（§3.15） |
| **装了 Create: Connected / Storage / FluidLogistics 但对应功能不生效** | 看启动日志有没有 compat 配置的警告——它是 `required=false`，**注入失败只打警告、不拦启动**。同时核对 `mods.toml` 里的 `modId` 是否与各模组 jar 推出的名字一致（§5.3） |
| **同一个箱子贴了多台机器却只有一台工作** | 双箱子被劈成两个键了。`VaultIdentity` 必须先用 `InventoryIdentifier.Pair` 做归一化再派生位置键（§3.13） |
| **拆网络里的箱子，池却被爆了** | 网络锚点没解析出来（`NetworkAnchorAccessor` 返回 null → 退化成"那个箱子自己的位置键"）。那就不是锚点键，删除时自然会命中。查 compat mixin 是否生效（§3.13） |
| **拆掉控制器但什么都没爆** | 反过来的情形：锚点解析到了**别的**位置，或 `LevelChunkRemovalMixin` 没生效（它是主配置，注不进应当启动崩，见 §3.20） |
| **玩家走远（区块卸载）时池被爆出来** | 有人在 `BlockEntity.setRemoved()` 里 drain 了。**必须**注 `LevelChunk.removeBlockEntity`——`setRemoved` 在区块卸载时也会被调用（§3.20） |
| **同一个容器方块被放回原位，池却被爆了** | `LevelChunkRemovalMixin` 里"同方块换回来不爆"那条判断被删了（§3.20 第 2 条） |
| **打掉机器后包裹"消失"** | **这不是 bug**——共享池跟着存档走，包裹在池里，重放机器即恢复（§3.9④）。只有打掉/扳手拆除**容器**才爆池；reshape 不爆 |
| **重启后池里的包裹再也取不出来** | `ContainerHintRegistry` / `ContainerHintStore` 被改成只内存了。持久化提示是跨存档兜底的前提（§3.14） |
| **每 tick 都在写磁盘 / 存档卡顿** | `ContainerHintRegistry.remember` 里的变更比较被去掉了——`remember` **每台机器每 tick** 都跑，落盘必须只在 hint 变化时发生（§3.14） |
| **升级后池被清空** | 从上游 0.5.0 的 BoundingBox-keyed 存档升级。`load()` 检测到旧格式会 WARN + 清空（alpha break，§3.9⑧）。让在跑订单跑完再升级。**本模组之间升级不会再有这个 break**（key 恒为 UUID） |
| **两个容器合并后一部分包裹掉在地上** | 流体罐合并**没有** merge 调和（§3.18 代价）——`OrphanSweep` 把输家的池作为掉落物回收。保险库合并则走最小 UUID 裁决（§3.11） |
| **产物数量不对（并行）** | 开 `DEBUG_LOGGING` 对账：`deposited` 总量是否 == 订单数，`fed` 累计是否 == deposited。共享池是纯搬运 + count 拆分，不等说明 poll/deposit/上交逻辑错（§2.6、§3.5） |
| **产物数量不对（部分重组）** | 开 DEBUG 对账：所有 `crafted` + `final pass` 的数量之和应 == 订单合成数。不等就逐条查 §3.10 五条不变量（重点：count≤0 过滤、消耗==删除） |
| **订单碎片到齐前不动工（无 `[CPI-PARTIAL]` 日志）** | ① 确认订单含**合成**配方（纯物品订单永不接管，等齐走 vanilla——设计如此）。② 确认至少有一台机器在推进。③ 确认身份解析非 null（是容器，不是没配适配器的多方块）。④ 确认已到材料够合成至少 1 次 |
| **渐进合成中订单卡住不动** | ① 是不是所有机器都被拆了 / 都断电了——接管中的订单需要至少一台机器推进。② 开 DEBUG 看是否还有 `crafted` / `final pass`；若长时间无动静且材料已到齐，查 `TrackedOrder.wouldComplete` 的末班判定（§2.5） |
| **拆容器后材料对不上** | 库内未消费碎片（vanilla 掉落）+ `[CPI-PARTIAL] drained` 爆出的余料 + 已发出的产物 == 已发货材料。三者 disjoint；对不上说明 extract/托管逻辑被破坏（§3.10） |
| **看到 `[GDR-` 前缀的日志** | 装的是上游 jar，不是本模组。本模组只用 `[CPI-POOL]` / `[CPI-PARTIAL]` |
| **配置项找不到** | 本模组**没有**自己的 `Config` 类（§4.2）。流体罐高度那类参数来自 Create 自己的配置 |

### 6.3 分析方法论：不要凭记忆猜 Create API

本项目的诸多决策靠反编译 Create 字节码做依据。**本机已验证可用的做法（PowerShell）**：

```powershell
# 已在本机确认存在的 slim jar（含本次需要的全部类：PackagerBlockEntity、
# RepackagerBlockEntity、ItemVaultBlockEntity、FluidTankBlockEntity、
# ConnectivityHandler、PackageRepackageHelper）
$jar = 'C:\Users\26423\.gradle\caches\forge_gradle\deobf_dependencies\com\simibubi\create\create-1.20.1\6.0.8-289_mapped_parchment_2023.08.20-1.20.1\create-1.20.1-6.0.8-289_mapped_parchment_2023.08.20-1.20.1-slim.jar'

& "$env:JAVA_HOME\bin\javap" -p -cp $jar <全限定类名>                                    # 1) 先看字段与方法签名
& "$env:JAVA_HOME\bin\javap" -p -c -cp $jar <全限定类名> | Select-String -Pattern '<关键字>'  # 2) 再看字节码
& "$env:JAVA_HOME\bin\javap" -p -s -cp $jar <全限定类名>                                 # 3) 看擦除描述符（校验 @Inject 的 method 串）
```

**五条方法论**：

1. **先字段（`-p`）再方法（`-c`）。** 用 `getfield`/`putfield` 定位字段被谁读写，
   用 `invokevirtual`/`invokeinterface` 追调用链。
   > 注意：`-c` 里的**字节码偏移会随 `javap` 版本与选项变化**。引用偏移时务必连同
   > "在哪台 jar、用了什么命令"一起记下来（本文档的偏移都注明取自 6.0.8-289 slim jar + `javap -p -c`）。
2. **改注入点前必须确认目标调用的 `ordinal`**：在 `javap -c` 输出里**数**同名调用的出现次数。
   本模组：`RepackagerBlockEntityMixin` 的 `List.addAll` 写了 `ordinal = 0`（已确认整类只有 1 处）；
   `PackagerBlockEntityMixin` 的 `List.add` **故意不写 ordinal**（§3.19）——将来 Create 若加第二个，
   Mixin 会因歧义**响亮失败**，而不是静默注错。
3. **假设"注进父类就对子类生效"之前，先查子类是否覆写。**
   `RepackagerBlockEntity` **覆写了** `attemptToSend` 且不调 `super`，所以父类级的 `@Redirect List.add`
   对它根本不跑（§3.19）。反过来，两者都**没有**覆写 `tick()`，所以 `tick` 只需注父类一次（§3.9①）。
4. **`@Inject` 用 `method = "名字(描述符)V"`**（如 `"notifyMultiUpdated()V"`、
   `"write(Lnet/minecraft/nbt/CompoundTag;Z)V"`、
   `"splitMulti(Lnet/minecraft/world/level/block/entity/BlockEntity;)V"`）。
   **描述符写错编译能过、运行时不注入。**
5. **注入点改变语义时，先确认被注入方法的实现**。`splitMulti` 的 HEAD/TAIL 之别
   （§3.16一）就是一条纯粹的字节码事实：它只是转调，真正的副作用在下一层。

**不要用 `unzip -o`**（写操作）。要解压就解到全新临时目录。

**可选模组的类**（Create: Connected 的 `hlysine.*`、Create: Storage 的 `net.fxnt.*`、
FluidLogistics 的 `com.yision.*`）在本机**没有对应的 jar**，本文档对它们只有源码层面的论断
（见 §6.5）。

### 6.4 § 编号对照表（代码注释 → 本文档）

本仓库代码注释里的 `§x.y` 引用最初指向**上游** TECHNICAL.md。下表把每一个引用映射到本文档的对应小节，
并说明该主题在本仓库的状态。**新增主题一律追加新编号，绝不重排上游编号。**

| 引用（代码里出现处） | 本文档对应小节 | 本仓库状态 |
|---|---|---|
| §2.2（`RepackagerBlockEntityMixin.java:45`） | §2.3 核心算法：共享包裹池 | 一致（上游 §2.2 = 共享池算法） |
| §3.5（`SharedPackagePool.java:58`） | §3.5 `BigItemStack.count` 才是真正的包裹数 | **一致，未变** |
| §3.7（`RepackagerBlockEntityMixin.java:49`） | §3.7 能力实例身份匹配对缓存"代次"敏感 | 一致（结论沿用；但上游的 BoundingBox 用途已被 UUID 取代） |
| §3.8（本仓库 `AGENTS.md` §4；上游 `PackagerBlockEntityMixin` javadoc） | §3.8 heldBox 被动清空协议 | **一致，未变**（load-bearing，必须保留） |
| §3.9②（`ConnectivityHandlerMixin.java:68`） | §3.9② `splitMulti` 可见性：不能注 private `splitMultiAndInvalidate` | 一致 |
| §3.9③（`VaultGeometry.java:46`、`ConnectivityHandlerMixin.java:28,98`） | §3.9③ `isController()` / `getControllerBE()` footgun | 一致 |
| §3.9⑤（`VaultIdentity.java:39`、`VaultGeometry.java:17`）/ 代码里也写作 §3.9.5 | §3.9⑤ 混入类里不能有 public/static 普通方法 | 一致（本文档为可读性写成 §3.9⑤，与代码里的 §3.9.5 是同一节） |
| §3.9⑦（本仓库 `AGENTS.md` §4；上游代码） | §3.9⑦ tick HEAD 灌队列与红石闸门 | **⚠️ 刻意分歧**：上游要求加 `if (!self.redstonePowered) return;`，**本模组刻意不加**，理由见该节 |
| §3.10（本仓库 `AGENTS.md` §9；上游） | §3.10 部分重组的五条不变量 | **一致，未变**（load-bearing，必须保留） |
| §3.11（`SharedPackagePool.java:31,62,210`、`PartialOrderTracker.java:33`、`ContainerIdSupport.java:38`、`VaultExtraData.java:10`） | §3.11 容器身份：为什么必须是 BE 上的稳定 UUID | **⚠️ 部分分歧**：UUID 方案一致，但本仓库的身份**不再只认保险库**（新增 §3.12/§3.13） |
| §4.3（`PartialRepackager.java:31`） | §4.3 部分重组：历次失败路线与成功路线 | **一致，未变** |
| （新增，代码注释未引用） | §2.4 容器身份分派 / §2.5 部分重组算法 / §2.6 防复制丢失 | 本模组新增内容 |
| （新增） | §3.12 三条身份路线与"无适配器必须返回 null" | 本模组新增 |
| （新增） | §3.13 单方块容器的位置键 / 双箱子归一化 / 网络锚点 | 本模组新增 |
| （新增） | §3.14 孤儿提示持久化 + `OrphanSweep` | 本模组新增 |
| （新增） | §3.15 流体打包机身份取 `fluidTarget` | 本模组新增 |
| （新增） | §3.16 `splitMulti` 必须注 TAIL + AP 误报辨析 | 本模组新增（修掉上游 bug） |
| （新增） | §3.17 连通性行走兜底（`±11` 不够用） | 本模组新增 |
| （新增） | §3.18 流体罐适配器的两处刻意省略与代价 | 本模组新增 |
| （新增） | §3.19 打包机路径入池 + at-least-once 顺序 | 本模组新增 |
| （新增） | §3.20 单方块容器为什么注 `removeBlockEntity` 而不是 `setRemoved` | 本模组新增 |

> **一致性提示**：本仓库 `AGENTS.md` 开头的警告说"代码注释里的 `§x.y` 目前来自上游 TECHNICAL.md"。
> 本文档落地后，**表中每一个编号在本仓库 TECHNICAL.md 里都已存在**（内容按本仓库实现改写，
> 分歧处已标注），因此 `AGENTS.md` 与代码注释的引用现在可以指向本仓库文档，不再需要"打开上游核对"。

### 6.5 本文档未验证的事项（诚实清单）

> 注：原列在此的 6 条（`fluidTankMaxHeight` 的取值、`ItemVaultBlockEntity` 的尺寸返回值、
> `LevelChunk.clearAllBlockEntities` 的卸载行为、Create: Connected / Storage / FluidLogistics 三个可选模组的
> 类与字段）已在 **§6.6 已复核闭环** 中用本机缓存的真实 jar 逐条证实，因此不再列为"未验证"。

以下论断**本次未能从本机源码或字节码独立求证**，已在正文对应处标注。它们都取自代码 javadoc 的陈述，
**不是编造**，但请在与它们相关的改动前自行复核：

| 事项 | 出处 | 为什么没验证 | 复核方法 |
|---|---|---|---|
| Create: FluidLogistics `FluidPackagerBlockEntity.fluidTarget` 的字段名与类型、其过滤器排除便携流体接口 | `FluidPackagerBlockEntityMixin.java` javadoc | 同上（`@Shadow` 的安全性正依赖于此，**换 `fluidlogistics_version` 前务必复核**） | 同上 |
| 上游 TECHNICAL.md 里的历史偏移（如 `splitMultiAndInvalidate` "offset 339-369"、`tryToFormNewMultiOfWidth` "offset 665-1074"、`addPackageFragment` 触发点的 "offset 108-113"） | 上游文档 | 上游文档的偏移与本次复验值**不一致**（本文档 §3.11 已给出复验值；§3.9② 与 §4.3 的偏移本次已复验并一致） | 以本仓库复验值为准；本机的 `javap` 命令与 jar 路径见 §6.3 |

> **本仓库里没有任何上游的参考 jar**（已全盘确认：`src` 下除 `.java` 外只有 `resources/` 里的两个 mixin json、
> `pack.mcmeta`、`Logo.png`、`META-INF/mods.toml`）。曾经放在**仓库根的** `src\main\` 下做字节码对照的那份
> `goddamnrepackager-0.5.1-forge-alpha.jar` **已删除**——**不要**把它恢复进本仓库，也不要加进 classpath。
> 需要对照上游字节码时，从**只读**的上游仓库取同一份副本：
> `..\github_repository\god-damn-repackager\build\libs\goddamnrepackager-0.5.1-forge-alpha.jar`
> （**只读**，永远不要写/建/移/删那个仓库里的任何东西）。

---

### 6.6 已复核闭环的原 §6.5 条目

下面这些条目原先列在 §6.5 的"未验证"里，后来都在**本机缓存的真实产物**上复核完毕。证据与所用 jar 一并记录，
后续需要复核同类问题时**直接用这些路径**，不必再满盘搜索：

| 原条目 | 复核结论 | 证据 / 所用 jar |
|---|---|---|
| `ItemVaultBlockEntity` 的 `getMaxWidth()==3` / `getMaxLength(r)==r*3` | **确证**（`iconst_3` / `iload_0; iconst_3; imul`）→ `MAX_CONTAINER_RADIUS=11` 的推导成立 | 同上 |
| `LevelChunk.clearAllBlockEntities()` 会对每个 BE 调 `setRemoved()` | **确证**：`BootstrapMethods #5` = `REF_invokeVirtual BlockEntity.setRemoved:()V`（偏移 28），`#4` = `onChunkUnloaded`（偏移 9）；`removeBlockEntity` 偏移 51 同样调 `setRemoved` | `forge-1.20.1-47.4.10_mapped_parchment_2023.08.20-1.20.1.jar`（构建本项目实际使用的映射 jar） |
| Create: Connected `ItemSiloBlockEntity` 的方法/字段 | **确证** `read`/`write`（protected）、`notifyMultiUpdated`、`getMaxWidth`、`getMaxLength` 都存在；**并且它没有自己的 extraData 三件套**（沿用接口默认方法）→ silo 适配器覆写三件套不会顶掉任何实现，**与流体罐的情况根本不同** | `create-connected-1.2.3-mc1.20.1.jar` |
| Create: Storage `StorageNetworkIdentifier` 的 record 组件 | **确证**：`(controllerPos: BlockPos, memberPositions: Set<BlockPos>)` + 同名访问器 | `create-storage-neo-forge-oSsfZYxj.jar` |
| Create: FluidLogistics `FluidPackagerBlockEntity.fluidTarget` | **确证**：`public TankManipulationBehaviour fluidTarget`（与 `@Shadow` 逐字一致），该类 `extends PackagerBlockEntity` | `createfluidlogistic-1.3.0-mc1.20.1.jar` |