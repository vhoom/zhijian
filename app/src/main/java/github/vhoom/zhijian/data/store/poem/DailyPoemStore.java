package github.vhoom.zhijian.data.store.poem;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.function.Function;

/**
 * 每日诗句缓存：保存本地日期及当天正文，页面刷新和进程重建时复用。
 * 使用独立偏好文件，日期与正文同时更新，落盘沿用异步 apply。
 *
 * 维护日期：2026年11月11日
 */
public final class DailyPoemStore {
    private static final String FILE = "daily_poem";
    private static final String KEY_DATE = "date";
    private static final String KEY_TEXT = "text";

    /** 工具类通过静态入口读取和更新每日缓存。 */
    private DailyPoemStore() { }

    /**
     * 复用当天正文，缓存缺失或跨日时由选择策略生成并保存新正文。
     * @param context 用于打开应用私有偏好文件的上下文
     * @param date 手机当前本地日期，采用 yyyy-MM-dd 格式
     * @param select 接收上次缓存正文并返回新正文的选择策略
     * @return 当天正文；选择策略没有可用正文时返回空字符串
     */
    public static synchronized String getOrCreate(Context context, String date,
            Function<String, String> select) {
        SharedPreferences preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Map<String, ?> snapshot = preferences.getAll();
        Object savedText = snapshot.get(KEY_TEXT);
        String previous = savedText instanceof String ? (String) savedText : "";
        if (date.equals(snapshot.get(KEY_DATE)) && !previous.isEmpty()) return previous;

        String selected = select.apply(previous);
        if (selected == null || selected.isEmpty()) return "";
        preferences.edit().putString(KEY_DATE, date).putString(KEY_TEXT, selected).apply();
        return selected;
    }
}
