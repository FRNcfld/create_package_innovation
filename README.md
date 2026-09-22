# create_package_innovation

机械动力的包裹与物流附属模组：为 Create 的包裹系统添加新的机制。

## 已实现功能

**打包机 / 理包机并行（移植自 [god-damn-repackager](https://github.com/cshawny/god-damn-repackager)，MIT，作者 cshaw）**

- **共享包裹池**：同一个容器上的多台机器不再由一台独占整张订单。整理/打包好的包裹进入按容器
  分桶的世界级共享池（多方块容器用稳定 UUID，其余用位置键），各空闲机器每 tick 主动取 1 个发送。
  N 台 ≈ N 倍速度，天然动态均衡（堵塞机自动停止取包，活流向空闲兄弟）。
- **按来源分流**：池里每条包裹都记着是**理包机产**还是**打包机产**，机器**只取与自己同来源的**。
  所以理包机整理好的**有序包裹不会被后贴上去的打包机取走**——它一定从理包机自己那一侧发出去。
  同类机器之间仍然共享同一个队列，"N 台理包机 ≈ N 倍速度"不受影响。
- **打包机也参与**（不只是理包机）：打包机在 `attemptToSend` 里组装好的包裹直接进池，而不是先塞进
  自己的私有队列；已经躺在私有队列里的积压也会被移交进池，交给同容器的其他机器发出去。
  **红石只管"理包机取包"**：切断红石后理包机最多 0.5 秒就停止从池里取包，重新通电自动继续（Create 的理包机
  与 Create: FluidLogistics 的流体理包机都适用）；**普通打包机不看自己的红石**，永远照常取包（共享容器上的
  打包机常常只是发货端，根本没接红石）。
  **"上交"那一步对任何机器都不看红石**——一台机器**已经生产出来**的包裹一定会被交给池，加红石门会把
  包裹锁死在断电的机器里，表现就是"吞物品"。池存在存档里，断电期间包裹不丢。
- **部分重组**：不等一张订单的原料碎片全部到齐，只要已到碎片够合成至少一次就提前开工；
  未用完的余料托管在存档里，拆掉容器时如数爆出，材料全程守恒。
- 共享池跟随存档而非方块：打掉机器不爆池；部分拆除多方块容器由剩余方块通过 UUID 继承身份，
  订单继续跑；只有拆掉最后一个方块才整池爆出。
- **漏抽兜底（含跨存档）**：每个池键"最后出现在哪"提示随存档持久化（独立 SavedData，不改动池与
  余料追踪器的存档格式），重启后区块加载时仍能判定容器是否还在，把漏抽的池如数爆成掉落物，
  而不是让包裹烂在存档里。
- **容器兼容（所有容器）**：身份判定不绑死具体类，按容器类型分三条路：

  | 容器类型 | 身份 | 拆除清理 |
  |---|---|---|
  | 多方块容器（Create 原版保险库与流体罐、Create: Connected 纵向保险库 Item Silo…） | 挂在该 BE 上的稳定 UUID（唯一能跨 reshape 稳定） | `ConnectivityHandler.splitMulti` + 邻居 UUID 扫描，再以**连通性行走**兜底（不限半径，涵盖高度取自 `fluidTankMaxHeight` 配置的流体罐），区分"部分拆"与"全拆" |
  | **网络型存储**（Create: Storage 的 Simple Storage Network） | 网络锚点（控制器）位置键 | 拆网络里的箱子：算出的位置键不匹配，不动池；拆控制器：正好命中锚点键，整池爆出 |
  | **其他所有容器**（原版箱子/木桶、其他 mod 的储物方块…） | 位置键（维度 + 坐标 派生的确定性 UUID） | `LevelChunk.removeBlockEntity`——只在方块真被移除时触发，**区块卸载不会误爆池** |

  **单方块容器不需要写任何适配 mixin，任何 mod 的容器开箱即用。** 多方块容器因为要跨 reshape
  保持身份，仍需照 `mixin/compat/ItemSiloBlockEntityMixin.java` 写一个约 60 行的适配 mixin；
  兼容 mixin 放在独立配置 `create_package_innovation.compat.mixins.json`（`required=false`、
  `defaultRequire=0`），未安装对应模组时只打警告，不拦启动。

  **流体（Create: FluidLogistics）**：流体打包机的 `targetInventory` 只是它的物品面（过滤器甚至
  排除了便携流体接口），真正的存储是 `fluidTarget` 面对的流体罐，所以按后者计身份
  （`FluidTargetAccessor`，实现放在 compat mixin 里）。而 Create 的流体罐**自己占用了
  `extraData` 通道**（传的是 `Boolean` 型的 window 标记），因此奶罐适配器刻意不覆写 extraData
  三件套、也不挂 `notifyMultiUpdated`：UUID 改由"拆分时沿连通性把旧 UUID 写回幸存部件"来跨
  reshape 保持（否则重新成形的控制器会先铸一个新 UUID，把旧池变成孤儿）。AE2/RS 等尚未提供
  网络锚点的存储仍按单个方块计身份。

## 安装与依赖

- Minecraft **1.20.1** + Forge **47.x**，客户端与服务端都要装（本模组两端都有内容）。
- **必需**：[Create](https://github.com/Creators-of-Create/Create) **6.0.8 ~ 6.0.x**（`mods.toml` 里声明为
  `[6.0.8,6.1.0)`；1.20.1 只维护 6.0 线，6.1 起不保证兼容）。
- **可选**（下面三个都只在 Modrinth 发布；没装只是少了对应容器的池化，不会拦启动、不会报错）：
  - **Create: Connected** —— 纵向物品保险库（Item Silo）也能作为池的容器。
  - **Create: Storage** —— Simple Storage Network 按"网络"计身份（拆网络里的箱子不会误爆池）。
  - **Create: FluidLogistics** —— 流体打包机可参与并行，身份按它面对的流体罐算。
- **JEI** 只在开发环境里作为配方查看器引入，不是运行时依赖。

## 已知限制

- **共享以"容器"为单位**：同一个容器上的机器共享一个池；不同容器各自独立（Create 会把相邻的同类
  容器合并成一个多方块，合并之后它们就是同一个容器了）。
- **网络型存储**：只有提供了网络锚点的（Create: Storage）按网络计身份；AE2/RS 等仍按单个方块计，
  拆掉那个方块会把该位置的池爆成掉落物 —— 不丢东西，但订单会中断。
- **两个同类多方块容器合并**时只有一个身份会活下来：保险库有 UUID 合并流程（输家的池迁到赢家），
  流体罐没有（奶罐不挂 `notifyMultiUpdated`，理由见 TECHNICAL.md），输家的池由兜底清扫当掉落物回收。
- **被装置/矿车搬走**的容器会拿到新身份（`writeSafe` 那条路没有写 UUID），旧池同样由兜底清扫回收。
- **吞吐上限**：每台机器受原版发送动画周期限制，每 20 tick（= 1 秒）只能发出 1 个包裹，所以 N 台
  ≈ N 个包裹/秒；订单超过这个速率时包裹是在池里排队，不会丢。
- **断电语义**：没通电的**理包机**（含 Create: FluidLogistics 的流体理包机）不取包，普通打包机不受影响；
  所以当容器上的理包机**全部**断电、且没有打包机在取包时，池里的包裹会停下来等电（它们在存档里，不丢）；
  重新通电就继续发送。断电侧的响应最多滞后一个 lazy tick（约 0.5 秒）。
- **按来源分流的代价**：如果容器上**没有**对应种类的机器，那些包裹会停在池里**等**（不丢、不爆、不发）——
  例如把理包机全拆了，池里剩下的"理包机来源"有序包裹要等放回一台理包机，或者拆掉容器时才会全部掉出来。
- 容器被拆时，池里的包裹会**每种一份掉落成一个物品实体**，不消失也不复制。

## 代码结构

```
src/main/java/com/frnc/create_package_innovation/
├── CreatePackageInnovation.java   # @Mod 入口（根包只留这一个类）
├── identity/                      # 容器身份：把"哪个容器"解析成一个稳定的池键
│   ├── VaultIdentity.java / VaultGeometry.java / VaultExtraData.java
│   ├── ContainerIdSupport.java    # 稳定 UUID 的 extraData 传递 + NBT 读写
│   ├── ContainerHintRegistry.java / ContainerHintStore.java   # "池键最后出现在哪"（内存 + 存档）
│   ├── VaultIdAccessor / NetworkAnchorAccessor / FluidTargetAccessor   # 三个 duck interface
│   └── RepackagerLike             # 纯标记接口：implements = 「这台是理包机」→ 来源分流 + 红石闸门
├── pool/                          # 共享包裹池
│   ├── SharedPackagePool.java     # 世界级 SavedData，按容器（UUID / 位置键）分桶
│   └── OrphanSweep.java           # 区块加载时回收"容器已消失"的池
├── partial/                       # 部分重组（订单接管 + 余料托管）
│   ├── PartialRepackager.java
│   └── PartialOrderTracker.java
└── mixin/                         # 注入点；compat/ 下只有可选模组相关的那几个
```

设计原理、逐个开发陷阱与调试方法见 [TECHNICAL.md](TECHNICAL.md)；在这个仓库里干活的规则与禁区见
[AGENTS.md](AGENTS.md)。

## 许可证

MIT。本项目移植了 [god-damn-repackager](https://github.com/cshawny/god-damn-repackager)（MIT，作者
cshaw）的部分代码，其许可与署名按 MIT 要求在 `LICENSE` 中一并保留。

调试：把 `CreatePackageInnovation.DEBUG_LOGGING` 改为 `true` 重新编译，日志会打印
`[CPI-POOL]` / `[CPI-PARTIAL]` 行。
