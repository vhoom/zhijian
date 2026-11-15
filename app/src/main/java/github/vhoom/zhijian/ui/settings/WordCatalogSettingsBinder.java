package github.vhoom.zhijian.ui.settings;

import android.app.Activity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * 单词配置的词库选择适配器；完整性检查属于启动流程，选择界面只展示名称。
 *
 * 维护日期：2026年11月08日
 */
public final class WordCatalogSettingsBinder {
    private final Spinner selection;

    /**
     * 绑定词库名称与本模式的选择草稿，不修改已保存的设置。
     * @param activity 参数弹窗所属页面
     * @param content 当前单词配置视图
     * @param current 本模式已保存的词库
     */
    public WordCatalogSettingsBinder(Activity activity, View content, BuiltInWordCatalog current) {
        selection = content.findViewById(R.id.field_word_catalog);
        List<String> names = new ArrayList<>();
        int position = 0;
        BuiltInWordCatalog[] catalogs = BuiltInWordCatalog.values();
        for (int i = 0; i < catalogs.length; i++) {
            names.add(activity.getString(catalogs[i].nameRes));
            if (catalogs[i] == current) position = i;
        }
        selection.setAdapter(new ArrayAdapter<>(activity, R.layout.item_word_catalog_choice, names));
        selection.setSelection(position);
    }

    /** 返回当前词库草稿，供调用方校验参数成功后统一保存。 */
    public BuiltInWordCatalog selectedCatalog() {
        return BuiltInWordCatalog.values()[selection.getSelectedItemPosition()];
    }

    /**
     * 将词库草稿变化交给表单校验，不读取或修改其他输入。
     * @param changed 选择词库后重新校验题量和摘要的回调
     */
    public void onSelectionChanged(Runnable changed) {
        selection.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            /**
             * 选择词库后通知调用方。
             * @param parent 当前选择控件
             * @param view 当前选项视图
             * @param position 选项索引
             * @param id 选项稳定标识
             */
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                changed.run();
            }

            /**
             * 未选择时不提交草稿，词库控件始终具有默认选项。
             * @param parent 当前选择控件
             */
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }
}
