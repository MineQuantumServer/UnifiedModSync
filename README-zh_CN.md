# Unified Mod Sync 1.0.0

服务端 NeoForge 1.21.1 统一同步模组。首批模块：精妙背包内容、时装工坊衣柜、星辉研究和天赋进度。MySQL 必需，Redis 可选。放入 `mods`，不是 `plugins`；客户端不需要安装这个同步模组。

## 安装与配置

1. 各子服使用同一套玩家 UUID 体系、模组版本、注册表和相关模组配置。数据库先创建一个空库，例如 `CREATE DATABASE unified_sync CHARACTER SET utf8mb4;`。账号需要该库的建表、查询、插入、更新、删除及升级表结构权限。
2. 放入 `unified-mod-sync-neoforge-1.21.1-1.0.0.jar`。删除之前单独的 Astral Sync。停用 AWWardrobeSync；YouerModSync 可以保留其他功能，但必须关闭 `modules.sophisticatedbackpacks`。
3. 启动一次后自动生成 `config/unified-mod-sync.properties`。首次默认 `enabled=false`。正常情况下不用手动创建；也可以复制发行包的示例配置到上述位置。
4. 停服，填写 MySQL 配置，设置 `enabled=true`。所有子服使用相同 `sync-group`，`server-id` 各不相同。只用 MySQL 时保留 `redis.enabled=false`，不需要 Redis 服务，也不需要额外放 JDBC/Jedis JAR。
5. 与本次提供的 KTG4 + NMS 插件配合，建议 `ktg-mode=required`，`join-delay-seconds=5`。模组同时等待延迟和 KTG4 的 `Work.isLoaded()`，不是仅等待固定秒数。
6. 重启生效。先在测试服执行文末的验收流程。首个上线的服不一定持有旧数据的最新副本；从旧同步方案迁移时，先阅读迁移说明。

核心配置示例（其余参数保留自动生成的默认值）：

```properties
enabled=true
server-id=survival-1
sync-group=main
mysql.url=jdbc:mysql://127.0.0.1:3306/unified_sync?sslMode=PREFERRED&characterEncoding=UTF-8
mysql.user=unified_sync
mysql.password=填写密码
redis.enabled=false
join-delay-seconds=5
ktg-mode=required
autosave-seconds=60
backup-count=10
modules=wardrobe,backpacks,astral
```

密码也可通过环境变量 `UMS_MYSQL_PASSWORD` / `UMS_REDIS_PASSWORD` 提供。更改配置需要重启，没有热重载。配置格式损坏时服务器可以启动，但玩家保持保护，修正配置后重启。

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| join-delay-seconds | 5 | 入服后至少等待的真实秒数 |
| ktg-mode | auto | auto：检测到 KTG4 就等待；required：没有 KTG4 也拒绝放行；off：跳过 KTG4 就绪检查 |
| load-timeout-seconds | 120 | 等待初始化、KTG4、旧服交接和加载的超时；超时保持保护 |
| autosave-seconds | 60 | 自动保存间隔，5–3600 秒 |
| backup-count | 10 | 每玩家、每模块保留最近 2–100 个检查点 |
| lease-seconds | 90 | MySQL/Redis 租约有效期，30–600 秒 |
| network-timeout-ms | 3000 | 单次连接及网络读写超时 |
| import-local | true | 数据库首次缺少记录时导入本地数据；已有损坏记录绝不按空白数据覆盖 |
| max-backpacks / max-nesting-depth | 128 / 8 | 背包数量及递归深度上限，超限进入保护 |
| protected-command-allowlist | login,l,register,reg,2fa | 保护期间允许的登录认证命令；只配置必要命令 |

Redis 开启后是额外的玩家会话协调层。其故障也会保护玩家，不会在故障期间自动降级放行；需要在所有服统一关闭 Redis 并重启。Redis 关闭时 MySQL 仍执行玩家与背包资源的租约、事务和过期检查。

## 管理命令

需要权限等级 3，默认管理员可用。`all` 指**当前子服的在线玩家**，不代表其他子服或所有离线账号。指定玩家使用名字。模块名为 `wardrobe`、`backpacks`、`astral` 或 `all`；没有安装的模块不会启用。

