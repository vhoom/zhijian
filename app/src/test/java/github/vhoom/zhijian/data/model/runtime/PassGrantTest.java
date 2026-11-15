package github.vhoom.zhijian.data.model.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 覆盖 PassGrant 的边界输入、状态变化与回归行为。
 *
 * 维护日期：2026年11月08日
 */
public class PassGrantTest {
    /** 分组授权覆盖当前生效成员，不覆盖独立配置或其他分组。 */
    @Test public void groupGrantCoversOnlyEffectiveMembers() {
        PassGrant grant = new PassGrant("a", "group:1", 1L, "boot", 1_000L, 60_000L, 61_000L);
        org.junit.Assert.assertTrue(grant.covers("b", "group:1"));
        org.junit.Assert.assertFalse(grant.covers("b", "app:b"));
        org.junit.Assert.assertFalse(grant.covers("b", "group:2"));
    }
    /** 独立应用与全局来源的授权仍只覆盖本次应用。 */
    @Test public void applicationAndGlobalGrantsDoNotSpreadToOtherApps() {
        org.junit.Assert.assertTrue(grant().covers("app", "global"));
        org.junit.Assert.assertFalse(grant().covers("other", "global"));
        PassGrant app = new PassGrant("a", "app:a", 1L, "boot", 1_000L, 60_000L, 61_000L);
        org.junit.Assert.assertFalse(app.covers("b", "app:a"));
    }
    /**
     * 读取或还原对应的临时放行记录。
     */
    private PassGrant grant() {
        return new PassGrant("app", "global", 1, "boot-A", 1_000_000, 300_000, 400_000);
    }

    /**
     * 验证同次系统启动内修改墙钟不会改变放行剩余时间。
     */
    @Test
    public void sameBootIgnoresWallClockChanges() {
        assertEquals(120_000, grant().remainingMs("boot-A", 1, 280_000));
        assertEquals(0, grant().remainingMs("boot-A", 1, 400_001));
    }

    /**
     * 验证离开目标应用不会重新开始放行倒计时。
     */
    @Test
    public void leavingAppDoesNotRestartCountdown() {
        PassGrant grant = grant();
        assertEquals(300_000, grant.remainingMs("boot-A", 1_000_000, 100_000));
        assertEquals(60_000, grant.remainingMs("boot-A", 1_240_000, 340_000));
    }

    /**
     * 验证重启后旧放行失效，修改墙钟无法恢复或延长期限。
     */
    @Test
    public void rebootRevokesGrantRegardlessOfWallClock() {
        assertEquals(0, grant().remainingMs("boot-B", 1_240_000, 10_000));
        assertEquals(0, grant().remainingMs("boot-B", 1_500_000, 10_000));
        assertEquals(0, grant().remainingMs("boot-B", 999_000, 10_000));
    }

    /**
     * 验证异常单调时钟不能延长临时放行。
     */
    @Test
    public void impossibleElapsedClockCannotExtendGrant() {
        assertEquals(0, grant().remainingMs("boot-A", 1_000_000, 50_000));
    }
}
