package github.vhoom.zhijian.data.db;

import github.vhoom.zhijian.data.db.entity.usage.DailyUsageEntity;

import github.vhoom.zhijian.data.db.dao.PolicyDao;
import github.vhoom.zhijian.data.db.dao.RuntimeDao;
import github.vhoom.zhijian.data.db.dao.UsageDao;
import github.vhoom.zhijian.data.db.dao.UsageArchiveDao;
import github.vhoom.zhijian.data.db.entity.usage.UsageArchiveDayEntity;
import github.vhoom.zhijian.data.db.entity.usage.UsageArchiveAppEntity;
import github.vhoom.zhijian.data.db.dao.WordDao;
import github.vhoom.zhijian.data.db.entity.StorageMetaEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyAppEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyRuleEntity;
import github.vhoom.zhijian.data.db.entity.policy.PolicyWindowEntity;
import github.vhoom.zhijian.data.db.entity.runtime.InterceptionEntity;
import github.vhoom.zhijian.data.db.entity.runtime.PassGrantEntity;
import github.vhoom.zhijian.data.db.entity.runtime.RuntimeCheckpointEntity;
import github.vhoom.zhijian.data.db.entity.usage.UsageSessionEntity;
import github.vhoom.zhijian.data.db.entity.word.WordEntity;
import github.vhoom.zhijian.data.db.entity.word.WordMeaningEntity;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * 止间的 Room 数据库入口，集中声明规则配置、使用历史、每日归档、运行状态和词库五组 DAO。
 *
 * 维护日期：2026年11月14日
 */
@Database(entities = {PolicyEntity.class, PolicyRuleEntity.class, PolicyAppEntity.class, PolicyWindowEntity.class,
        StorageMetaEntity.class, UsageSessionEntity.class, DailyUsageEntity.class,
        InterceptionEntity.class, PassGrantEntity.class, RuntimeCheckpointEntity.class,
        WordEntity.class, WordMeaningEntity.class, UsageArchiveDayEntity.class, UsageArchiveAppEntity.class},
        version = 1, exportSchema = true)
public abstract class ZhijianDatabase extends RoomDatabase {
    /** 应用私有数据库文件名，测试通过独立文件名隔离数据。 */
    public static final String FILE_NAME = "zhijian.db";

    /** 已提交策略及其规则、应用和时段关联。 */
    public abstract PolicyDao policies();

    /** 使用会话、每日聚合和导出分页。 */
    public abstract UsageDao usage();

    /** 日期筛选所需的永久每日快照，与使用会话派生聚合分开。 */
    public abstract UsageArchiveDao usageArchive();

    /** 拦截记录、临时放行与运行检查点。 */
    public abstract RuntimeDao runtime();

    /** 单词摩擦使用的 SQLite 词条与释义。 */
    public abstract WordDao words();

    /**
     * 使用当前开发基线和 WAL 打开数据库；测试通过独立文件名隔离数据。
     *
     * @param context 访问系统服务、资源或持久化文件的上下文
     * @param name 数据库文件名；测试使用独立名称隔离数据
     */
    public static ZhijianDatabase open(Context context, String name) {
        return Room.databaseBuilder(context.getApplicationContext(), ZhijianDatabase.class, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build();
    }
}
