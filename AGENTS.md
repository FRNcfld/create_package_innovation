# AGENTS.md — AI 助手操作手册

> **面向**：AI 编码助手（Claude、Copilot、DSH 等）和未来的你。
> **互补文件**：[TECHNICAL.md](TECHNICAL.md) 记录架构、原理和开发陷阱；**本文件**记录"在这个项目里该怎么干活"的规则与禁区。
>
> **`§x.y` 编号契约**：本手册与代码注释里的 `§x.y` 引用**由本仓库的 [TECHNICAL.md](TECHNICAL.md) 承载**。
> 该文档**沿用上游的编号体系**（§2.2 / §3.1 / §3.5 / §3.7 / §3.8 / §3.9①~⑦ / §3.10 / §3.11 / §4.1 / §4.3 / §5 / §6），
> **绝不重排已有编号**，上游没有的主题一律追加为新编号（§2.4~§2.6、§3.12 起）。
> 凡是本仓库**刻意偏离上游**之处，TECHNICAL.md 都标了 "⚠️ 与上游分歧（不要改回去）"；
> **注释 ↔ 文档的唯一权威映射表见 TECHNICAL.md §6.4**，改注释里的 §引用前先查它。

---

## 1. 项目一句话

一个 Forge 1.20.1 Mixin 附加模组（**不是 alpha**，`mod_version=1.0.0`）：让同一容器上的 N 台打包机/理包机
**并行**完成同一张订单。整理好的包裹不再留在机器私有队列里，而是进入一个**按容器分桶的世界级共享包裹池**；
空闲机器每 tick 主动取 1 个发送，因此 N 台 ≈ N 倍速度。身份判定不绑死具体类，按容器种类分路：

| 容器种类 | 身份 | 代表 |
|---|---|---|
| 多方块容器 | 挂在该 BE 上的稳定 UUID（唯一能跨 reshape 稳定） | Create 保险库 `ItemVaultBlockEntity`、Create: Connected 纵向 Item Silo、Create 流体罐 |
| 网络型存储 | 网络锚点（控制器）位置键 | Create: Storage 的 Simple Storage Network |
| 任意单方块容器 | 位置键（维度 + 坐标 派生的确定性 UUID） | 原版箱子/木桶、任何 mod 的储物方块 |

Create: FluidLogistics 的**流体**打包机通过一个 compat mixin 纳入（它真正的存储是 `fluidTarget` 面对的流体罐，
不是 `targetInventory` 的物品面）。漏抽的池由 `OrphanSweep` 在区块加载时兜底回收——**含跨存档重启**，因为
"池键最后出现在哪"的提示本身持久化在 `gdr_container_hints`。

---

## 2. 构建与验证

### 真实的构建面

- **没有 `g.sh`**，也**没有 alpha jar 分类器**（已在 `build.gradle` / `gradle.properties` 全量确认：
  没有 `archiveClassifier`、没有 `mod_is_alpha`）。jar 名由 `gradle.properties` 推导：
  `base.archivesName = mod_id`（`build.gradle:16-18`）+ `version = mod_version` → `build/libs/create_package_innovation-1.0.0.jar`。
- 构建工具是 `gradlew.bat` / 用户 IDE，**JDK 17**（`build.gradle:33`；本机在 `E:\Java\jdk17`）。
- Mixin 由 MixinGradle（`org.spongepowered.mixin` 0.7-SNAPSHOT）注册，两个配置都在 `mixin {}` 块里
  （`build.gradle:22-30`），并写进 jar manifest 的 `MixinConfigs`（`build.gradle:198`）。
- `org.gradle.daemon=false`（`gradle.properties:4`）：每次构建都起新 daemon，慢是正常的。

### ⛔ AI 无法编译本项目（硬约束，不是"暂时不行"）

agent 沙箱只允许写会话工作区，而**用户的 Gradle home 在工作区之外**——`E:\IntelliJ IDEA\ontology\gradle\caches`
（不是默认的 `~\.gradle`）。Gradle 必须往那里写锁文件与缓存，所以 **`gradlew build` 在本会话里跑不起来**。

推论（每一条都要当真）：