```text
/ums status <玩家名|all>
/ums save <玩家名|all> <模块|all>
/ums backups <玩家名|all> <模块|all>
/ums rollback <玩家名|all> <模块|all> <备份序号>
/ums retry <玩家名|all>
/ums release <玩家名|all>
```

例：`/ums save Steve all`、`/ums rollback Steve backpacks 1`、`/ums release all`。

序号 `0` 为最近保存的检查点，`1` 为前一个，以此类推。先执行 `backups` 查看时间。`all` 只选择同一次事务产生、包含当前所有启用模块的完整检查点，避免把不同时刻的三个模块拼在一起；因此单模块序号和 all 序号不是同一列表。各模块独立清理旧备份后，完整检查点可能少于 `backup-count`。

正常玩家回档前会新增 `before-rollback` 备份，随后保存回档结果。损坏状态下回档不会把未验证的运行时数据备份为正常数据；恢复后重新加载全部模块，全部成功才解除保护。

精妙背包回档要求备份中的背包 UUID 集合与当前持有集合完全一致。背包已交易、丢弃、套娃结构变化等情况会拒绝回档；该命令不会生成背包物品、恢复 KTG4 的物品栏或复制已转移的背包。玩家物品栏回档应由管理员协调 KTG4 的对应检查点。

`release` 是人工绕过保护：该会话停止自动保存，防止把错误状态写回数据库。修复后执行 `retry`，或让玩家重进。正在执行读写时会拒绝 release/retry，请等待后再试。不要用解除保护代替修复数据。

## 同步范围与保护

- `wardrobe`：用 AW 的 `SkinWardrobe` 和 `TagSerializer` 保存衣柜，并广播刷新。皮肤文件/作品库不是衣柜数据的一部分；各服需要能访问相同的 AW 皮肤资源。
- `backpacks`：保存 `BackpackStorage` 中按内容 UUID 索引的完整 CompoundTag；遍历玩家物品栏、末影箱、SB 注册的玩家携带位置（含相应集成）、光标持有物及普通容器组件中的嵌套物品；递归处理套娃背包。物品外壳及其数据组件仍由 KTG4 同步。
- `astral`：使用 `PlayerProgress.SAVE_CODEC` 保存完整进度，恢复时刷新天赋效果和客户端研究；不使用裁剪过的客户端共享格式。接管普通研究保存调度，离服结束后再清理缓存。

首次加载、资源交接、保存和回档期间会短暂保护并关闭打开的容器。保护时拦截移动、交互、攻击、丢弃、拾取、物品栏点击、创造物品、模组操作包及非白名单命令；玩家 tick 暂停，免疫正常伤害。网络保活和必要的握手包仍可处理。数据损坏/连接故障不会主动踢人，而是提示“请联系服务器管理员”。

每个玩家和每个背包 UUID 有独立租约；旧会话在锁过期或被替换后不能写库。正常交接等待旧服完成最后保存并释放。异常关服时可能要等待租约过期。无法完成最后保存时，会尽力在世界目录 `unifiedsync-recovery/` 写恢复文件，供管理员排查；这些文件不会自动导入覆盖新服数据。

本模组同步的是**玩家持有数据**。世界中放置的背包方块、无人持有的箱内背包、漏斗/机械/远程存储自动访问、其他插件直接改写玩家对象、绕过标准网络链的自定义操作，不在此次完整跨服一致性范围内。不要让相同 UUID 的背包同时在多个子服被世界机器访问。数据库也无法与 KTG4 的独立数据库组成一笔事务；突然断电时，两套最后检查点仍可能不同，需要配合 KTG4 备份核对。这些边界不能用“绝不会刷物品”来保证。

## 从旧同步方案迁移

新表：`ums_leases`、`ums_resources`、`ums_backups`。本模组不会直接修改旧插件的表。仅 `import-local=true` 不足以保证从旧数据库迁移完整数据，尤其之前 Astral Sync 已停止写本地文件时。

