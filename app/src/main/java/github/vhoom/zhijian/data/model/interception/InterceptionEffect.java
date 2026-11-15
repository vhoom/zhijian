package github.vhoom.zhijian.data.model.interception;

/**
 * 状态机输出的效果标识，交给任务协调层执行。
 *
 * 维护日期：2026年11月08日
 */
public enum InterceptionEffect { NONE, RETURN_HOME, RETURN_TO_TARGET, ISSUE_GRANT, RESUME_USAGE, DISMISS }
