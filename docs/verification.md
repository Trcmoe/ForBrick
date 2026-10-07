# 验证记录

更新日期：2026-10-07（Asia/Shanghai）。

## 1.0.1 语言切换修复

在 Windows 上通过真实展开的 `JComboBox` 菜单发送鼠标释放事件，复现 1.0.0 的 `IndexOutOfBoundsException`：语言监听器在菜单尚未关闭时调用 `setContentPane`，删除仍带活动 popup 的组件树，破坏了 Swing 分层容器的删除流程。

修复保留原有组件，只原地更新文字；切换语言不执行磁盘扫描，也不改变 busy 状态。回归测试连续点击展开的菜单切换语言，验证菜单关闭并释放输入、控件继续响应、当前页签 / 待应用路径 / 状态消息和版本选择保留；即使其他进程占用安装器协调端口，切换语言也不会启动文件操作。随后执行安装、一键卸载和逐字节还原检查。CI 同时在 Windows Java 17 桌面和 Linux Java 17 虚拟显示器中运行该测试。

1.0.1 安装器可以直接卸载旧安装。若要获得新增的退出提示框，需要对旧实例再次点击安装，升级到 1.0.1 运行库。

## 1.0.1 退出提示与升级恢复

游戏入口和 Java agent 都先启动独立 Java 辅助进程，再以退出码 0 结束游戏。辅助进程等待父进程持有的标准输入管道关闭后，才显示信息框；不依赖 Java 9 的进程 API，也不写入 PID 文件。Windows 使用 `javaw.exe`，其他系统使用 `java`。辅助进程不继承启动器环境中的 JVM agent 和语言覆盖参数，避免递归注入，并按系统显示语言选择中文或英文。

运行库测试验证管道在 EOF 前保持等待、headless 辅助进程正常结束、独立运行库可在中文工作目录下加载、中文地区和其他语言的文案选择。`NoticeSmoke` 在独立子 JVM 中运行实际提示框入口，分别模拟中文和英文系统显示语言；检查 EOF 前无窗口、EOF 后窗口文案 / 下载地址 / 按钮正确，点击关闭后进程退出。

1.0.1 内置经过固定 SHA-256 校验的 1.0.0 原始运行库样本，以精确识别旧安装。旧、新运行库使用独立路径和所有权标记；所有实例卸载后统一清理。升级前后保存必要的配置校验值，测试旧版直接卸载、旧版升级、混合版本安装、外部追加参数和库的保留，以及升级写入中断后的恢复。已有用户修改的配置不会被当作可直接覆盖的原始安装结果。

## 本地验证

- 本地环境：Windows 11，Zulu JDK 25.0.2，Maven 3.9.15。1.0.1 最终测试结果见下方发布验证记录。
- 核心测试：正常安装、原始字节恢复、继承配置、额外配置保留、重复操作、共享库、缺失库修复、外部配置冲突、运行库被改写、损坏 / 丢失备份、安装中断、未知残留提示、路径限制、并发线程和独立进程、端口占用无写入、杀死持锁进程后恢复、中文目录及版本名，以及旧版升级恢复。
- 独立 JVM / 运行库测试：独立 JAR 退出；Java agent 使原始 `main` 不执行；运行库只含 Java 8 字节码与清单、生成结果一致；无桌面环境时安装器给出 CLI 提示并返回非零状态；中文工作目录中的普通入口使用相对运行库路径、agent 使用目录外的 ASCII 绝对路径；新增提示框等待退出和本地化检查。
- `UiSmoke` 在真实 Swing 组件上测试两个临时目录。验证输入新路径会禁用操作、切换语言不丢失待应用路径、安装只作用于应用后的目录、“一键卸载全部”精确恢复并清理管理文件和运行库。文档 PNG 是这个实际组件树的离屏渲染，未另画界面。

### 1.0.1 本地发布验证

