package github.vhoom.zhijian.data.store.apps;

import java.io.IOException;
import java.util.List;

import github.vhoom.zhijian.data.model.AppInfo;

/**
 * 应用清单快照的持久化边界，供文件实现和本地回归夹具共用。
 *
 * 维护日期：2026年11月10日
 */
public interface AppCatalogCache {
    /**
     * 读取最后一次成功保存的清单，空清单也是有效快照。
     * @return 尚未保存时返回 null；格式错误或读取失败通过异常报告
     * @throws IOException 本地快照无法完整读取
     */
    List<AppInfo> read() throws IOException;

    /**
     * 完整保存新快照，失败时保留上一份已提交内容。
     * @param apps 包含包名和显示名称的完整清单
     * @throws IOException 新快照未能成功提交
     */
    void write(List<AppInfo> apps) throws IOException;
}