1. **保持每次改动小到能靠阅读验证。** 不要一次性改 6 个文件再交出去。
2. **每次编辑后静态自检**：`import` 是否都存在、被调方法签名是否与调用处一致、泛型是否对得上、
   `@Inject`/`@Redirect` 的 `method` 描述符是否与 `javap` 输出逐字一致。
3. **用户的 IDE 是唯一的编译闸门。** 交提案时明说"我无法编译，请你编译"。
4. **编译通过 ≠ Mixin 注入正确。** 注入点可以在编译期完全合法、运行时却注到错误的目标或用了错误的
   `ordinal`（见 §5）。Mixin 注解处理器（`build.gradle:149`）只做编译期目标校验。
5. **行为改动必须附可执行的验证方案**：放什么方块、拆什么、看什么现象、日志里该出现哪一行。

> **唯一能自己拿到的客观证据是 `javap`**（只读，不违反沙箱；jar 路径见 §5）。所以：
> 凡是关于 Create / 其他 mod / 原版行为的论断，先 javap 证；不能证的一律标"未验证"。凭记忆猜 Create API 禁止。

### 调试入口

把 `CreatePackageInnovation.DEBUG_LOGGING` 改成 `true` 重新编译（`CreatePackageInnovation.java:26`，默认 `false`）：

- `[CPI-POOL] handed over N queue entr(ies)` / `deposited … from packager at …` / `fed 1 package to packager at …`
  — 分别对应：私有积压交给池 / 入池（普通打包机走 `attemptToSend`，理包机走 `attemptToRepackage`）/ 空闲机器取走 1 个
- `[CPI-POOL] drained & dropped N package(s) at vault …` — 容器**全拆**，整池爆出
- `[CPI-POOL] two-vault merge resolved: winner=…` — 两个独立容器合并，输家池迁到赢家（罕见）
- `[CPI-POOL] orphan sweep dropped a pool whose container is gone` — 漏抽兜底，**无条件打印**，不受 DEBUG 开关控制
- `[CPI-PARTIAL] crafted N package(s) for order …` / `final pass for order …` / `drained N item(s) …` /
  `migrated M tracked order(s) on vault merge …` — 部分趟 / 末班收尾 / 爆余料 / 余料迁移

---

## 3. 代码结构与禁区

### 真实文件清单（`src/main/java/com/frnc/create_package_innovation/`）

| 文件（按包分组） | 作用 |
|---|---|
| `CreatePackageInnovation.java`（**根包只留这一个入口类**） | `@Mod` 主类：`MOD_ID` / `LOGGER` / `DEBUG_LOGGING` |
| `identity/` → `VaultIdentity.java` / `VaultGeometry.java` / `VaultExtraData.java` | 身份分派 / 邻居与连通性扫描 + 区块安全存活探测(Liveness 三态) / UUID 的 Object 通道载体 |
| `identity/` → `ContainerIdSupport.java` ★ | 容器无关的稳定 UUID 逻辑（extraData 三件套 / `notifyMultiUpdated` / NBT 读写） |
| `identity/` → `ContainerHintRegistry.java` / `ContainerHintStore.java` | 内存 half（"池键最后出现在哪" + 是否多方块）/ 磁盘 half SavedData(`gdr_container_hints`) |
| `identity/` → `VaultIdAccessor.java` `NetworkAnchorAccessor.java` `FluidTargetAccessor.java` | 三个 duck interface：UUID 桥 / 网络锚点桥 / 流体存储位置桥 |
| `pool/` → `SharedPackagePool.java` ★ | SavedData(`gdr_shared_package_pool`)：per-容器共享包裹池，key=UUID |
| `pool/` → `OrphanSweep.java` | `@Mod.EventBusSubscriber`：`ChunkEvent.Load` 时回收容器已消失的池 |
| `partial/` → `PartialRepackager.java` ★ | 部分重组核心：零副作用预扫描 + 部分趟 + 末班 vanilla `repack()` 收尾 |
| `partial/` → `PartialOrderTracker.java` ★ | SavedData(`gdr_partial_order_tracker`)：被接管订单的余料/消费进度/上下文 |
| `mixin/` | `RepackagerBlockEntityMixin`(HEAD 入口 + `addAll` 入池)、`PackagerBlockEntityMixin`(tick HEAD 交给池/取 1 + `List.add` 入池)、`ConnectivityHandlerMixin`(splitMulti **TAIL**)、`ItemVaultBlockEntityMixin`、`FluidTankBlockEntityMixin`(故意少两样，见 §4)、`LevelChunkRemovalMixin`(唯一可 remap)、`PackageRepackageHelperInvoker`(@Invoker) |
| `mixin/compat/` | **只有这里**能出现 `com.yision.*` / `net.fxnt.*` / `hlysine.*`：`ItemSiloBlockEntityMixin`、`StorageNetworkIdentifierMixin`、`FluidPackagerBlockEntityMixin` |
| `src/main/resources/` | `create_package_innovation.mixins.json`、`…compat.mixins.json`、`META-INF/mods.toml`（占位符由 processResources 展开）、`pack.mcmeta`、`Logo.png` |

