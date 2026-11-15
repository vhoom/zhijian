package github.vhoom.zhijian.data.source;

import android.content.Context;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.store.poem.DailyPoemStore;

import java.security.SecureRandom;
import java.time.LocalDate;

/**
 * 每日诗句：按手机本地日期随机选取并缓存正文，当天刷新和重启保持一致。
 * 次日有其他正文可选时避开上次诗句；Debug 预览通过独立随机入口抽样。
 *
 * 维护日期：2026年11月11日
 */
public final class DailyPoem {

    /** 共享系统随机源，避免每次抽取时重建随机数生成器。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private DailyPoem() {
    }

    /**
     * 返回当天固定的诗句，首次读取或日期变化时随机选择并保存正文。
     *
     * @param context 用于读取本地诗句资源的上下文
     * @return 当天诗句正文；没有缓存且资源无可用正文时返回空字符串
     */
    public static String of(Context context) {
        return DailyPoemStore.getOrCreate(context, LocalDate.now().toString(), previous ->
                select(context.getResources().getStringArray(R.array.daily_poems), previous));
    }

    /**
     * 独立随机抽取正文，供 Debug 预览随时切换样例。
     * @param context 用于读取本地诗句资源的上下文
     * @return 随机正文，允许重复；资源无可用正文时返回空字符串
     */
    public static String random(Context context) {
        return select(context.getResources().getStringArray(R.array.daily_poems), "");
    }

    /**
     * 从有效资源条目中等概率选取正文，优先避开上次缓存的诗句。
     * @param poems 本地资源中的全部诗句条目
     * @param previous 上次正文；独立随机抽样时传入空字符串
     * @return 选中的正文；仅有上次正文时复用，全部为空时返回空字符串
     */
    private static String select(String[] poems, String previous) {
        int candidates = 0;
        String fallback = "";
        for (String poem : poems) {
            if (poem == null || poem.isEmpty()) continue;
            fallback = poem;
            if (!poem.equals(previous)) candidates++;
        }
        if (candidates == 0) return fallback;

        int index = RANDOM.nextInt(candidates);
        for (String poem : poems) {
            if (poem == null || poem.isEmpty() || poem.equals(previous)) continue;
            if (index-- == 0) return poem;
        }
        return fallback;
    }
}
