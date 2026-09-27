# UnifiedModSync

面向 Minecraft 1.21.1 / NeoForge 21.1.251 的服务端模块化数据同步模组。

内置时装工坊衣柜、精妙背包内容、星辉研究三个模块，使用 MySQL 持久化，可选 Redis 协调。支持等待 KTG4 加载、同步保护、自动保存、备份，以及按玩家和模块保存、回档、重试、解除保护。

## 文档

- [安装、配置和管理命令](README-zh_CN.md)
- [参考插件分析与同步原理](ANALYSIS-zh_CN.md)
- [测试结果和验证边界](VERIFICATION.md)
- [配置示例](src/main/resources/unified-mod-sync.example.properties)
- [旧数据迁移工具](tools/migrate_legacy.py)

## 构建

需要 Java 21。

```shell
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

将 `build/libs/unified-mod-sync-neoforge-1.21.1-1.0.1.jar` 放入服务端 `mods`，不要使用 `unshaded-dev-only` JAR。客户端无需安装本同步模组。

首次启动会生成 `config/unified-mod-sync.properties`。填写数据库配置并设置 `enabled=true` 后重启。更换旧同步方案前，请先阅读数据迁移说明。

## 验证范围

已完成存储测试、真实模组 GameTest 和发行 JAR 独立加载测试；尚未在完整 Youer 双服环境中完成真实客户端联测。请先在世界副本和独立测试数据库中验收。KTG4 与本模组使用独立提交过程，不能保证两套数据库在突然断电时拥有完全相同的检查点。

## 扩展

新增模块实现 `dev.unifiedsync.api.SyncModule`，外部扩展模组可在 common setup 调用 `UnifiedSync.registerModule(MyModule::new)`。模块业务操作在服务器主线程执行，数据库读写使用独立快照。

本项目代码采用 [MIT License](LICENSE)。打包的第三方数据库驱动和依赖遵循各自许可证，详见 `src/main/resources/META-INF/licenses` 及发行 JAR 内的许可证文件。
