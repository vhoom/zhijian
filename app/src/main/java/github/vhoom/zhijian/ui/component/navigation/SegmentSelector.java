package github.vhoom.zhijian.ui.component.navigation;

import android.content.Context;
import android.widget.TextView;

import github.vhoom.zhijian.R;

/**
 * 横向互斥分段控件的选中态。布局里只声明默认样式，选中用的底色和文字色由这里切换，
 * 强度档位和配色档位共用一套表现，不会各画各的。
 *
 * 维护日期：2026年11月08日
 */
public final class SegmentSelector {

    private final Context context;
    private final TextView[] segments;

    /**
     * 创建 SegmentSelector 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param segments 参与分段选择的标题控件数组
     */
    public SegmentSelector(Context context, TextView... segments) {
        this.context = context;
        this.segments = segments;
    }

    /** 把段本身交出去，调用方好给每一段挂自己的点击逻辑。 */
    public TextView[] segments() {
        return segments;
    }

    /**
     * 返回指定位置的分段标题控件。
     *
     * @param index 从零开始的条目或页面索引
     */
    public TextView segmentAt(int index) {
        return segments[index];
    }

    /**
     * 越界当作没选中，全段都回到默认态：存储里的档位可能被新版本删掉。
     *
     * @param index 从零开始的条目或页面索引
     */
    public void select(int index) {
        select(index, R.drawable.bg_pill_primary, R.color.on_primary);
    }

    /**
     * 指定选中态的底色与文字色，强度档位这类需要语义着色的场景用。
     *
     * @param index 从零开始的条目或页面索引
     * @param activeBgRes 选中项背景对应的资源 ID
     * @param activeTextColorRes 选中项文字颜色对应的资源 ID
     */
    public void select(int index, int activeBgRes, int activeTextColorRes) {
        for (int i = 0; i < segments.length; i++) {
            boolean active = i == index;
            TextView segment = segments[i];
            segment.setTextColor(context.getColor(active ? activeTextColorRes : R.color.mute));
            segment.setBackground(active ? context.getDrawable(activeBgRes) : null);
        }
    }
}
