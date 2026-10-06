# 验证记录

日期：2026-10-06（Asia/Shanghai）。

## 本地验证

- Windows 11，Zulu JDK 25.0.2，Maven 3.9.15：`mvn verify` 通过，22 项测试，0 失败、0 错误。
- 17 项核心测试：正常安装、原始字节恢复、继承配置、额外配置保留、重复操作、共享库、缺失库修复、外部配置冲突、运行库被改写、损坏 / 丢失备份、安装中断、未知残留提示、路径限制、并发线程和独立进程、端口占用无写入、杀死持锁进程后恢复、中文目录及版本名。
- 5 项独立 JVM / 运行库测试：独立 JAR 静默退出；Java agent 使原始 `main` 不执行；运行库只含 Java 8 字节码与清单、生成结果一致；无桌面环境时安装器给出 CLI 提示并返回非零状态；中文工作目录中的普通入口使用相对运行库路径、agent 使用目录外的 ASCII 绝对路径。
- `UiSmoke` 在真实 Swing 组件上测试两个临时目录。验证输入新路径会禁用操作、切换语言不丢失待应用路径、安装只作用于应用后的目录、“一键卸载全部”精确恢复并清理管理文件和运行库。文档 PNG 是这个实际组件树的离屏渲染，未另画界面。

## 官方元数据样本

在临时目录中使用以下真实启动配置，分别安装 ForBrick，再用配置里的 `mainClass` 和 ForBrick 运行库启动独立 JVM。六个样本均返回 0，标准输出 / 标准错误为空；最终统一卸载，六份原始 JSON 均逐字节还原，临时游戏目录中没有新增残留路径。

| 来源 | 版本 |
| --- | --- |
| [Mojang 官方版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) | 1.7.10、1.12.2、1.16.5、1.21.1、26.3 |
| [Fabric Meta 官方生成配置](https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.19.5/profile/json) | Fabric Loader 0.19.5 / Minecraft 1.21.1 |

这些检查验证元数据接入、Java 启动层、恢复流程，**没有下载游戏全部依赖、登录游戏或逐个自动操作第三方启动器**。Forge 的入口在构造配置测试中覆盖；NeoForge、Quilt、LiteLoader 和 OptiFine 通过相同元数据机制接入，未宣称逐个完成真实启动器测试。

## 跨平台 CI

[Build and verify](https://github.com/Trcmoe/ForBrick/actions/workflows/ci.yml) 配置了 Windows / Linux / macOS 与 Java 8 / 17 / 21 / 25 的 12 个组合。macOS Java 8 使用 Intel，其他 macOS 组合使用 ARM。每个组合都执行全套核心与独立 JVM 测试；Linux Java 17 还运行 Swing 界面测试。具体执行状态以 Actions 页面为准。

本次发行的 [最终 CI 运行 #37393881102](https://github.com/Trcmoe/ForBrick/actions/runs/37393881102) **12 / 12 组合全部通过**，每个组合执行 22 项测试；Linux 的真实 Swing 冒烟测试也通过。本地 Windows JDK 25 与 Linux CI JDK 17 导出的运行库 SHA-256 一致：`244068776d1f2a231dcee81845a17d0327fca3ce79f076e4c7a0a7ecd0cdbb63`。

## 复现

```sh
mvn -B verify
```

有图形桌面时，可额外运行：

```sh
# Linux / macOS
java -Djava.awt.headless=false -cp "target/test-classes:target/forbrick-installer-1.0.0.jar" dev.forbrick.UiSmoke target/ui-preview

# Windows：classpath 分隔符为分号
java -Djava.awt.headless=false -cp "target/test-classes;target/forbrick-installer-1.0.0.jar" dev.forbrick.UiSmoke target/ui-preview
```

Linux 无桌面环境可在前面加 `xvfb-run -a`。界面测试自行创建 `target/` 下的隔离目录，不读取用户的真实 Minecraft 安装。

## 已知边界

- ForBrick 自身不产生错误输出；启动器、其他 agents、JVM 或缺失依赖仍可能先产生提示。
- 标准元数据被启动器刷新后可能失效。Prism / MultiMC 的独立组件格式需使用手动 Java agent 方式。
- 原始配置、恢复记录或暂存文件被破坏时，清理会报告冲突并保留可恢复的数据，不会虚报“无残留”。
- 并发协调使用短暂的 `127.0.0.1:47691` 绑定，关闭后无锁文件；该端口冲突或本机安全策略禁止绑定会阻止写入。此机制不协调不同机器对共享目录的写入。
- 目录链接 / junction 会保守拒绝。没有操作系统符号链接权限时，测试中的符号链接子检查无法执行；路径穿越拒绝仍会验证。
- Windows 原生 `java.exe` 按系统 ANSI 代码页转换命令行，因此不保证支持代码页之外的字符参数。英文 Windows CI 实测把中文 JAR 文件名转换为 `??` 后报找不到文件；这发生在 ForBrick 执行之前。Java 17 / 21 / 25 的原生 instrumentation 对中文工作目录中的相对 agent 路径还会产生 `AddToSystemClassLoaderSearch` 错误。发行文件名使用 ASCII；普通 `-jar` 验证中文工作目录加相对路径，手动 agent 验证目录外的 ASCII 绝对路径。实现依据见 [OpenJDK 原生启动入口](https://github.com/openjdk/jdk/blob/master/src/java.base/share/native/launcher/main.c)。
