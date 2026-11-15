package github.vhoom.zhijian.ui.component.layout;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * 按背景轮廓裁切子视图与点击反馈，兼容尚不支持 XML 裁切属性的系统。
 *
 * 维护日期：2026年11月08日
 */
public final class RoundedLinearLayout extends LinearLayout {

    /**
     * 创建使用默认样式的圆角容器。
     *
     * @param context 读取主题与布局资源的上下文
     */
    public RoundedLinearLayout(Context context) {
        this(context, null);
    }

    /**
     * 从 XML 创建圆角容器。
     *
     * @param context 读取主题与布局资源的上下文
     * @param attrs XML 属性；通过代码创建时可以为 null
     */
    public RoundedLinearLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * 创建容器并启用 Android 10 也支持的背景轮廓裁切。
     *
     * @param context 读取主题与布局资源的上下文
     * @param attrs XML 属性；通过代码创建时可以为 null
     * @param defStyleAttr 默认样式属性；不需要时传入 0
     */
    public RoundedLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipToOutline(true);
    }
}
