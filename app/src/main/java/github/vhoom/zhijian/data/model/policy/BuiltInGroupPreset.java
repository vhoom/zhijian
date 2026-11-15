package github.vhoom.zhijian.data.model.policy;

import github.vhoom.zhijian.R;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 默认组目录与规则工厂；稳定身份独立于显示名称，不读取系统或存储。
 *
 * 维护日期：2026年11月08日
 */
public enum BuiltInGroupPreset {
    VIDEO(0x464C4F575356L, R.string.rule_default_short_video_group,
            "built_in_short_video_dismissed", 15, 10,
            Arrays.asList(
                    "com.tencent.weishi",
                    "com.ss.android.article.lite",
                    "com.kuaishou.nebula",
                    "com.baidu.searchbox.lite",
                    "com.ss.android.yumme.video",
                    "com.phoenix.read",
                    "com.ss.android.ugc.live",
                    "com.kylin.read",
                    "com.dz.hmjc",
                    "com.baidu.haokan",
                    "com.tencent.kairos",
                    "com.ss.android.ugc.aweme.lite",
                    "com.smile.gifmaker",
                    "com.ss.android.ugc.aweme",
                    "tv.danmaku.bili",
                    "com.xingin.xhs",
                    "com.youku.phone",
                    "com.ss.android.article.video",
                    "com.qiyi.video",
                    "com.miui.video",
                    "com.tencent.qqlive",
                    "com.hunantv.imgo.activity",
                    "com.sohu.sohuvideo",
                    "com.qiyi.video.lite",
                    "com.cmcc.cmvideo",
                    "com.baidu.searchbox.tomas",
                    "com.baidu.searchbox"),
            ban("short-video-night", 23 * 60, 6 * 60 + 30),
            reminder("video-morning", 6 * 60 + 30, 8 * 60, 5, 20)),
    MEDIA(0x464C4F574D44L, R.string.rule_default_media_group,
            "built_in_media_dismissed", 20, 10,
            Arrays.asList(
                    "com.wondertek.paper",
                    "com.sina.news",
                    "com.ifeng.news2",
                    "com.sohu.newsclient",
                    "com.cns.mc.activity",
                    "com.netease.newsreader.activity",
                    "com.newsweekly.livepi",
                    "com.sina.weibo",
                    "com.jiupainews.app",
                    "com.news.shangyounews",
                    "com.ifext.news",
                    "com.shenyuan.militarynews",
                    "com.jxmfkj.www.company.young",
                    "com.sohu.mobile",
                    "com.douban.frodo",
                    "com.peopledailychina.activity",
                    "com.ifeng.newvideo",
                    "com.ss.android.article.news",
                    "cn.cntvnews",
                    "com.tencent.news",
                    "com.zhihu.android",
                    "com.tencent.mm",
                    "com.tencent.mobileqq",
                    "com.baidu.tieba",
                    "com.jinrikd.news"),
            ban("media-night", 30, 6 * 60 + 30),
            reminder("media-morning", 7 * 60, 8 * 60, 10, 10)),
    READING(0x464C4F575244L, R.string.rule_default_reading_group,
            "built_in_reading_dismissed", 25, 30,
            Arrays.asList(
                    "com.manga.manhuaren",
                    "com.qiyi.video.reader",
                    "com.dragon.read",
                    "com.shaoniandream",
                    "com.qidian.QDReader",
                    "com.kmxs.reader",
                    "com.xs.fm",
                    "com.dt.maoyu",
                    "com.shuqi.controller",
                    "com.faster.dsyd",
                    "com.cyclotron.in",
                    "cn.youth.news",
                    "com.qq.reader",
                    "top.cloudfun.read",
                    "com.duokan.reader",
                    "com.ushaqi.zhuishushenqi.adfree",
                    "com.jjwxc.reader",
                    "com.kuangxiangciweimao.novel",
                    "com.avgorange.hudong",
                    "com.zhihu.vip.android",
                    "com.ushaqi.zhuishushenqi",
                    "com.woodleaves.read",
                    "com.faloo.BookReader4Android",
                    "com.douban.book.reader",
                    "com.qingcheng.reader",
                    "com.qbmf.reader",
                    "com.xx.reader",
                    "com.zhangyue.read.iReader",
                    "com.bilibili.comic",
                    "com.yuewen.cooperate.reader.free",
                    "com.ophone.reader.ui",
                    "com.xmtj.novel",
                    "com.wbxm.icartoon",
                    "com.eggflower.read",
                    "com.tencent.weread"),
            ban("reading-morning", 7 * 60, 8 * 60),
            reminder("reading-night", 0, 6 * 60 + 30, 5, 10),
            reminder("reading-noon", 12 * 60, 13 * 60, 5, 10));

