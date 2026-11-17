---
date: 2026-11-17
---

# 测试目录与运行说明

本目录对应当前工作区的测试组织。审查结论与已修正问题见[测试有效性审查](测试有效性审查.md)，历史运行结果见[真机验证](真机验证.md)。时段、配置更替及键盘窗口误判的修复依据见[时段规则修复核对](时段规则修复核对.md)，本次执行结果及挑战页休息验证见[前台窗口修复验证](前台窗口修复验证.md)。测试清单分别见[本地单元测试](本地单元测试.md)和[设备测试](设备测试.md)。源码声明数量为77个本地测试类、54个设备测试类，共778个 `@Test` 方法；循环组合不额外计数。这些数字表示测试清单，不表示通过数量或覆盖率。

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
| 有启动图标的输入法、其他浮层及真正前台切换 | — | `ForegroundWindowContinuationTest`；真实事件与独立偏好路径，系统组件元数据和前台查询回调采用可控输入，本次10个方法已通过 |
| 挑战页静止、操作及完成后的连续休息 | `ContinuousUsageCycleTest` | `RepairContinuationTest#idleAndActiveChallengesPreserveContinuousRest`；真实按压、单词、数独页面及正式周期存储 |
| 重装、首次生效起点与强度切换 | `UsageTrackingStartTest` | `UsageEvidenceReaderTest`、`UsageTrackingStoreTest`、`InstallationRestoreTest`、`RetreatStorageTest` |
| 跨日生效与计算耗时后的复查期限 | `InterceptionEvaluationTest`、`WeekdayRuleScheduleTest` | `ForegroundEvaluationTest` 的午夜、延迟回调及额度到期用例 |
| 随心与修心禁用继续、冻结开关与真实重进 | `BlockContinuationPolicyTest`、`UsageTimelineTest`、`ReminderRepeatPolicyTest` | `BlockContinuationStoreTest`、`RepairContinuationTest`、`InterceptionReentryTest#wuweiContinueAfterReusedPageDoesNotImmediatelyBlockAgain`、`ForegroundEvaluationTest` |
| 连续提醒退出与明确继续、同组提醒间隔 | `ReminderRepeatPolicyTest` | `PendingInterceptionStoreTest` 的退出与继续用例，覆盖两档强度及全天、时段提醒的正式写入和共享休息读取；本次未运行 |
| 修改使用阈值后的再次提醒与原休息保留 | `ReminderRepeatPolicyTest` | `ReminderThresholdRuntimeTest` 的全天阈值升降、同组换应用及同窗口参数更新，覆盖正式提交与最终展示检查；本次未运行 |
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

清单与源码数量一致：77个本地测试类、458个方法；54个设备测试类、320个方法。共享夹具及Debug预览入口不计入JUnit清单。循环中的多个档位和参数组合不额外计数。本次实际执行结果以[前台窗口修复验证](前台窗口修复验证.md)为准。

已将脱离正式调用链的优先级辅助类测试替换为 `RuleEnginePriorityTest`，直接验证当前规则入口；设备优先级夹具现在分别保存展示、休息事实和周期，断言新禁用优先展示且原休息保留。前台调度测试补齐真实启动请求、旧结果不启动、独立初始化与静心分支断言，并清理测试组自己的限制事实。

`ProtectionDeviceSession` 和 `GroupedContinuousUsageTest.DeviceSession` 共用恢复队列；测试组在尝试保存前登记，单项失败不会跳过其余组、配置或监听器。新增 `FixtureCleanupTest` 的 5 个故障注入用例，验证部分初始化失败、清理异常汇总、原始错误保留和重复关闭；这 5 个用例已在真机通过。正式服务夹具另清理测试应用自身的连续周期。

前台允许结果按计算开始时的午夜和额度期限中较早者安排复查，并扣除后台计算及主线程排队耗时。`InterceptionEvaluationTest` 的 5 个本地用例和 `ForegroundEvaluationTest` 的 4 个真机 Handler 用例均已通过，覆盖星期切换、较早额度、耗时扣除及到期边界。固定时间只作为方法输入，不修改设备时钟，没有实际等待午夜。

提交 `828006b` 的真机运行修正了分组服务用例的单成员非法夹具，以及挑战用例重新查找已移除按钮、对当时按压候选规则的错误假定。当前独立挑战开关已允许仅选择单词；相关用例仍通过正式设置和会话选择策略准备输入。

没有发现 `@Ignore`。档位切换用例与提醒阈值运行用例使用 `Assume`，条件不满足时属于跳过，不是通过。截图开关只控制辅助函数的文件导出，不会绕过主测试断言。预览测试、独立组件测试和真实服务测试的覆盖边界不同，不能互相替代。

## 本次验证范围

本次全部77类458个本地方法通过；9类69个设备方法通过，无失败或跳过。设备范围覆盖窗口识别、旧前台结果隔离、继续确认存储、三种挑战的正式继续与休息计时、真实重进、分组使用及额度、分组服务拦截，以及提醒阈值更新和窗口端点更替。调试包构建与Lint执行成功，Lint有71条警告。逐类结果、测试问题修正及设备授权恢复见[前台窗口修复验证](前台窗口修复验证.md)。

`ReminderThresholdRuntimeTest` 的4个方法补测全部通过；端点更替夹具同时保存旧提醒及其匹配的旧周期，验证旧休息与新提醒分别保留。其他设备方法未在本次执行，安装恢复、完整主题及大屏方向仍须按相关任务验证。

窗口回归使用正式服务事件入口和独立偏好，组件及系统前台回调采用可控输入；真实服务和挑战回归使用正式页面与存储，并恢复测试设置。它们不能代替厂商输入法的手动验收或未执行的设备用例。

实现边界分别见[规则计时起点](../architecture/规则计时起点.md)、[数独挑战与进度](../architecture/数独挑战与进度.md)和[挑战进度与重新进入](../architecture/挑战进度与重新进入.md)。

历史结果见[真机验证](真机验证.md)，不作为本次未执行事项的通过证据。