### 两个 mixin 配置的分工（不要合并）

| | 主配置 `create_package_innovation.mixins.json` | 兼容配置 `…compat.mixins.json` |
|---|---|---|
| `required` / `defaultRequire` | `true` / `1` | `false` / `0` |
| 目标 | Create 与**原版**（`LevelChunk`） | 可选模组（Create: Connected / Storage / FluidLogistics） |
| 注入失败时 | **启动期硬崩** | 只打警告，核心功能不受影响 |

**为什么主配置必须硬失败**：它包含 `LevelChunkRemovalMixin`。如果它注不进去，池照常工作但**没有任何东西会在
容器被移除时把它爆出来**——物品静默丢失。宁可在启动时崩掉（`LevelChunkRemovalMixin.java:49-52`）。
兼容配置相反：没装 Create: Connected 的玩家不该因为一个可选模组启动失败（`ItemSiloBlockEntityMixin.java:34-39`）。

新增 mixin 时**必须**同时登记到对应 json 的 `mixins` 数组，否则它编译进包但运行时从不触发。

### 四条硬规则

**① Mixin 类里不能有 public/static 普通方法。**
mixin 方法会被合并进目标类，非 private 的 static 方法会被 Mixin transformer 拒绝（`checkMethodVisibility`）。
共享 helper 必须放**普通包里的类**——本仓库的 `identity/`、`pool/`、`partial/` 就是这么来的
（`VaultIdentity`、`VaultGeometry`、`ContainerIdSupport` 都在 `identity/`）。
**public 实例方法是允许的**——那是 duck interface 契约的实现方式（`ItemVaultBlockEntityMixin` 的
`createPackageInnovation$getVaultId()` 等）。`@Invoker`/`@Accessor` 接口 mixin 是标准例外（转成调用桥，不合并）。

**② duck interface 必须住在普通包（本仓库是 `identity/`），绝不能在 `mixin` 包里。**
Mixin 子系统禁止任何代码直接引用注册在 mixin 包里的类，启动崩
`IllegalClassLoadError: … is in a defined mixin package … cannot be referenced directly`。
现有三个都在 `identity/`：`VaultIdAccessor`、`NetworkAnchorAccessor`、`FluidTargetAccessor`
（理由写在 `VaultIdAccessor.java:41-46`）。把新的 duck interface 放进 `mixin/` 会**立刻**触发这个崩溃。

> ⚠️ **已知的既存例外，不要照抄**：`PartialRepackager.java`（partial 包）`import` 了
> `mixin.PackageRepackageHelperInvoker`。`@Invoker` 接口不被合并进目标类，所以按现状工作，但这是
> **普通包引用 mixin 包**的边界情形——新代码不要模仿，需要桥接时优先改用普通类或把接口移到别处。

