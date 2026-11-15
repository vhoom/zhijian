---
date: 2026-11-15
---

# 测试目录与运行说明

本目录对应当前工作区的测试组织。审查结论与已修正问题见[测试有效性审查](测试有效性审查.md)，历史运行结果见[真机验证](真机验证.md)。测试清单分别见[本地单元测试](本地单元测试.md)和[设备测试](设备测试.md)。源码声明数量为74个本地测试类、51个设备测试类，共745个 `@Test` 方法；循环组合不额外计数。这些数字表示测试清单，不表示通过数量或覆盖率。

## 源码组织

| 入口 | 职责 | 框架 |
| --- | --- | --- |
| `app/src/test/java/github/vhoom/zhijian/` | 不依赖 Android 运行时的模型、规则、时间计算和工具测试 | JUnit 4 |
| `app/src/androidTest/java/github/vhoom/zhijian/` | Android 资源、组件、页面、Room 与真实服务集成测试 | AndroidX JUnit / Espresso |
| `app/src/androidTest/java/github/vhoom/zhijian/fixture/` | 测试目标页面、稳定会话、共享断言和事件分发 | 测试 APK 内部工具 |
| `app/src/debug/` | Debug 变体的手动预览入口 | 非 JUnit 测试 |

测试包优先与被测模块一致。页面集成测试归入 `ui/activity`，绑定器测试归入 `ui/binder`，独立组件归入 `ui/component`，启动图标资源归入 `resources`，拦截文案与预览归入各自子包。`data/store/RetreatChangePolicyTest` 使用包内 `PolicySnapshot.load` 构建真实快照，因此保留在存储包；跨层存储集成用例也保留在 `data/store`。不为移动测试扩大生产接口的可见性。

新增测试使用 `*Test.java` 和描述行为的 camelCase 方法名。一个测试类围绕同一模块或同一集成边界组织；跨用例复用的设备工具放入 `fixture`，不依赖其他 `*Test` 类。Java 主类 Javadoc 和 Markdown 顶部维护日期遵循仓库规范。

## 运行入口

从仓库根目录在 PowerShell 中运行。使用 JDK 17；SDK、Java 目标和测试运行器以 [app/build.gradle](../../app/build.gradle) 为准。当前最低 Android 版本为 10（API 29），编译和目标 SDK 为 36，设备运行器为 `androidx.test.runner.AndroidJUnitRunner`。

| 目的 | 命令 |
| --- | --- |
| 执行本地测试 | `.\gradlew.bat :app:testDebugUnitTest` |
| 只编译设备测试 APK | `.\gradlew.bat :app:assembleDebugAndroidTest` |
| 在连接的设备执行测试 | `.\gradlew.bat :app:connectedDebugAndroidTest` |