- `mvn verify`：34 项测试通过（25 项核心、5 项游戏入口 / agent、4 项提示框协议与本地化），无失败或错误。
- Windows 真实桌面上的 `UiSmoke` 和 `NoticeSmoke` 均通过。中英文提示框的每个字符均在可见区域内；长句使用自动换行，关闭按钮可结束辅助进程。
- 使用已发布的 1.0.0 安装器真实安装临时实例，再以 1.0.1 安装器升级、启动及卸载：原始 JSON 逐字节恢复，管理记录和两代运行库均清理。
- 最终代码提交 `d45160e` 的 [CI #37643642537](https://github.com/Trcmoe/ForBrick/actions/runs/37643642537) **12 / 12 组合全部通过**，每组执行 34 项测试；Windows / Linux Java 17 的安装器和提示框桌面测试通过。
- Windows JDK 25 与 Linux CI JDK 17 的运行库逐字节一致，SHA-256：`81724ff596968e6b6b08205e159cfc3f0810566beff2d9440130f15a3fdd4e5b`。运行库避免使用不同编译器会生成不同引导常量布局的 lambda，防止同一发行版本的文件所有权识别受构建环境影响。

## 1.0.0 官方元数据样本

在临时目录中使用以下真实启动配置，分别安装 ForBrick，再用配置里的 `mainClass` 和 ForBrick 运行库启动独立 JVM。六个样本均返回 0，标准输出 / 标准错误为空；最终统一卸载，六份原始 JSON 均逐字节还原，临时游戏目录中没有新增残留路径。

| 来源 | 版本 |
| --- | --- |
| [Mojang 官方版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json) | 1.7.10、1.12.2、1.16.5、1.21.1、26.3 |
| [Fabric Meta 官方生成配置](https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.19.5/profile/json) | Fabric Loader 0.19.5 / Minecraft 1.21.1 |

这些检查验证元数据接入、Java 启动层、恢复流程，**没有下载游戏全部依赖、登录游戏或逐个自动操作第三方启动器**。Forge 的入口在构造配置测试中覆盖；NeoForge、Quilt、LiteLoader 和 OptiFine 通过相同元数据机制接入，未宣称逐个完成真实启动器测试。

## 跨平台 CI

[Build and verify](https://github.com/Trcmoe/ForBrick/actions/workflows/ci.yml) 配置了 Windows / Linux / macOS 与 Java 8 / 17 / 21 / 25 的 12 个组合。macOS Java 8 使用 Intel，其他 macOS 组合使用 ARM。每个组合都执行全套核心与独立 JVM 测试；Windows Java 17 和 Linux Java 17 还运行 Swing 界面与提示框测试。具体执行状态以 Actions 页面为准。

1.0.0 的 [最终 CI 运行 #37393881102](https://github.com/Trcmoe/ForBrick/actions/runs/37393881102) **12 / 12 组合全部通过**，每个组合执行 22 项测试；当时的界面测试未打开语言下拉菜单，1.0.1 已补充真实 popup 点击。Windows JDK 25 与 Linux CI JDK 17 导出的运行库 SHA-256 一致：`244068776d1f2a231dcee81845a17d0327fca3ce79f076e4c7a0a7ecd0cdbb63`。

## 复现

```sh
mvn -B verify
```

有图形桌面时，可额外运行：

```sh
# Linux / macOS
java -Djava.awt.headless=false -cp "target/test-classes:target/forbrick-installer-1.0.1.jar" dev.forbrick.UiSmoke target/ui-preview
java -Djava.awt.headless=false -cp "target/test-classes:target/forbrick-installer-1.0.1.jar" dev.forbrick.runtime.NoticeSmoke

# Windows：classpath 分隔符为分号
java -Djava.awt.headless=false -cp "target/test-classes;target/forbrick-installer-1.0.1.jar" dev.forbrick.UiSmoke target/ui-preview
java -Djava.awt.headless=false -cp "target/test-classes;target/forbrick-installer-1.0.1.jar" dev.forbrick.runtime.NoticeSmoke
```

Linux 无桌面环境可在前面加 `xvfb-run -a`。界面测试自行创建 `target/` 下的隔离目录，不读取用户的真实 Minecraft 安装。

## 已知边界

- ForBrick 自身不产生错误输出；启动器、其他 agents、JVM 或缺失依赖仍可能先产生提示。
- 信息框需要可用的图形桌面和辅助 JVM。headless 环境只退出游戏；启动器若终止整个子进程树，可能使提示框无法显示。手动导出的运行库仍应使用 ASCII 文件名与路径。
- 标准元数据被启动器刷新后可能失效。Prism / MultiMC 的独立组件格式需使用手动 Java agent 方式。
- 原始配置、恢复记录或暂存文件被破坏时，清理会报告冲突并保留可恢复的数据，不会虚报“无残留”。
- 并发协调使用短暂的 `127.0.0.1:47691` 绑定，关闭后无锁文件；该端口冲突或本机安全策略禁止绑定会阻止写入。此机制不协调不同机器对共享目录的写入。
- 目录链接 / junction 会保守拒绝。没有操作系统符号链接权限时，测试中的符号链接子检查无法执行；路径穿越拒绝仍会验证。
- Windows 原生 `java.exe` 按系统 ANSI 代码页转换命令行，因此不保证支持代码页之外的字符参数。英文 Windows CI 实测把中文 JAR 文件名转换为 `??` 后报找不到文件；这发生在 ForBrick 执行之前。Java 17 / 21 / 25 的原生 instrumentation 对中文工作目录中的相对 agent 路径还会产生 `AddToSystemClassLoaderSearch` 错误。发行文件名使用 ASCII；普通 `-jar` 验证中文工作目录加相对路径，手动 agent 验证目录外的 ASCII 绝对路径。实现依据见 [OpenJDK 原生启动入口](https://github.com/openjdk/jdk/blob/master/src/java.base/share/native/launcher/main.c)。
