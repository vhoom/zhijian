package github.vhoom.zhijian.ui.component.rule;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.Switch;

import github.vhoom.zhijian.util.time.DecimalDuration;

/**
 * 可选开关与数字输入的通用绑定，保留原文并统一解析、程序回填和监听行为。
 * 范围与必填校验由调用方的保存入口完成。
 *
 * 维护日期：2026年11月13日
 */
public final class NumberRow {

    /**
     * 输入行的数值写入接口，由调用方决定草稿或存储目标。
     */
    public interface Writer {
        /**
         * 接收解析后的草稿值；空值或格式非法以零表示，保存入口校验原文。
         *
         * @param value 本次需要读取、转换或写入的值
         */
        void write(long value);
    }

    /** 关闭位约定是 0：AppPolicyStore.OFF 与 GlobalSettings.NO_QUOTA 都是 0。 */
    private static final long OFF = 0L;

    private final EditText field;
    private final Writer writer;
    private final long unitMillis;

    /** 可空：像「关掉即不生效」那种行才带开关，纯输入框的行不带。 */
    private Switch toggle;
    private Runnable onChange;

    /** 程序回填输入框时抑制文本监听，避免写入—回调互相触发。 */
    private boolean binding;
    /** 同一输入行只注册一组监听，重复 bind 不产生重复写入。 */
    private boolean bound;

    /**
     * 创建 NumberRow 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param field 承载当前数值输入的文本框
     * @param writer 把输入值写入草稿或持久化目标的回调
     */
    public NumberRow(EditText field, Writer writer) {
        this(field, writer, 1L);
    }

    /**
     * 创建允许一位小数的时长输入行。
     *
     * @param field 承载用户输入的文本框
     * @param unitMillis 一个界面单位对应的毫秒数
     * @param writer 接收精确毫秒值的写入器
     * @return 已配置为时长模式的输入行
     */
    public static NumberRow duration(EditText field, long unitMillis, Writer writer) {
        return new NumberRow(field, writer, unitMillis);
    }

    /**
     * 创建 NumberRow 实例，并初始化本次操作依赖或不可变字段。
     *
     * @param field 承载当前数值输入的文本框
     * @param writer 把输入值写入草稿或持久化目标的回调
     * @param unitMillis 一个界面时间单位对应的毫秒数
     */
    private NumberRow(EditText field, Writer writer, long unitMillis) {
        this.field = field;
        this.writer = writer;
        this.unitMillis = unitMillis;
    }

    /**
     * 为数字输入行绑定控制启用状态的开关。
     *
     * @param toggle 控制该输入项是否启用的开关
     */
    public NumberRow withToggle(Switch toggle) {
        this.toggle = toggle;
        return this;
    }

    /**
     * 每次改动之后要刷新的说明文案，策略页用它重算「当前生效模式」。
     *
     * @param onChange 输入或状态改变后刷新相关界面的回调
     */
    public NumberRow onChange(Runnable onChange) {
        this.onChange = onChange;
        return this;
    }

    /** 监听只能注册一次：重置时若再注册一遍，之后每次改动都会被重复写进存储。 */
    public void bind() {
        if (bound) return;
        bound = true;
        if (toggle != null) {
            toggle.setOnCheckedChangeListener((button, checked) -> {
                field.setEnabled(checked);
                if (binding) return;
                if (checked)
                    writeIfValid();
                else
                    writer.write(OFF);
                notifyChange();
            });
        }

        field.addTextChangedListener(new TextWatcher() {
            /**
             * 接收文本修改前通知；实际变更由修改完成回调处理。
             *
             * @param s 编辑器当前文本
             * @param start 变化起点或区间开始值
             * @param count 处理的条目数或本次变更的字符数量
             * @param after 变更后的文本数量或导出起始行号
             */
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            /**
             * 接收文本修改通知，具体保存由编辑完成回调处理。
             *
             * @param s 编辑器当前文本
             * @param start 变化起点或区间开始值
             * @param before 变更前的文本数量或待比较的旧记录
             * @param count 处理的条目数或本次变更的字符数量
             */
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            /**
             * 文本修改完成后更新草稿或筛选结果。
             *
             * @param s 编辑器当前文本
             */
            @Override
            public void afterTextChanged(Editable s) {
                if (binding || (toggle != null && !toggle.isChecked()))
                    return;
                writeIfValid();
                notifyChange();
            }
        });
    }

    /**
     * 纯输入框的行：回填合并好的当前值，输入框始终可用。
     *
     * @param value 本次需要读取、转换或写入的值
     */
    public void apply(long value) {
        applyText(unitMillis == 1L
                ? String.valueOf(value) : DecimalDuration.format(value, unitMillis));
    }

    /**
     * 原样恢复未完成输入，抑制文本监听，不把系统恢复误认为用户编辑。
     * @param text 已保存的原始输入文本
     */
    public void applyText(String text) {
        binding = true;
        try {
            field.setText(text);
            field.setEnabled(toggle == null || toggle.isChecked());
        } finally {
            binding = false;
        }
    }

    /** 程序性清空输入框（切类型、关开关后的复位），不触发写入。 */
    public void clear() {
        binding = true;
        try {
            field.setText("");
        } finally {
            binding = false;
        }
    }

    /**
     * 用户清空输入框等同于撤销自定义：写入 OFF，由存储层删掉这条记录，
     * 读取方自然落回默认值。不会出现「界面上是默认提示、存储里却是旧值」的矛盾。
     */
    private void writeIfValid() {
        long value = parse();
        writer.write(value > 0 ? value : OFF);
    }

    /**
     * 解析当前数字输入；非法或空输入交由调用方处理。
     */
    private long parse() {
        if (unitMillis != 1L) {
            return DecimalDuration.parseMillis(field.getText().toString().trim(), unitMillis);
        }
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 在当前值变更后通知界面刷新关联说明。
     */
    private void notifyChange() {
        if (onChange != null)
            onChange.run();
    }
}