**③ 可选模组的类只能出现在 `mixin/compat/` 下。**
`com.yision.*`（FluidLogistics）、`net.fxnt.*`（Create: Storage）、`hlysine.*`（Create: Connected）一旦出现在
普通包里或主 mixin 包里，没装该模组的玩家会在类加载时 `NoClassDefFoundError`。
**证明规矩仍然成立的命令**（输出必须只有 3 行，且全部在 `mixin\compat\`）：
grep，pattern `com\.yision\.|net\.fxnt\.|hlysine`，path `src/main/java`。

**④ `remap` 的取值不是风格问题。**
Create 与第三方模组的目标：`remap = false`（它们自己的名字从不被混淆）。
**原版目标必须保持可 remap**——`LevelChunkRemovalMixin` 上**没有** `remap = false`，因为 refmap 要把
`removeBlockEntity` 翻译成生产环境（SRG）名。给原版 mixin 加 `remap = false` 会让它在生产环境注不进去
（`LevelChunkRemovalMixin.java:43-47`）。

---

## 4. 绝对不要碰 / 不要"改回去"的表

| 禁区 | 原因 | 文档位置 |
|---|---|---|
| `heldBox` 的**赋值 / 清空** | 清空是**被动**的：应用 `PackagerItemHandler.extractItem` → `setStackInSlot` → `putfield heldBox`（**已 javap 确认** `PackagerItemHandler` 是唯一写点）。打断此协议 = 死锁 / 复制 / 丢失。 | `TECHNICAL.md` §3.8（load-bearing，未变）；`PackagerBlockEntityMixin.java:56-65` |
| **不能**给池的灌入加 `redstonePowered` 闸门 | 上游手册要求这个闸门，本模组**刻意删除**。原版 `tick()` 没有任何红石检查，无条件排空 `queuedExitingPackages`；红石只决定 `lazyTick`/`attemptToSend` **是否填**队列。加闸门会让我们**比原版更严**：被交给池之后红石又被切断的包裹永远取不回来，订单静默停摆，表现为"打包机吞物品"。 | `PackagerBlockEntityMixin.java:78-89`（javadoc，含 "⚠️ Deliberately NOT gated on redstonePowered"） |
| `BigItemStack.count` 的语义 | `count` 是"**这个包裹要发几次**"，不是"包裹有几个"。`poll()` 按 count 拆分（head 的 `count > 1` 时只取 1 份并 `head.count--`）；`drainAndDrop` 按 count **全量** drop 同样多次。按元素数切分是错的。 | `SharedPackagePool.java:53-58`；`poll()` 155-172；`drainAndDrop()` 186-209 |
| `ConnectivityHandler.splitMulti` 的注入点**必须留在 `TAIL`** | 上游用 HEAD，那是**部分拆误判 bug**：`splitMulti` 只是转调 `splitMultiAndInvalidate`（**已 javap 确认**），而"幸存方块从旧 controller 继承 UUID"（`getExtraData` → `setExtraData`）发生在它里面。HEAD 时幸存方 UUID 还是 null，"找同 UUID 兄弟"必然落空 → 误判全拆 → 爆池 → 订单重做 → 物品复制。 | `ConnectivityHandlerMixin.java:70-83` |
| 流体罐适配器**故意少两样**：不覆写 extraData 三件套、不挂 `notifyMultiUpdated` | ① `FluidTankBlockEntity.getExtraData()` 返回 `Boolean.valueOf(window)`（**已 javap 确认**字节码读 `window:Z`）——Create 自己占了这个通道，我们再声明就会顶掉罐子的窗口状态同步。② 罐子没有 extraData 交接：若在 `notifyMultiUpdated` 里铸 UUID，重新成形的控制器在 split hook 跑之前就持有新 UUID，adopt 走不会覆盖非 null 值，池变孤儿。所以罐子**首次使用时惰性铸造**（`VaultIdentity` 读到 null 才铸），把位置留给 adopt 走。 | `FluidTankBlockEntityMixin.java:30-55` |
| 没有适配器的多方块容器必须返回 `null`（不池化） | 对多方块来说位置键**不是 reshape 稳定**的，用它池化会把包裹搁浅。绝不能"退化成位置键"当兜底。 | `VaultIdentity.java:34-36`、`88-93` |
| **绝不重命名**持久化 NBT 键 | 它们已写进玩家存档，改名会让所有已放置容器的 UUID/池/余料变成不可达孤儿：`GDR_VaultId`（`ContainerIdSupport.java:67`）、`gdr_shared_package_pool`（`SharedPackagePool.java:68`）、`gdr_partial_order_tracker`（`PartialOrderTracker.java:57`）、`gdr_container_hints`（`ContainerHintStore.java:52`）。`GDR_VaultId` 保留上游拼写是**故意的**。（键**名**是硬约束；键**值**由 `UUID.randomUUID()` 生成，不是定值。） | 各常量旁的 javadoc |

---

## 5. Create 源码分析方法论

### 已在本机验证的 jar 位置

- **slim jar**（含本次需要的全部类：`PackagerBlockEntity`、`RepackagerBlockEntity`、`ItemVaultBlockEntity`、
  `FluidTankBlockEntity`、`ConnectivityHandler`、`PackageRepackageHelper`）：
  `E:\IntelliJ IDEA\ontology\gradle\caches\modules-2\files-2.1\com.simibubi.create\create-1.20.1\6.0.8-289\25f4470ff5914550297f149765a2352c6ad96186\create-1.20.1-6.0.8-289-slim.jar`
  （`C:\Users\26423\.gradle\caches\modules-2\files-2.1\…` 下另有一份同样的拷贝）。
- 用户的 **IDE Gradle home 是 `E:\IntelliJ IDEA\ontology\gradle\caches`**，**不是**默认的 `~\.gradle`。
- **`-slim` 不含它自己的依赖**（已确认 jar 内无 `catnip` 条目）。以 `net.createmod.catnip.*` 为例
  （`VaultIdentity` 用的 `BlockFace`），它**在 Ponder 的 jar 里**：
  `C:\Users\26423\.gradle\caches\modules-2\files-2.1\net.createmod.ponder\Ponder-Forge-1.20.1\1.0.91\<hash>\Ponder-Forge-1.20.1-1.0.91.jar`
  → `net/createmod/catnip/math/BlockFace.class`。**不要**假设 catnip 有独立 artifact 可找。
  （**未验证**：其它第三方库是否也这样寄居——按同样方式查条目再断言。）
- **映射后的原版 jar**（复核原版行为用，本次已实测）：
  `…\forge_gradle\minecraft_user_repo\net\minecraftforge\forge\1.20.1-47.4.10_mapped_parchment_2023.08.20-1.20.1\forge-1.20.1-47.4.10_mapped_parchment_2023.08.20-1.20.1.jar`
  —— 与 `gradle.properties` 的 `mapping_version` 对应，可 `javap` 出带可读方法名的 `net.minecraft.*`（例如
  `LevelChunk.clearAllBlockEntities` 的 lambda 目标）。**不要再去找 `minecraft_repo\versions\*.jar`**：那是混淆包。
- **三个可选模组的 jar**（构建成功后才出现在缓存里）：
  `…\modules-2\files-2.1\maven.modrinth\create-connected\1.2.3-mc1.20.1\…\create-connected-1.2.3-mc1.20.1.jar`、
  `…\create-storage-neo-forge\oSsfZYxj\…\create-storage-neo-forge-oSsfZYxj.jar`、
  `…\createfluidlogistic\1.3.0-mc1.20.1\…\createfluidlogistic-1.3.0-mc1.20.1.jar`
  —— 复核 `@Shadow` 字段名与 compat mixin 前提时用它们（`TECHNICAL.md` §6.6 已用它们闭环了 4 条）。

### 本机可用的操作方式

agent shell 是 PowerShell（`pwsh`）。**不要用 `unzip -o`**（写操作）。三个已验证可用的动作：

```powershell
$jar = '<上面那个 slim jar 的完整路径>'
& 'E:\Java\jdk17\bin\javap.exe' -p -cp $jar <全限定类名>            # 1) 先看字段
& 'E:\Java\jdk17\bin\javap.exe' -p -c -cp $jar <全限定类名> | Select-String -Pattern '<关键字>'   # 2) 再看字节码
$z=[System.IO.Compression.ZipFile]::OpenRead($jar); $z.Entries|%{$_.FullName}; $z.Dispose()      # 3) 列 jar 条目
```

已确认 `PackagerBlockEntity` 的 `heldBox` / `queuedExitingPackages` / `animationTicks` / `targetInventory` /
`computerBehaviour` 都是 **public** → mixin 里直接访问，**不需要 `@Accessor`**。

### 方法论

1. **先字段（`-p`）再方法（`-c`）。** 用 `getfield`/`putfield` 定位字段被谁读写，用
   `invokevirtual`/`invokeinterface` 追调用链。
2. **改注入点前必须确认目标调用的 `ordinal`**：在 `javap -c` 输出里**数**同名调用的出现次数。
   `RepackagerBlockEntityMixin` 的 `List.addAll` 写了 `ordinal = 0`；`PackagerBlockEntityMixin` 的 `List.add`
   **故意不写 ordinal**——父类 `attemptToSend` 里目前只有一个，将来 Create 若加第二个，Mixin 会因歧义
   **响亮失败**，而不是静默注错（`PackagerBlockEntityMixin.java:135-139`）。
3. **假设"注进父类就对子类生效"之前，先查子类是否覆写。** `RepackagerBlockEntity` **覆写了** `attemptToSend`
   且不调 `super`（**已 javap 确认**子类有 `public void attemptToSend`），所以父类级的 `@Redirect List.add`
   对它根本不跑——这正是理包机保留自己那条 `@Redirect`（注 `attemptToRepackage` 的 `addAll`）的原因。
   反过来，两者都**没有**覆写 `tick()`，所以 `tick` 只需注父类一次。
4. **`@Inject` 用 `method = "名字(描述符)V"`**（如 `"notifyMultiUpdated()V"`、
   `"write(Lnet/minecraft/nbt/CompoundTag;Z)V"`）。描述符写错编译能过、运行时不注入。
5. **注解处理器的 "Cannot find target method" 有两类，不要一律当良性。** `ConnectivityHandlerMixin` 上针对
   `splitMulti` 的那条是**误报**（AP 匹配不了 `<T extends BlockEntity & IMultiBlockEntityContainer>` 的交类型
   边界；`javap -s` 显示擦除描述符正是 `(Lnet/minecraft/world/level/block/entity/BlockEntity;)V`，与注解逐字
   一致，且类里只有这一个 `splitMulti`）。而 `splitMultiAndInvalidate` 是**真注不进去**的：private static，
   且参数带 package-private 的 `SearchCache`。详见 `ConnectivityHandlerMixin.java:62-69`。

---

## 6. 复用现有 helper（不要重新发明）

| API | 一行说明 |
|---|---|
| `SharedPackagePool.get(server)` | 取世界级池实例（SavedData，id `gdr_shared_package_pool`） |
| `SharedPackagePool.deposit(vault, batch)` | 整批入池（追尾 FIFO），只收 `count > 0` 的条目 |
| `SharedPackagePool.poll(vault)` / `pending(vault)` / `drainAndDrop(vault, level, pos)` | 从头部取 **1 个发货单位**（`count > 1` 时拆分并留余数；空池 null）/ 待发**总份数**（按 count 求和，**不是**条目数）/ 整池爆成掉落物（幂等） |
| `SharedPackagePool.noteMergeParticipant(id)` / `resolveMergeWinner(tracker)` | 双容器合并的登记与裁决（赢家 = 最小 UUID）；内部用 |
| `PartialOrderTracker.get(server)` / `get(vault, orderId)` | 追踪器实例（SavedData，id `gdr_partial_order_tracker`）/ 某被接管订单的 `TrackedOrder`（未接管 null） |
| `PartialOrderTracker.update(vault, orderId, leftovers, consumedFragments, context, address)` | 部分趟后写入余料 + 记录已消费碎片槽 |
| `PartialOrderTracker.forget(vault, orderId)` / `migrateKey(oldId, newId)` / `drainAndDrop(vault, level, pos)` | 末班收尾删除条目 / 合并时迁移键 / 容器销毁时按 `maxStackSize` 分批爆余料 |
| `ContainerHintRegistry.remember(server, key, level, pos, multiblock)` | 记录"这个键最后出现在哪"（内存 + 磁盘，仅变更时落盘） |
| `ContainerHintRegistry.hints(server)` / `forget(server, key)` | 只读视图（首次访问从磁盘播种 = 跨重启兜底的关键）/ 键已解决时同清内存与磁盘 |
| `ContainerHintStore.get(server)` | 磁盘 half（SavedData，id `gdr_container_hints`）；**独立文件**，不改动池与追踪器格式 |
| `VaultGeometry.anySiblingVaultWithUuidExists(level, pos, uuid)` | ±11 立方体扫描：还有没有同 UUID 的幸存部件（判 partial vs 全拆） |
| `VaultGeometry.adoptUuidOnSurvivingParts(level, pos, block, uuid)` | ±1 邻居加固：把 UUID **写回**没有 UUID 的同方块幸存部件 |
| `VaultGeometry.anyContiguousPartNearby(level, pos, block, uuid, adopt)` | 连通性行走版（面相邻、不依赖几何与配置）；流体罐唯一可行的 reshape 保命路径 |
| `VaultGeometry.multiblockLivenessNear(...)` / `contiguousLivenessNear(...)` | 区块安全存活探测（`getChunkSource().getChunkNow`，**不强制加载区块**，只读），返回 `Liveness{ALIVE,GONE,UNKNOWN}` |
| `VaultIdentity.vaultIdOf(packager)` / `positionKey(level, pos)` | 解析目标容器的稳定身份（分路 + 惰性铸造 + 顺手 `remember`）/ 单方块容器的确定性键（UUIDv3 over 维度 + 坐标） |
| `ContainerIdSupport.onSetExtraData / onNotifyMultiUpdated / onWrite / onRead` | 容器适配器的**全部逻辑**；适配器类只留字段 + 桥方法 + 4 个 hook |
| `VaultIdAccessor` / `NetworkAnchorAccessor` / `FluidTargetAccessor` | 三个 duck interface（都在 `identity/`） |

**支持一个新的多方块容器 = 写一个约 60 行的适配器**：`@Unique UUID cpi$vaultId`（需要合并语义时再加
`@Unique Set<UUID> cpi$observedIds`）、`implements VaultIdAccessor` 的两个 public 桥方法、extraData 三件套
（除非该 BE 已占用该通道，见 §4 流体罐）、以及 `notifyMultiUpdated`/`write`/`read` 三个 `@Inject`——每个都只
委托给 `ContainerIdSupport`。**单方块容器什么都不用写**，开箱即用。

---

## 7. 文档同步表

| 改了什么 | 需要同步的文件 |
|---|---|
| 发送逻辑（入池 / 取包 / 容器爆池） | `TECHNICAL.md`（算法 + 防丢 + 排查表各节） |
| 新增/修复开发陷阱 | `TECHNICAL.md` 的陷阱实录一节 |
| 改变用户可见行为（工作原理 / 已知限制 / 兼容容器表） | `README.md` |
| 版本号变更 | `gradle.properties` 的 `mod_version`；`mods.toml` **不用改**（写的是 `${minecraft_version}-${mod_version}` 占位符，`mods.toml:15`） |
| 新增 / 删除 / 改名 mixin | 对应 mixin json 的 `mixins` 数组（主配置还是兼容配置，见 §3） |
| compat mixin 或可选模组版本变更 | `gradle.properties` 的 `create_connected_version` / `create_storage_version` / `fluidlogistics_version` **+** `mods.toml` 的可选依赖段（两处要一起想） |
| 构建约定变更（插件、run 配置、MixinGradle） | `build.gradle` 顶部注释 + `TECHNICAL.md` |
| 代码注释里的 `§x.y` 交叉引用 | `TECHNICAL.md` **§6.4** 的对照表——它是"注释 ↔ 文档"的**唯一权威映射**；改动或新增注释里的 §引用时必须同步核对它（编号沿用上游体系，**不要重排**） |

`mods.toml` 的两个既有约定（`mods.toml:1-5`、`71-75`）：占位符一律改 `gradle.properties` 而不是改 toml；
而且**注释里不要写"美元符号紧跟大括号"**——`expand()` 走 Groovy 模板引擎会把它当表达式，让整个
`processResources` 失败。可选依赖的 `modId` 是按各模组 jar 文件名推出来的（ForgeGradle 默认
`archivesName = mod_id`），换版本后要在游戏模组列表里复核。另外 `processResources` 显式钉了 UTF-8
（`build.gradle:167-171`）——中文 Windows 上不这么做会把 `mods.toml` 里的中文全搞坏。

---

## 8. 与上游的关系

- 本模组**移植**自 [god-damn-repackager](https://github.com/cshawny/god-damn-repackager)（MIT，作者 cshaw）。
  本地参考仓库（**只读**）：`E:\GitHub Desktop\repository\github_repository\god-damn-repackager`。
  **永远不要**在那个仓库里写、建、移、删任何东西。要改它的行为，就改本仓库。
- 包名已从上游的 `com.github.goddamnrepackager` 改为 `com.frnc.create_package_innovation`，但**持久化 NBT 键**
  保留了 `gdr_`/`GDR_` 前缀（见 §4）——那是存档格式的一部分，改名就是数据破坏。
- 参考 jar 曾经放在**仓库根的** `src\main\` 下（**不是** java 包目录下）做字节码对照，移植完成后**已按用户要求
  删除**——本仓库现在**不存在**任何名字含 `goddamnrepackager` 的 jar（已全盘确认：`src` 下除 `.java` 外只有
  `resources/` 里的两个 mixin json、`pack.mcmeta`、`Logo.png`、`META-INF/mods.toml`）。
  **不要把它恢复进本仓库、也不要加进 classpath。** 需要对照字节码时从**只读**的上游仓库取同一份副本：
  `…\github_repository\god-damn-repackager\build\libs\goddamnrepackager-0.5.1-forge-alpha.jar`。
- 与上游的**有意的分歧**（不要"同步回去"）：没有 `g.sh`、没有 alpha 分类器、多了兼容 mixin 配置、删掉了
  红石闸门、身份从 BoundingBox 换成 UUID、`splitMulti` 从 HEAD 改到 TAIL，并且**没有** `PUBLISH_*.md` 文件
  （发布时不要去找它们，也不要凭上游结构新建）。

---

## 9. 进一步背景

- **本仓库的 `TECHNICAL.md`**：架构、原理、逐个陷阱的实录。动池 / 追踪器 / 身份 / 注入点之前先读对应小节。
  代码注释里的 `§x.y` 引用**现在由它承载**——映射表见 **§6.4**（"注释 ↔ 文档"的唯一权威来源），
  它保留了上游编号体系且**不重排**，分歧处在正文里标了 "⚠️ 与上游分歧（不要改回去）"。
  另请留意它的 **§6.5**（"未验证事项"诚实清单）与 **§6.6**（**已复核闭环**的原 §6.5 条目 + 所用 jar 路径，
  例如 `LevelChunk.clearAllBlockEntities` 会调 `setRemoved()`、`ItemSiloBlockEntity` 没有自己的 extraData
  三件套 等），以及它 §5 里"复核用的 jar 在本机的哪个路径"。
- **上游 `TECHNICAL.md`**（`…\github_repository\god-damn-repackager\TECHNICAL.md`，**只读**）：保留为
  **历史 / 出处**参考——本仓库文档的编号体系来自这里，想知道某个编号"当初为什么是这个主题"时读它。
  常用小节（编号 + 标题均按上游原文核对过）：
  - `§2.2 核心算法：共享包裹池（0.4.0 架构）`（本仓库对应 §2.3）
  - `§3.5 【数值理解】BigItemStack.count 才是真正的包裹数` —— 即上文"count 语义"
  - `§3.8 【关键背景】发送状态机依赖 heldBox 被动清空协议`
  - `§3.9 【0.4.0 核心陷阱】共享池脱离 BlockEntity → vault-centric 归属 + 三个 mixin 陷阱`（含红石闸门、
    mixin 类 public static 方法、`splitMulti` 可见性等）
  - `§3.10 【0.5.0 核心陷阱】部分重组的五条不变量（违反任何一条都会丢/复制物品）`
  - `§3.11 【0.5.1 核心改动】vault 身份从 BoundingBox 改为 UUID（经 Create extraData 钩子传递）` ——
    **本仓库与它的实现已"有意分歧"**（上游是 `splitMulti` HEAD + 纯邻居扫描；本仓库改为 TAIL +
    连通性行走 + `Liveness` 三态），读它时必须对照 §4，不要照抄回上游写法
  - `§4.3 部分重组（0.5.0 已实现）：订单接管 + 余料托管 + 末班 vanilla 收尾`
  读它时记住：**它描述的是上游，不是本仓库**；尤其是上游 `§3.9⑦`"tick HEAD 必须守 `redstonePowered`"的
  结论在本仓库已被**有意推翻**（见 §4 第二行）。
- **`README.md`**：面向玩家的功能表与兼容容器矩阵，是核对"用户可见行为"的权威描述。
