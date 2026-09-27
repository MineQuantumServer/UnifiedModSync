# 验证记录

## 1.0.2 优化与升级显示验证

日期：2026-09-27。本地测试，没有操作正式服务器或生产数据库。

本次通过 `build runSmoke`、`test -Pintegration --rerun-tasks` 和最终 JAR 的独立 `verification/packaged runSmoke`。9 个 JUnit 用例全部通过、无跳过；开发环境和最终 JAR 各 3 个 GameTest 全部通过。开发 GameTest 使用 MySQL-only，最终 JAR 测试使用 MySQL + fakeredis TCP 兼容服务，并额外加入父层 MySQL Connector/J 8.4.0，检查隔离打包兼容性；这不是实际 Redis 集群测试。

分配对比使用同一真实 NeoForge GameTest 环境：一个普通背包、7 个钻石、相同已加载资源集合，预热 2,000 次后连续发现 10,000 次，通过 ThreadMXBean 读取服务器线程分配字节。1.0.1 基线为 9,824 字节/次，优化后两次开发运行为 2,312 和 1,982 字节/次，约降低 76%～80%。这是简化场景的扫描路径数据，受 JIT 等因素影响，不代表线上整个模组的分配率或常驻内存下降同样比例。用户报告中的 503.05 MiB 是 90 秒累计分配估计，也不是常驻内存。

背包回归增加了：保存拾取升级、清空槽位形成过期显示信息、重新加载后无需点击即可恢复升级内容和显示元数据；创建真实 BackpackContainer，确认发送正确窗口 ID、升级槽索引及物品的 ClientboundContainerSetSlotPacket。仍检测同一 tick 内新出现的重复 UUID；失败后可再次发现，清理玩家缓存后可重新发现。嵌套父记录优先加载及损坏数据保护测试继续运行。

这些是服务端状态和数据包验证，未运行真实客户端 GUI。升级槽与原版槽位分开存放的实现已核对，新增显式槽位更新作为兼容处理；尚不能认定用户完整 Youer 环境中的唯一根因。验收时携带多个升级从 A 服切换到 B 服，首次打开背包、不点击升级槽，检查图标、升级设置和实际功能，并测试再次切服与回档。

## 1.0.1 修复验证

针对用户日志 `KtgGate.ready: argument type mismatch`，核对提供的 KTG4 JAR 反编译代码：`Work` 同时公开 `isLoaded(Go4Player)` 和 `isLoaded(PlayerStatus)`。修复后精确匹配 Go4Player 参数，不依赖反射枚举顺序。

本次运行 `gradlew.bat build`，包含 4 个新的 KTG4 反射回归用例及快照格式测试。未重新启动数据库集成测试或完整 GameTest，也未宣称已在实际 Youer + KTG4 服务端联测。以下数据库和 GameTest 记录为 1.0.0 的既有验证记录。

日期：2026-09-27。未部署到用户正式服务器，也没有连接用户生产数据库。

## 已通过

- Java 21 / NeoForge 21.1.251 编译、单元测试与 Shadow 构建。
- 4 个 JUnit 用例，0 失败、0 跳过。数据库为本地真实 MySQL 8.4.9：
  - 快照格式、截断数据、SHA-256 损坏检测。
  - 玩家锁冲突、背包 UUID 锁冲突、跨玩家交接、旧 token 写入拒绝。
  - 多资源保存中途失败整笔回滚，备份数量清理。
  - all 回档使用同批检查点，单模块保存不污染整组回档时间点。
  - 数据库时钟租约过期后拒绝续租、拒绝保存。
  - Redis token 校验、错误 token 无法续租/释放。
- 3 个 NeoForge GameTest，使用真实 AW、SB、Core、Astral JAR：
  - 三个模块编解码；背包恢复清除旧键、刷新 wrapper 缓存；未知物品注册 ID 在应用前被拒绝；保护时拒绝移动和切换物品栏槽位；异步加载、保存、单模块回档、延迟离服清理。
  - 故意破坏 MySQL 中的星辉快照：玩家进入 ERROR 并保持保护，备份回档后重新加载全部模块恢复 READY；管理解除保护进入 BYPASS。
  - 本地外层背包错误引用一个已被其他会话锁住的内层背包：先读取数据库权威父记录，再发现正确的内层 UUID，避免扫描旧本地引用造成错误锁冲突。
- 最终打包 JAR 的独立 GameTest 启动：同样 3 项通过。此测试额外在父层加入 MySQL Connector/J 8.4.0，同时使用模组内隔离的 Connector/J 9.2.0，未发生重复 `mysql.connector.j` 模块错误。
- 开发 GameTest 使用 MySQL-only；最终打包测试开启 Redis。Redis 端点为本地 fakeredis TCP 兼容服务器，**不是实际 Redis 发行版/集群测试**。
- 旧数据迁移脚本：测试三个来源格式、gzip 解压、dry-run 不写入、重复执行不覆盖已有目标记录。
- 发行 JAR 检查：无顶层/多版本 module-info.class、无原始 com/mysql 类路径、无 smoke 测试模组；Automatic-Module-Name 为 dev.unifiedsync。

## 验证环境

| 组件 | 版本 |
| --- | --- |
| Java | Dragonwell 21.0.11 |
| NeoForge | 21.1.251 |
| Armourer's Workshop | 3.4.0-beta.3 |
| Sophisticated Backpacks | 3.26.3.2158 |
| Sophisticated Core | 1.5.1.2341 |
| Astral Sorcery | 文件版本 2.0.0.4 |
| Curios | 9.5.1+1.21.1 |
| ObserverLib | 1.10.3.31 |
| MySQL | 8.4.9 |

## 尚未完成的环境验收

没有在用户完整 Youer 插件服上进行真实客户端双服联测。KTG4/NMS 桥接依据用户提供 JAR 的实际接口和反编译时序；不代表已运行这两款插件的全部流程。

未模拟整个服务器进程突然断电、真实 Redis 网络分区、全部升级物品/饰品整合、其他插件直接改写对象、世界机器远程访问背包、完整整合包 GUI 的所有自定义消息。

保护与事务用于收紧同步窗口，不能让 KTG4 与另一套数据库的提交自动成为一笔分布式事务。请按 README 的验收流程在服务器副本中测试后使用。
