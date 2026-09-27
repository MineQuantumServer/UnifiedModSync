# 更新记录

## 1.0.1

- 修复 KTG4 就绪检查错误选择 `isLoaded(PlayerStatus)` 重载，导致 `IllegalArgumentException: argument type mismatch`、玩家进入保护状态的问题。
- 使用 KTG4 插件类加载器加载 `Go4Player`，精确调用 `boolean isLoaded(Go4Player)`。接口缺失、适配对象或返回类型不兼容时仍保持保护，不跳过检查。
- 增加重载选择、未就绪状态和不兼容接口的回归测试。

停服后用 1.0.1 替换旧同步模组 JAR，再启动。保留原有配置与数据库，不需要删表或清空玩家数据；仍须在实际 Youer + KTG4 环境验证入服流程。