源码附带 `tools/migrate_legacy.py`，用于同一个 MySQL 实例中的**只插入、不覆盖**迁移，支持提供的 YouerModSync `backpack_data`、AWWardrobeSync `aw_wardrobe_sync`、此前 Astral Sync `astral_sync_players`。背包 gzip 解压为新格式；AW 和星辉使用未压缩 NBT。旧历史备份不迁移，AW 原先使用 Redis 存储的情况也不由此脚本处理。

先完整备份旧库。启动新模组让其建表后停止所有相关子服和旧同步插件，等待租约过期；此时不要让玩家进入新服生成本地导入记录。安装 Python 依赖 `python -m pip install PyMySQL`，执行：

```text
python tools/migrate_legacy.py --module backpacks --user 管理账号 --source-db 旧数据库 --target-db unified_sync
python tools/migrate_legacy.py --module wardrobe --user 管理账号 --source-db 旧数据库 --target-db unified_sync
python tools/migrate_legacy.py --module astral --user 管理账号 --source-db 旧数据库 --target-db unified_sync --legacy-group main
```

默认只检查，不写入。确认数据来源正确后，对相同命令加 `--apply`。密码交互输入或用 `UMS_MIGRATION_PASSWORD`，不要写在命令行。已有目标记录一律保留；脚本不是合并冲突或纠正陈旧数据的工具。脚本只做容量、UUID、NBT 根类型初检；实际模组解码仍在首次同步时完成，失败保持保护。

## 构建与扩展

Java 21：`gradlew.bat build`。使用 `build/libs/` 下没有 `unshaded-dev-only` 的 JAR。`gradlew.bat test` 运行无需数据库的检查；`test -Pintegration` 需要测试 MySQL 127.0.0.1:13306、`unified_sync_test` 库和 Redis 兼容端点 127.0.0.1:16379。`runSmoke` 使用三个真实模组进行 GameTest，需要在其 `run/config/` 配置测试库。

`gradlew.bat -p verification/packaged runSmoke` 可验证已构建的发行 JAR 与额外 MySQL 8.4.0 驱动共存。需要先 build，并在 `verification/packaged/run/config/unified-mod-sync.properties` 填写专用测试库、启用三个模块且将入服延迟设为 0；开启 Redis 可同时验证该分支。测试会创建/修改测试数据，不能使用生产库。

模块接口是 `dev.unifiedsync.api.SyncModule`。新增模块实现 `id/requiredMod/verify/discover/capture/validate/apply`；玩家私有资源可继承 `PlayerModule`。模块业务方法只在服务器主线程执行，返回独立 byte[] 快照，不能把活的 NBT/物品对象交给数据库线程。共享物品必须以资源 UUID 作键，而非玩家 UUID。需要客户端请求资源的刷新应放到 `activated`，它在同步保护解除后调用；衣柜使用这个时机，避免刚发出的皮肤请求又被保护拦截。

新增内置模块加入 Coordinator 的模块列表。外部扩展模组推荐在 common setup 时调用 `UnifiedSync.registerModule(MyModule::new)`，并声明对 unifiedsync 的依赖。也支持 Java ServiceLoader：提供 `META-INF/services/dev.unifiedsync.api.SyncModule`，每行一个无参公开实现类。之后在 `modules=` 中启用对应 ID。扩展必须验证依赖版本和存档格式；`validate` 应在修改游戏状态前拒绝损坏数据，异常交给保护状态处理。不要吞异常返回空白进度。含父子引用的数据需覆盖 `discover(player, loaded)`，只遍历已经加载父记录的子引用。

## 已验证与待验收

开发时使用 NeoForge 21.1.251、Java 21、AW 3.4.0-beta.3、SB 3.26.3.2158、Core 1.5.1.2341、Astral Sorcery 2.0.0.4、Curios 9.5.1。以附带 `VERIFICATION.md` 为准。

这些验证不等于在你完整的 Youer 插件服、真实客户端及两台服务器上完成联测。上线前至少验证：A→B→A 修改三个模块、套娃/末影箱/饰品位、KTG4 慢加载、加载时断线重进、数据库断连、玩家交易背包、备份回档、死亡重生和管理员解除保护。先用独立测试数据库与世界副本。
