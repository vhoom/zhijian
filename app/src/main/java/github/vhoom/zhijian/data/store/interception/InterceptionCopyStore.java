package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 为每次新拦截分配持久递增的提示变体序号；主句由独立会话轮换存储选择。
 *
 * 维护日期：2026年11月13日
 */
public final class InterceptionCopyStore {
    private static final String FILE_NAME = "interception_copy";
    private static final String NEXT_VARIANT = "next_variant";
    private static final Object LOCK = new Object();

    /** 工具类不创建实例。 */
    private InterceptionCopyStore() { }

    /**
     * 返回本次触发的序号，并立即在内存中推进下一次序号。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     */
    public static int next(Context context) {
        synchronized (LOCK) {
            SharedPreferences preferences = context.getApplicationContext()
                    .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
            int current = Math.max(0, preferences.getInt(NEXT_VARIANT, 0));
            int next = current == Integer.MAX_VALUE ? 0 : current + 1;
            preferences.edit().putInt(NEXT_VARIANT, next).apply();
            return current;
        }
    }
}