设备测试包含真实服务与系统授权路径，应按[设备测试的运行条件](设备测试.md#运行条件与设备影响)选择类别。单类与单方法过滤示例见两份分类目录。Unix 环境将入口替换为 `./gradlew`；本轮实际执行范围见下文。

## 按变更选择回归

| 变更位置 | 本地用例 | 设备用例 |
| --- | --- | --- |
| 管控列表标签与来源 | `AppListOrderTest`、`RuleSourceTest`、`PolicySnapshotTest` | 对应列表操作仍需页面观察 |
| 首页手动条数 | `UsageRowLimitTest`、`UnsignedIntegerTest` | `UsageRowSettingsBinderTest` |
| 首页统计、日期与刷新 | `UsageRowBudgetTest`、`UsageCalendarTest`、`UsageDateSnapTest`、`DailyUsagePolicyTest` | `DashboardBinderTest`、`UsageDateSelectorTest`、`PullRefreshLayoutTest`、`SetupNavigationTest` |
| 逐日规则与编辑草稿 | `RuleGroupValidatorTest`、`ReminderWindowValidationTest`、`WeekdayRuleDraftTest` | `WeekdayPolicyStoreTest`、`RuleCountInputTest`、`StorageUiTest`、`RuleGroupingTest` |
| 手动规则输入与保存提示 | `RuleInputLimitsTest`、`RuleGroupValidatorTest` | `RuleInputBindingTest`、`RuleCountInputTest`、`RuleValidationUiTest`、`InputErrorTest` |
| 限次范围与额度释放 | `CountRuleEvaluatorTest`、`RuleGroupValidatorTest`、`UsageTimelineTest`、`WeekdayRuleScheduleTest` | `WeekdayPolicyStoreTest`、`RuleCountInputTest`、`RuleGroupingTest`、`InterceptionRecoveryLifecycleTest` |
| 前台观察、阈值与提醒 | `ForegroundObservationTest`、`ContinuousUsageCycleTest`、`RuleEngineTest`、`RuleEnginePriorityTest`、提醒系列 | `ForegroundEvaluationTest`、`runtime/interception` 系列 |
| 重装、首次生效起点与强度切换 | `UsageTrackingStartTest` | `UsageEvidenceReaderTest`、`UsageTrackingStoreTest`、`InstallationRestoreTest`、`RetreatStorageTest` |
| 跨日生效与计算耗时后的复查期限 | `InterceptionEvaluationTest`、`WeekdayRuleScheduleTest` | `ForegroundEvaluationTest` 的午夜、延迟回调及额度到期用例 |
| 随心与修心禁用继续、冻结开关与真实重进 | `BlockContinuationPolicyTest`、`UsageTimelineTest`、`ReminderRepeatPolicyTest` | `BlockContinuationStoreTest`、`RepairContinuationTest`、`InterceptionReentryTest#wuweiContinueAfterReusedPageDoesNotImmediatelyBlockAgain`、`ForegroundEvaluationTest` |
| 持久化、归档与授权 | `PolicySnapshotTest`、`UsageBucketsTest`、`PassGrantTest` | 存储系列、`UsageArchiveStoreTest`、`PendingInterceptionStoreTest` |
| 设备夹具初始化与恢复 | — | `FixtureCleanupTest`、使用两个设备会话夹具的真实链路用例 |
| 拦截页面与挑战 | `InterceptionFlowTest`、`InitialActionPlanTest`、按压、单词与数独系列 | `ui/interception` 系列、`SudokuProgressStoreTest`、`WordProgressStoreTest` |
| 权限、主题与诊断 | 权限、诊断工具系列 | `PermissionIntentsTest`、`AccessibilityRegistrationTest`、`DialogThemeTest`、`AboutDiagnosticsTest` |

表格用于定位现有回归范围；没有对应自动断言的页面行为需要单独验证，不能用邻近用例的存在推断已覆盖。

## 共享夹具

| 工具 | 用途与边界 |
| --- | --- |
| [InitialPageSessions](../../app/src/androidTest/java/github/vhoom/zhijian/fixture/InitialPageSessions.java) | 按正式首屏方案查找稳定会话，不增加生产概率开关。 |
| [InterceptionAssertions](../../app/src/androidTest/java/github/vhoom/zhijian/fixture/InterceptionAssertions.java) | 共用的旧页头资源清理断言；跨子包使用。 |
| [TouchEvents](../../app/src/androidTest/java/github/vhoom/zhijian/fixture/TouchEvents.java) | 按原独立组件用例的时间语义分发事件，在 finally 中回收 MotionEvent；不模拟完整多指或速度轨迹。 |
| [UsageTargetActivity](../../app/src/androidTest/java/github/vhoom/zhijian/fixture/UsageTargetActivity.java) | 在测试 APK 包名下提供可启动的真实前台页面，由测试 Manifest 注册。 |
| [FixtureCleanup](../../app/src/androidTest/java/github/vhoom/zhijian/fixture/FixtureCleanup.java) | 测试专用的恢复队列；初始化失败立即回滚，逐项尝试恢复并汇总异常，重复关闭不重复执行。 |
| [ProtectionDeviceSession](../../app/src/androidTest/java/github/vhoom/zhijian/fixture/ProtectionDeviceSession.java) | 真实服务回归会话；页面创建前配置本次交互，备份规则、强度、挑战设置、随心与修心流程开关和系统授权，初始化失败或正常结束时逐项恢复并清理测试身份及访问确认；只构造未启动时只释放监听器。进程强制终止仍可能无法恢复。 |
| [DebugInterceptionActivity](../../app/src/debug/java/github/vhoom/zhijian/debug/DebugInterceptionActivity.java) | 仅 Debug 变体可用的 ADB 预览入口，不计入测试类和用例数量。 |

## 当前审查结论

清单与源码数量一致：74 个本地测试类、448 个方法；51 个设备测试类、297 个方法。设备目录另有 7 个共享夹具 Java 文件，Debug 预览入口不计入 JUnit 清单。循环中的多个档位和参数组合不额外计数。

已将脱离正式调用链的优先级辅助类测试替换为 `RuleEnginePriorityTest`，直接验证当前规则入口；设备优先级夹具现在分别保存展示、休息事实和周期，断言新禁用优先展示且原休息保留。前台调度测试补齐真实启动请求、旧结果不启动、独立初始化与静心分支断言，并清理测试组自己的限制事实。

`ProtectionDeviceSession` 和 `GroupedContinuousUsageTest.DeviceSession` 共用恢复队列；测试组在尝试保存前登记，单项失败不会跳过其余组、配置或监听器。新增 `FixtureCleanupTest` 的 5 个故障注入用例，验证部分初始化失败、清理异常汇总、原始错误保留和重复关闭；这 5 个用例已在真机通过。正式服务夹具另清理测试应用自身的连续周期。

前台允许结果按计算开始时的午夜和额度期限中较早者安排复查，并扣除后台计算及主线程排队耗时。`InterceptionEvaluationTest` 的 5 个本地用例和 `ForegroundEvaluationTest` 的 4 个真机 Handler 用例均已通过，覆盖星期切换、较早额度、耗时扣除及到期边界。固定时间只作为方法输入，不修改设备时钟，没有实际等待午夜。

提交 `828006b` 的真机运行修正了分组服务用例的单成员非法夹具，以及挑战用例重新查找已移除按钮、对当时按压候选规则的错误假定。当前独立挑战开关已允许仅选择单词；相关用例仍通过正式设置和会话选择策略准备输入。

没有发现 `@Ignore`。一个档位切换用例使用 `Assume`，条件不满足时属于跳过，不是通过。截图开关只控制辅助函数的文件导出，不会绕过主测试断言。预览测试、独立组件测试和真实服务测试的覆盖边界不同，不能互相替代。

## 本次验证范围

规则起点变更新增 4 个本地方法和 11 个设备方法，覆盖首次开启、旧历史裁剪、启用时已在前台、三档强度共享起点、数据库重建、安装身份隔离、连续周期身份及事务回滚。其中 `InstallationRestoreTest` 的 4 个方法通过正式初始化验证启动标识碰撞、来源墙钟较快、同次安装重启，以及偏好清理后数据库提交失败的重试。相关真实链路夹具保存周期时带入当前统计身份。本轮只做静态核对，未编译、运行测试或进行真机验证，详见[规则计时起点](../architecture/规则计时起点.md)。

项目命名调整已同步生产和测试中的 `ZhijianApplication`、`ZhijianStorage`、`ZhijianDatabase`、`ZhijianAccessibilityService`、主题资源引用、测试参数及 `zhijian.usage` 导出格式断言。本次仅做静态引用核对，未编译或执行测试；数据库启用新的 `zhijian.db`，旧安装的无障碍服务授权需重新开启，不能沿用此前版本的运行结果作为验证。

静态核对涉及测试文件与方法清单、文档数量、正式资源 ID、挑战枚举接线、页面重建和文件保存边界，不代表全部 745 个方法已运行或通过。

当前挑战变更新增 10 个本地方法和 17 个设备方法，覆盖三档数独题目、难度确认与取消、九键填入／替换／清空、给定格不可选、未开始退出重进刷新、已答题进度恢复、单词重启时钟与旧写入拒绝，以及完成后继续流程。正式单词操作夹具等待每次答案落盘，并备份恢复用户单词文件。昼夜配色只做资源与对比度静态核对；所有新用例均未运行，未编译或进行真机验证。实现边界见[数独挑战与进度](../architecture/数独挑战与进度.md)和[挑战进度与重新进入](../architecture/挑战进度与重新进入.md)。

提交 `828006b` 的本地 434 个通过及真机 39 个通过、1 个条件跳过记录见[真机验证](真机验证.md)。它们属于此前的拦截复查验证，不作为当前挑战开关、数独及夹具变更的通过证据。