    /** 三组共享的安装查询目录，保持目录顺序且不重复查询。 */
    public static final List<String> ALL_PACKAGES = collectPackages();
    /** 各默认组独立的稳定身份。 */
    public final long groupId;
    /** 显示名称资源，由界面或存储资源适配器解析。 */
    public final int nameRes;
    /** 用户删除默认组后的持久标记。 */
    public final String dismissedKey;
    /** 固定包名只用于匹配，实际成员由当前用户的安装状态决定。 */
    public final List<String> packages;
    private final int useMinutes, restMinutes;
    private final List<RuleWindow> windows;

    /**
     * @param groupId 稳定组身份 @param nameRes 名称资源 @param dismissedKey 删除标记
     * @param useMinutes 全天连续使用分钟 @param restMinutes 全天休息分钟
     * @param packages 包名目录 @param windows 每天的时段模板，仅工厂持有
     */
    BuiltInGroupPreset(long groupId, int nameRes, String dismissedKey, int useMinutes,
            int restMinutes, List<String> packages, RuleWindow... windows) {
        this.groupId = groupId;
        this.nameRes = nameRes;
        this.dismissedKey = dismissedKey;
        this.useMinutes = useMinutes;
        this.restMinutes = restMinutes;
        this.packages = Collections.unmodifiableList(new ArrayList<>(packages));
        this.windows = Arrays.asList(windows);
    }

    /** @param groupId 稳定身份 @return 对应预设；普通用户组返回 null */
    public static BuiltInGroupPreset find(long groupId) {
        for (BuiltInGroupPreset preset : values()) if (preset.groupId == groupId) return preset;
        return null;
    }

    /** @param groupId 稳定身份 @return 是否为默认组 */
    public static boolean isBuiltIn(long groupId) { return find(groupId) != null; }

    /** @param installedPackages 当前用户的安装清单 @return 按目录顺序去重后的独立成员列表 */
    public List<String> members(Collection<String> installedPackages) {
        Set<String> installed = new HashSet<>(installedPackages);
        List<String> members = new ArrayList<>();
        for (String packageName : packages) if (installed.contains(packageName)) members.add(packageName);
        return members;
    }

    /**
     * 首次创建默认关闭的组，七天独立复制参数；成员刷新不重新套用默认值。
     * @param name 资源显示名称 @param installedPackages 当前安装清单
     * @param passDurationMillis 默认放行毫秒 @return 不共享可变参数的组草稿
     */
    public RuleGroup create(String name, Collection<String> installedPackages, long passDurationMillis) {
        RuleGroup group = new RuleGroup(groupId, name);
        group.enabled = false;
        group.apps.addAll(members(installedPackages));
        group.types.add(RuleType.CONTINUOUS);
        group.continuousLimitMillis = useMinutes * 60_000L;
        group.interruptionPauseMillis = restMinutes * 60_000L;
        group.passDurationMillis = passDurationMillis;
        for (RuleWindow window : windows) {
            group.types.add(window.type);
            group.windows.add(window.copy());
        }
        RuleDayParameters defaults = RuleDayParameterAdapter.capture(group);
        for (int day = 1; day <= 7; day++) group.weekdayRules.put(day, defaults.copy());
        return group;
    }

    /** @return 合并后的不可变包名目录 */
    private static List<String> collectPackages() {
        Set<String> packages = new LinkedHashSet<>();
        for (BuiltInGroupPreset preset : values()) packages.addAll(preset.packages);
        return Collections.unmodifiableList(new ArrayList<>(packages));
    }

    /** @param id 时段身份 @param start 起始分钟 @param end 结束分钟 @return 禁用时段模板 */
    private static RuleWindow ban(String id, int start, int end) {
        return new RuleWindow(id, RuleType.WINDOW_BAN, start, end, 0L);
    }

    /** @param id 时段身份 @param start 起始分钟 @param end 结束分钟
     * @param use 使用分钟 @param rest 休息分钟 @return 连续提醒时段模板 */
    private static RuleWindow reminder(String id, int start, int end, int use, int rest) {
        RuleWindow window = new RuleWindow(id, RuleType.WINDOW_CONTINUOUS, start, end, use * 60_000L);
        window.interruptionPauseMillis = rest * 60_000L;
        return window;
    }
}
