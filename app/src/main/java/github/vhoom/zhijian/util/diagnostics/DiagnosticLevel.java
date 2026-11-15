package github.vhoom.zhijian.util.diagnostics;

/**
 * 构建类型只影响详细日志，正式版始终保留关键事件和异常。
 *
 * 维护日期：2026年11月08日
 */
public enum DiagnosticLevel {
    DEBUG, INFO, WARN, ERROR;

    /**
     * 判断当前构建是否保留此级别。
     * @param debugBuild 是否为调试构建
     * @return 正式版仅过滤 DEBUG
     */
    public boolean retainedIn(boolean debugBuild) { return debugBuild || this != DEBUG; }
}

