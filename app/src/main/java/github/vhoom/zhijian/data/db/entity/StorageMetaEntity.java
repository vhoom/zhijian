package github.vhoom.zhijian.data.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 存储内部键值元数据，保存默认组删除及应用移除记录。
 *
 * 维护日期：2026年11月08日
 */
@Entity(tableName = "storage_meta")
public class StorageMetaEntity {
    @PrimaryKey @NonNull public String key = "";
    @NonNull public String value = "";

    /**
     * 创建 StorageMetaEntity 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param key 稳定标识或待读取的持久化设置键
     * @param value 本次需要读取、转换或写入的值
     */
    public StorageMetaEntity(String key, String value) {
        this.key = key;
        this.value = value;
    }
}
