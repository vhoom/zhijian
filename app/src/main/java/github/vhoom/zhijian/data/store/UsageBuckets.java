package github.vhoom.zhijian.data.store;

import github.vhoom.zhijian.data.model.usage.UsageDay;
import github.vhoom.zhijian.data.model.usage.UsageSession;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 日界线按记录自身时区计算，包含 23/25 小时的夏令时日期。
 *
 * 维护日期：2026年11月08日
 */
public final class UsageBuckets {
    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private UsageBuckets() {}

    /**
     * 按本地日界线拆分用量，并保持总毫秒数和打开次数不变。
     *
     * @param session 当前使用会话明细
     */
    public static List<UsageDay> split(UsageSession session) {
        List<UsageDay> result = new ArrayList<>();
        ZoneId zone = ZoneId.of(session.zoneId);
        LocalDate day = Instant.ofEpochMilli(session.startedAtMs).atZone(zone).toLocalDate();
        long cursor = session.startedAtMs;
        long allocated = 0;
        boolean first = true;
        do {
            long nextDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long end = Math.min(session.endedAtMs, nextDay);
            long cumulative = session.foregroundMs;
            if (end < session.endedAtMs) {
                // 使用整数比值，分段舍入的余数留给最后一天，不丢失毫秒。
                cumulative = BigInteger.valueOf(session.foregroundMs)
                        .multiply(BigInteger.valueOf(end - session.startedAtMs))
                        .divide(BigInteger.valueOf(session.endedAtMs - session.startedAtMs)).longValue();
            }
            result.add(new UsageDay(day.toString(), session.zoneId, session.packageName,
                    cumulative - allocated, first && session.countsAsOpen ? 1 : 0));
            allocated = cumulative;
            first = false;
            cursor = end;
            day = day.plusDays(1);
        } while (cursor < session.endedAtMs);
        return result;
    }
}
