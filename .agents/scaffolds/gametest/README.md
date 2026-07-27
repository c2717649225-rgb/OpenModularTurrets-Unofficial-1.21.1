# 通用 GameTest scaffold

`FeatureGameTests.java.template` 面向 Minecraft 1.21.1 + NeoForge 21.1.x，不绑定任何示例 Mod。

使用时：

1. 将 `{{MOD_GROUP}}`、`{{MODID}}`、`{{TEMPLATE_NAME}}` 替换为宿主工程真实值，并复制到 `src/main/java`。
2. 将结构模板放到 `src/main/resources/data/{{MODID}}/structure/{{TEMPLATE_NAME}}.nbt`，或由宿主既有资源流程提供。
3. 用真实的 Arrange / Act / Assert 替换 `helper.fail(...)`。该失败哨兵用于防止占位测试误报全绿。
4. 运行：

   ```text
   python .agents/gates/gametest_gate.py --require-tests --run
   ```

`@GameTestHolder("{{MODID}}")` 负责自动注册。`@PrefixGameTestTemplate(false)` 使模板路径保持为 `{{MODID}}:{{TEMPLATE_NAME}}`，不会自动添加 Java 类名。
