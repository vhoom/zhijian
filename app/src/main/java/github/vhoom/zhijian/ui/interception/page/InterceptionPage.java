package github.vhoom.zhijian.ui.interception.page;

import android.os.Bundle;
import android.view.View;

/**
 * 页面统一契约；实现自行管理控件和交互，只向协调层报告用户事件。
 *
 * 维护日期：2026年11月10日
 */
public interface InterceptionPage {
    /** 返回由本页面持有的根视图。 */
    View view();
    /** 开始显示并恢复本页计时回调。 */
    void render();
    /**
     * 接收状态机已提交的挑战完成状态，在现有视图上显示结果。
     * @return 已原页处理时为 true，不支持原页完成时由页面目录重新装配
     */
    default boolean completeChallenge() { return false; }
    /** 停止可见期间的工作，长按必须立即取消。 */
    void pause();
    /** 返回页面是否正在处理横向手势，外壳的调试翻页必须让出该手势。 */
    default boolean isHandlingHorizontalGesture() { return false; }
    /**
     * 更新独立休息时钟，本页不得因归零自动退出。
     * @param remainingMs 剩余休息毫秒数，非正值表示结束
     */
    void updateRest(long remainingMs);
    /**
     * 保存本页交互进度，不保存模式或运行记录。
     * @param state 用于重建的页面状态容器
     */
    void save(Bundle state);
}
