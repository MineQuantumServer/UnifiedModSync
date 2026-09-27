# 参考文件分析与设计依据

分析对象是本次提供的文件内容，附件中的文字不作为额外任务指令。第三方反编译代码只用于本地分析，没有装入发行包。统一模组通过原模组的接口/反射读写数据，没有复制整套第三方实现。

## YouerModSync 的精妙背包同步

本次文件名是 `YouerModSync-0.5.1.jar`，但内嵌 plugin.yml 声明为 0.4.1，以下结论针对该文件而非其他同名版本。

关键类：`com.mohistmc.youermodsync.SophisticatedBackpacks`。

1. 在 Bukkit 入服事件把玩家放入等待集合，按 `join_delay * 20` 延迟执行加载。`PlayerInventoryProvider.runOnBackpacks()` 遍历携带位置，另遍历末影箱。
2. 从背包 ItemStack 的 wrapper 取得 `contentsUuid`。这个 UUID 是**背包内容的 UUID**，不是玩家 UUID。
3. 表 `backpack_data(uuid, backpack_nbt)` 每行对应一个背包。读取 gzip NBT，再调用 `BackpackStorage.get().setBackpackContents(uuid, tag)`。
4. 离服取得 `getOrCreateBackpackContents(uuid)`，用 `NbtIo.writeCompressed()` 序列化，逐个 `REPLACE INTO backpack_data`。
5. 原插件也有自动保存/历史数据：`saveBackpackToHistory()` 写 `history_data`，`restoreBackpackFromHistory()` 按背包 UUID 与时间查询恢复；不能说原插件完全没有备份。

因此，KTG4 只同步玩家 NBT 中的背包物品及 UUID，并不等价于同步背包内部物品。精妙背包的内容还存在世界级 `BackpackStorage` SavedData 中，必须另行同步。

从代码可确认的风险点：

- 同步依赖固定延迟，没有验证 KTG4 的 NMS 加载是否完成；可能扫描到加载前的物品栏。
- 加载中的 SQL/解码错误主要记录日志，正常返回后仍移出等待集合；没有把失败转换为持续保护状态。
- SQL 按背包逐条写入，没有玩家会话和背包资源租约，旧服延迟保存可能覆盖新服内容。
- 遍历没有递归处理套娃背包；记录外层 NBT 不代表内层 UUID 对应内容也已经保存。
- `setBackpackContents` 对已有 CompoundTag 合并键，没有清除目标快照中已不存在的旧键。
- 加载之后没有统一刷新已建立的 wrapper、inventory、upgrade 等缓存。
- `getOrCreateBackpackContents` 在本地数据不存在时可能创建空记录，使“缺失”被当作“空背包”继续保存。
- 等待期间的保护集中在交互和打开容器，不能替代完整的移动、伤害、物品栏包、模组自定义包防护。

这些是可复现问题的来源和代码风险，不能只凭这三个 JAR 断言你所有历史故障都由某一个点导致；具体某次故障仍需要对应运行日志与数据。

## KTG4 / NMS 的时序

所给 NMS 文件名包含 2025.08.02，其内嵌版本日期与文件名不完全一致；分析以 JAR 内代码为准。

NMS 扩展使用 `saveWithoutIdCB(tag, true)` 保存，再用实体的 `load(tag)` 恢复完整玩家 NBT。KTG4 在异步取数后切回主线程反序列化，然后设置 loaded 状态。

所给 KTG4 有公开静态 `KnapsackToGo4.work`，其 `Work.isLoaded(Go4Player)` 同时检查 loaded、playerQuit、dataError。桥接通过 `BukkitGo4Player` 包装同一 UUID 的玩家读取该状态。桥接无法匹配或插件报错时保持保护，不把反射失败解释成加载完成。

统一模组依次等待入服延迟和此状态，再申请锁、加载模块。已经同步后若又发生完整玩家 NBT load，会重新保护并要求管理员在外部加载结束后 retry。离服捕获延至服务器 tick 结束，规避 Youer 在 PlayerLoggedOutEvent 后还执行最后一次玩家 tick 的时序。

KTG4 和统一模组是两套独立提交过程；没有修改 KTG4 事务，也没有宣称它们共享一个原子提交点。崩溃恢复仍须核对两边备份。

## 时装工坊与星辉

压缩包内的 AWWardrobeSync 实际上是 Bukkit 插件源码，其数据桥接是 `SkinWardrobe.of(player)` → `TagSerializer` → NBT，加载后标记 inventory 变化并广播。统一模组沿用这个数据入口，加入统一锁、错误保护与备份机制。作品/皮肤资源文件不包含在衣柜 NBT 中。

星辉模块面向 AstralSorcery 2.0.0.4，以完整 `SAVE_CODEC` 编解码 PlayerProgress。研究缓存与天赋效果一起刷新。星辉研究不是简单复制原版 playerdata 文件即可处理。

## 新结构

```text
玩家入服（立即保护）
  → 延迟 + KTG4 isLoaded
  → 可选 Redis 玩家锁 + MySQL 玩家锁
  → 模块发现资源；先加载父背包，再发现内层 UUID
  → MySQL 资源锁 + 校验 + 主线程应用 + 完整检查点
  → READY

定时 / 命令 / 离服保存
  → 主线程捕获独立快照
  → 数据库事务内校验租约、写头记录、写备份、清理旧备份
  → 成功解除短暂保护 / 离服释放锁

任一失败 → ERROR，停止正常保存，保留玩家在线并提示联系管理员
```

MySQL 服务器时间决定锁是否有效，唯一 token 防止旧会话释放新锁或覆盖新数据。备份按玩家、模块存储；一次保存的模块共享 batch ID，all 回档只选完整同批快照。普通 NBT 采用有界读取，额外 SHA-256 检测损坏。Redis 是附加协调层，不是唯一的数据源或唯一的锁。

## 版本与来源

- [SophisticatedBackpacks 1.21.x](https://github.com/P3pp3rF1y/SophisticatedBackpacks/tree/1.21.x)，研究时 checkout `8005657e4ce1caa05ba2feea123d8d036ed1790b`。此分支已比目标 JAR 更新；兼容性以 SB 3.26.3.2158 / Core 1.5.1.2341 实际 JAR 测试为准，未宣称支持后续 linked storage 功能。
- [Armourers-Workshop](https://github.com/Armourers-Workshop/Armourers-Workshop)，研究时 checkout `f79d70dd928e0294b8925d0f3ff9142471c38b67`，运行验证使用 3.4.0-beta.3。
- [KTG4 指定分支](https://gitee.com/jja8/KnapsackToGo4/tree/bukkit-test-2025.8.13/)。网页工具未能读取该分支，具体时序和桥接依据所给 KTG4 / NMS JAR 的反编译结果。
- [AstralSorcery](https://github.com/HellFirePvP/AstralSorcery)，目标 JAR 2.0.0.4。

附件 SHA-256：

```text
YouerModSync: 3D458E468889699C6A8A7E97D6D717F88C6A96153E92BFDD01C2DF2D273ADB6B
KTG4:        01485FD534EF1C8E7F45BFAB4762FB19A33E7262C9D4C34324D4F8BBB21B21A0
NMS:         3486C108DD43332C20FC7525CA1F44396F4671FCC15D902199A37A714F809EF7
```
