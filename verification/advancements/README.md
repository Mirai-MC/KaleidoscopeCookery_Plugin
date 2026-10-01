# 成就集成运行验证

`AdvancementProbe.java` 是独立测试插件源文件，供隔离的 Paper 26.3、CraftEngine 26.9.2、完整森罗物语包和本地 UltimateAdvancementAPI Pro 2.8.1-pro.2 使用。不要放到正式服务器。

用测试服的 Paper API、UAA Pro、本插件及相关运行库作为 Java 编译路径，将编译后的类打包成测试插件，附带以下 `plugin.yml`：

```yaml
name: AdvancementProbe
version: 1.0
main: AdvancementProbe
api-version: '1.21'
depend: [KaleidoscopeCookeryPlugin, UltimateAdvancementAPI]
```

使用全新的隔离测试服目录，将 UAA 存储设为 SQLite。连续启动并正常关闭三次，保留同一数据库：

1. 验证 31 个展示节点、6 个独立分项、Pro 布局和原生本地化组件；写入部分进度，检查重复获取、重建、禁用和重新启用。
2. 检查第一次关闭后部分进度持久化，再完成双椒、套装和隐藏挑战。
3. 检查完成后的进度仍存在，再执行实际 `/ce reload all` 并检查成就树和完成状态。

每次写出 `advancement-probe-result.json`，成功时 `success` 为 `true`，并更新 `advancement-phase.txt`。测试直接检查 SQLite 的父项记录和离线完成记录，覆盖分项完成时的写入顺序。测试通过 UAA 的数据库执行器创建一个专用离线测试账户，使用实际分项对象更新进度；它不模拟真实客户端登录、通知画面或玩家操作。真实玩法的钩子与此验证的持久化检查分开评估。
