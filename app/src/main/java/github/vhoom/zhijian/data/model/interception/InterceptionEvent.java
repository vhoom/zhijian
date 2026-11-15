package github.vhoom.zhijian.data.model.interception;

/**
 * 页面和时钟报告的纯数据事件；不包含导航、持久化或状态机实现。
 *
 * 维护日期：2026年11月08日
 */
public enum InterceptionEvent { ABANDON, CONTINUE, CHALLENGE_COMPLETED, PAUSE_COMPLETED, RESET }
