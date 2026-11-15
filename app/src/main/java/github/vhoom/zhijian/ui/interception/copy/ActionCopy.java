package github.vhoom.zhijian.ui.interception.copy;

import android.content.Context;
import github.vhoom.zhijian.ui.interception.copy.InterceptionCopyCatalog.Pool;

/**
 * 操作去向的文案适配器；临时放行与继续使用读取同一动态文案库。
 *
 * 维护日期：2026年11月10日
 */
public final class ActionCopy {
    /** 各池表达的意图；盲盒文字不能透露预选结果。 */
    public enum Route { REST, CONTINUE, PASS, CHALLENGE, BLIND_BOX }

    /** 静态文案适配器不需要实例。 */
    private ActionCopy() { }

    /** @param context 资源上下文 @param route 实际去向 @param variant 稳定文案种子 @return 动态引导语 */
    public static String text(Context context, Route route, int variant) {
        Pool pool;
        switch (route) {
            case CONTINUE:
            case PASS: pool = Pool.CONTINUE_ACTIONS; break;
            case CHALLENGE: pool = Pool.CHALLENGE_ACTIONS; break;
            case BLIND_BOX: pool = Pool.BLIND_BOX_ACTIONS; break;
            default: pool = Pool.EXIT_ACTIONS; break;
        }
        InterceptionCopyCatalog catalog = new InterceptionCopyCatalog(context);
        return catalog.value(pool, Math.floorMod(variant, catalog.count(pool)));
    }
}
