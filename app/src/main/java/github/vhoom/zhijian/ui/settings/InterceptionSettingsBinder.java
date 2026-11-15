package github.vhoom.zhijian.ui.settings;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import github.vhoom.zhijian.R;
import github.vhoom.zhijian.ui.component.input.InputError;
import github.vhoom.zhijian.util.format.UnsignedInteger;
import github.vhoom.zhijian.ui.component.dialog.AppDialogs;
import github.vhoom.zhijian.ui.component.settings.SettingsBottomSheet;
import github.vhoom.zhijian.ui.component.settings.SettingsNumberStepper;
import github.vhoom.zhijian.ui.component.settings.SettingsValidation;
import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;
import github.vhoom.zhijian.data.model.interception.InterceptionMode;
import github.vhoom.zhijian.data.model.settings.SudokuDifficulty;
import github.vhoom.zhijian.data.model.settings.SecondsRange;
import github.vhoom.zhijian.data.model.settings.InterceptionOptions;
import github.vhoom.zhijian.data.model.settings.InteractionSettings;
import github.vhoom.zhijian.data.model.settings.WordChallengeSettings;
import github.vhoom.zhijian.data.model.word.BuiltInWordCatalog;
import github.vhoom.zhijian.data.store.ZhijianStorage;
import github.vhoom.zhijian.data.store.settings.GlobalSettings;
import github.vhoom.zhijian.data.store.settings.InteractionSettingsStore;
import github.vhoom.zhijian.util.config.AppConfig;
import github.vhoom.zhijian.util.time.DecimalDuration;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 模式设置绑定器：编辑流程开关与修心挑战参数，页面渲染层只读取快照。
 *
 * 维护日期：2026年11月14日
 */
public final class InterceptionSettingsBinder {
    private final Activity activity;
    private final GlobalSettings settings;
    private final InteractionSettingsStore interactions;
    private final ViewGroup settingsContent;
    private final View obstacleOptions;
    private Boolean obstaclesExpanded;
    private Dialog settingsDialog;

    /** 接收已校验长按区间。 */
    private interface RangeSaver {
        /**
         * 保存合法区间。
         * @param range 已校验长按区间
         */
        void save(SecondsRange range);
    }

    /**
     * 绑定模式选项、修心挑战参数与全天规则恢复时间。
     * @param activity 设置视图及弹窗所在页面
     * @param settings 通用每日恢复配置
     */
    public InterceptionSettingsBinder(Activity activity, GlobalSettings settings) {
        this.activity = activity;
        this.settings = settings;
        interactions = new InteractionSettingsStore(activity, new AppConfig(activity));
        settingsContent = activity.findViewById(R.id.settings_content);
        obstacleOptions = activity.findViewById(R.id.settings_repair_hold_group);
        activity.findViewById(R.id.row_daily_reset).setOnClickListener(v -> chooseDailyResetTime());
        activity.findViewById(R.id.row_hold_range).setOnClickListener(v -> chooseSecondsRange(
                R.string.settings_challenge_hold_title, interactions.holdRange(InterceptionMode.REPAIR),
                range -> interactions.setHoldRange(InterceptionMode.REPAIR, range)));
        activity.findViewById(R.id.row_word_challenge).setOnClickListener(v -> chooseWordChallengeSettings(InterceptionMode.REPAIR));
        activity.findViewById(R.id.row_sudoku_challenge).setOnClickListener(v -> chooseSudokuDifficulty());
        renderInterceptionSettings();
    }

    /** 按当前设置显示模式开关；隐藏阻碍配置不会清空其值。 */
    private void renderModeOptions() {
        InterceptionOptions wuwei = interactions.readOptions(InterceptionMode.WUWEI);
        InteractionSettings repairSettings = interactions.read(InterceptionMode.REPAIR);
        InterceptionOptions repair = repairSettings.options;
        bindOptionSwitch(R.id.switch_wuwei_temporary_pass, R.string.settings_wuwei_mode,
                R.string.settings_temporary_pass_title, wuwei.temporaryPassEnabled,
                enabled -> interactions.setTemporaryPassEnabled(InterceptionMode.WUWEI, enabled));
        bindOptionSwitch(R.id.switch_repair_temporary_pass, R.string.settings_repair_mode,
                R.string.settings_temporary_pass_title, repair.temporaryPassEnabled,
                enabled -> interactions.setTemporaryPassEnabled(InterceptionMode.REPAIR, enabled));
        bindOptionSwitch(R.id.switch_wuwei_dynamic_buttons, R.string.settings_wuwei_mode,
                R.string.settings_dynamic_buttons_title, wuwei.dynamicButtonsEnabled,
                interactions::setDynamicButtonsEnabled);
        bindOptionSwitch(R.id.switch_repair_confirmation, R.string.settings_repair_mode,
                R.string.settings_confirmation_title, repair.confirmationEnabled,
                interactions::setConfirmationEnabled);
        bindOptionSwitch(R.id.switch_repair_obstacle_pages, R.string.settings_repair_mode,
                R.string.settings_obstacle_pages_title, repair.obstaclePagesEnabled,
                enabled -> {
                    interactions.setObstaclePagesEnabled(enabled);
                    showObstacleOptions(enabled, true);
                });
        showObstacleOptions(repair.obstaclePagesEnabled, false);
        bindRepairChallengeSwitch(R.id.switch_repair_hold, R.string.settings_challenge_hold_title,
                InterceptionChallenge.BUTTON_HOLD, repairSettings);
        bindRepairChallengeSwitch(R.id.switch_repair_word, R.string.settings_challenge_word_title,
                InterceptionChallenge.WORD_TRANSLATION, repairSettings);
        bindRepairChallengeSwitch(R.id.switch_repair_sudoku, R.string.settings_challenge_sudoku_title,
                InterceptionChallenge.SUDOKU, repairSettings);
    }

    /**
     * 同步子项显隐及周围布局，只为用户切换播放展开收起动画。
     * @param expanded 是否展开再次确认、按压和单词配置
     * @param animate 本次是否来自用户开关操作
     */
    private void showObstacleOptions(boolean expanded, boolean animate) {
        if (obstaclesExpanded != null && obstaclesExpanded == expanded) return;
        obstaclesExpanded = expanded;
        if (animate && settingsContent.isLaidOut() && ValueAnimator.areAnimatorsEnabled()) {
            TransitionManager.beginDelayedTransition(settingsContent, new TransitionSet()
                    .setOrdering(TransitionSet.ORDERING_TOGETHER)
                    .addTransition(new ChangeBounds())
                    .addTransition(new Fade().addTarget(obstacleOptions))
                    .setDuration(220L)
                    .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)));
        } else {
            TransitionManager.endTransitions(settingsContent);
        }
        obstacleOptions.setImportantForAccessibility(expanded
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        obstacleOptions.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    /**
     * 接线一个模式选项；刷新选中状态不重复保存。
     * @param viewId 开关控件
     * @param modeRes 模式名称资源
     * @param titleRes 选项名称资源
     * @param checked 已提交开关值
     * @param saver 用户改变选项时的保存操作
     */
    private void bindOptionSwitch(int viewId, int modeRes, int titleRes, boolean checked, Consumer<Boolean> saver) {
        Switch toggle = activity.findViewById(viewId);
        toggle.setOnCheckedChangeListener(null);
        toggle.setContentDescription(activity.getString(R.string.settings_mode_option_toggle,
                activity.getString(modeRes), activity.getString(titleRes)));
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((view, enabled) -> {
            saver.accept(enabled);
            renderInterceptionSettings();
        });
    }

    /**
     * 绑定独立挑战开关；阻碍页面开启时锁定最后一个选项，参数入口保持可用。
     * @param viewId 挑战开关控件
     * @param titleRes 挑战名称资源
     * @param challenge 开关对应的挑战
     * @param repair 同次读取的修心设置快照
     */
    private void bindRepairChallengeSwitch(int viewId, int titleRes, InterceptionChallenge challenge,
            InteractionSettings repair) {
        boolean checked = repair.enabledChallenges.contains(challenge);
        boolean locked = repair.options.obstaclePagesEnabled && checked && repair.enabledChallenges.size() == 1;
        bindOptionSwitch(viewId, R.string.settings_repair_mode, titleRes, checked,
                enabled -> interactions.setEnabled(InterceptionMode.REPAIR, challenge, enabled));
        Switch toggle = activity.findViewById(viewId);
        toggle.setEnabled(!locked);
        // 锁定只限制操作，仍使用正常开启的轨道和滑块颜色。
        toggle.setTrackTintList(locked
                ? android.content.res.ColorStateList.valueOf(activity.getColor(R.color.switch_enabled))
                : activity.getColorStateList(R.color.switch_track));
        toggle.setThumbTintList(locked
                ? android.content.res.ColorStateList.valueOf(activity.getColor(R.color.switch_knob))
                : activity.getColorStateList(R.color.switch_thumb));
        if (locked) {
            toggle.setContentDescription(activity.getString(R.string.settings_last_challenge_toggle,
                    toggle.getContentDescription()));
        }
        toggle.setTooltipText(locked ? toggle.getContentDescription() : null);
    }

    /**
     * 刷新当前模式的单词摘要。
     * @param mode 参数所属模式
     * @param rowId 设置行标识
     * @param valueId 摘要标识
     */
    private void renderWordValue(InterceptionMode mode, int rowId, int valueId) {
        WordChallengeSettings word = interactions.wordSettings(mode);
        String parameters = activity.getString(R.string.settings_word_challenge_value, word.questionCount,
                DecimalDuration.format(word.minDwellMillis, DecimalDuration.SECOND_MILLIS),
                DecimalDuration.format(word.minimumDurationMillis(), DecimalDuration.SECOND_MILLIS));
        String value = activity.getString(R.string.settings_word_catalog_value,
                activity.getString(word.catalog.nameRes), parameters);
        ((TextView) activity.findViewById(valueId)).setText(value);
        activity.findViewById(rowId).setContentDescription(activity.getString(R.string.settings_row_description,
                activity.getString(R.string.settings_word_challenge_title), value));
    }

    /** 销毁设置页或显示其他弹窗前结束布局动画并释放参数弹窗。 */
    public void closeSettingsDialog() {
        TransitionManager.endTransitions(settingsContent);
        if (settingsDialog != null) settingsDialog.dismiss();
        settingsDialog = null;
    }

    /** 按策略分类刷新模式选项、闭关恢复时间与修心摩擦参数。 */
    public void renderInterceptionSettings() {
        renderModeOptions();
        String resetValue = activity.getString(R.string.settings_daily_reset_value,
                formatMinuteOfDay(settings.dailyResetMinute()));
        ((TextView) activity.findViewById(R.id.daily_reset_value)).setText(resetValue);
        activity.findViewById(R.id.row_daily_reset).setContentDescription(activity.getString(
                R.string.settings_row_description,
                activity.getString(R.string.settings_daily_reset_title), resetValue));

        renderRangeValue(R.id.row_hold_range, R.id.hold_range_value,
                R.string.settings_hold_range_title, settings.buttonFrictionRange());

        renderWordValue(InterceptionMode.REPAIR, R.id.row_word_challenge, R.id.word_challenge_value);
        String difficulty = activity.getString(sudokuDifficultyLabel(interactions.sudokuDifficulty()));
        ((TextView) activity.findViewById(R.id.sudoku_difficulty_value)).setText(difficulty);
        activity.findViewById(R.id.row_sudoku_challenge).setContentDescription(activity.getString(
                R.string.settings_row_description, activity.getString(R.string.settings_challenge_sudoku_title), difficulty));
    }

    /** 显示三档难度草稿，点击完成才保存，返回取消不影响已有配置。 */
    private void chooseSudokuDifficulty() {
        closeSettingsDialog();
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_sudoku_difficulty, null, false);
        android.widget.RadioGroup choices = content.findViewById(R.id.sudoku_difficulty_options);
        SudokuDifficulty current = interactions.sudokuDifficulty();
        choices.check(current == SudokuDifficulty.HARD ? R.id.sudoku_difficulty_hard
                : current == SudokuDifficulty.MEDIUM ? R.id.sudoku_difficulty_medium : R.id.sudoku_difficulty_easy);
        settingsDialog = SettingsBottomSheet.show(activity, modeTitle(R.string.settings_challenge_sudoku_title), content, () -> {
            int selected = choices.getCheckedRadioButtonId();
            interactions.setSudokuDifficulty(selected == R.id.sudoku_difficulty_hard ? SudokuDifficulty.HARD
                    : selected == R.id.sudoku_difficulty_medium ? SudokuDifficulty.MEDIUM : SudokuDifficulty.EASY);
            renderInterceptionSettings();
            return true;
        }, null);
    }

    /** @param difficulty 当前配置的难度 @return 只描述难度的设置摘要资源 */
    private static int sudokuDifficultyLabel(SudokuDifficulty difficulty) {
        switch (difficulty) {
            case HARD: return R.string.settings_sudoku_hard;
            case MEDIUM: return R.string.settings_sudoku_medium;
            default: return R.string.settings_sudoku_easy;
        }
    }

    /**
     * 将一个秒数区间渲染到设置行，并补齐整行可读的无障碍描述。
     *
     * @param rowId 设置行容器的视图 ID
     * @param valueId 设置摘要文本的视图 ID
     * @param titleRes 标题对应的字符串资源 ID
     * @param range 经过校验的时长区间
     */
    private void renderRangeValue(int rowId, int valueId, int titleRes, SecondsRange range) {
        String value = formatRange(range);
        ((TextView) activity.findViewById(valueId)).setText(value);
        activity.findViewById(rowId).setContentDescription(activity.getString(R.string.settings_row_description,
                activity.getString(titleRes), value));
    }

    /** 显示原生时间选择器并保存全天规则的每日恢复分钟。 */
    private void chooseDailyResetTime() {
        closeSettingsDialog();
        int minuteOfDay = settings.dailyResetMinute();
        TimePickerDialog dialog = new TimePickerDialog(activity, R.style.Theme_Zhijian_Dialog, (picker, hour, minute) -> {
            settings.setDailyResetMinute(hour * 60 + minute);
            renderInterceptionSettings();
        }, minuteOfDay / 60, minuteOfDay % 60, true);
        dialog.setTitle(R.string.settings_daily_reset_title);
        settingsDialog = dialog;
        AppDialogs.show(dialog);
    }

    /**
     * 显示最小值和最大值输入框，校验通过后原子保存随机区间。
     *
     * @param titleRes 标题对应的字符串资源 ID
     * @param current 当前模式已保存的按压时长区间
     * @param saver 校验通过后原子保存时长区间的回调
     */
    private void chooseSecondsRange(int titleRes, SecondsRange current, RangeSaver saver) {
        closeSettingsDialog();
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_seconds_range, null, false);
        EditText minField = content.findViewById(R.id.field_min_seconds);
        EditText maxField = content.findViewById(R.id.field_max_seconds);
        SettingsNumberStepper.bindDuration(minField, current.minMillis,
                GlobalSettings.MIN_INTERACTION_MILLIS, GlobalSettings.MAX_INTERACTION_MILLIS,
                null);
        SettingsNumberStepper.bindDuration(maxField, current.maxMillis,
                GlobalSettings.MIN_INTERACTION_MILLIS, GlobalSettings.MAX_INTERACTION_MILLIS,
                null);
        settingsDialog = SettingsBottomSheet.show(activity, modeTitle(titleRes),
                content, () -> saveSecondsRange(content, minField, maxField, saver), null);
    }

    /**
     * 解析并校验区间输入；错误保留弹窗，成功后保存并刷新摘要。
     *
     * @param minField 输入时长区间下界的文本框
     * @param content 当前区间表单
     * @param maxField 输入时长区间上界的文本框
     * @param saver 校验通过后原子保存时长区间的回调
     * @return 保存成功时允许底部弹窗关闭
     */
    private boolean saveSecondsRange(View content, EditText minField, EditText maxField,
            RangeSaver saver) {
        long min = parseDuration(minField, DecimalDuration.SECOND_MILLIS);
        long max = parseDuration(maxField, DecimalDuration.SECOND_MILLIS);
        boolean valid = SecondsRange.isValid(min, max);
        if (!valid) {
            String error = activity.getString(R.string.settings_seconds_range_invalid,
                    DecimalDuration.format(GlobalSettings.MIN_INTERACTION_MILLIS,
                            DecimalDuration.SECOND_MILLIS),
                    DecimalDuration.format(GlobalSettings.MAX_INTERACTION_MILLIS,
                            DecimalDuration.SECOND_MILLIS));
            SettingsValidation.show(content, min < GlobalSettings.MIN_INTERACTION_MILLIS
                    || min > GlobalSettings.MAX_INTERACTION_MILLIS || min > max
                    ? minField : maxField, error);
            return false;
        }
        saver.save(new SecondsRange(min, max));
        renderInterceptionSettings();
        return true;
    }

    /**
     * 显示本模式独立的词库、题量与停留时间弹窗。
     * @param mode 当前编辑的模式
     */
    private void chooseWordChallengeSettings(InterceptionMode mode) {
        closeSettingsDialog();
        WordChallengeSettings current = interactions.wordSettings(mode);
        View content = activity.getLayoutInflater().inflate(R.layout.dialog_word_challenge, null, false);
        EditText countField = content.findViewById(R.id.field_word_question_count);
        EditText dwellField = content.findViewById(R.id.field_word_dwell_seconds);
        WordCatalogSettingsBinder catalogBinder = new WordCatalogSettingsBinder(activity, content, current.catalog);
        Runnable updateMinimum = () -> renderWordMinimum(content, countField, dwellField, catalogBinder);
        SettingsNumberStepper countStepper = SettingsNumberStepper.bindCount(countField, current.questionCount,
                WordChallengeSettings.MIN_QUESTION_COUNT,
                () -> ZhijianStorage.get(activity).wordCatalog(catalogBinder.selectedCatalog()).size(), updateMinimum);
        SettingsNumberStepper.bindDuration(dwellField, current.minDwellMillis,
                WordChallengeSettings.MIN_DWELL_MILLIS, WordChallengeSettings.MAX_DWELL_MILLIS, updateMinimum);
        catalogBinder.onSelectionChanged(() -> {
            InputError.clear(countField);
            countStepper.refreshBounds();
        });
        settingsDialog = SettingsBottomSheet.show(activity,
                modeTitle(R.string.settings_challenge_word_title), content,
                () -> saveWordChallengeSettings(content, countField, dwellField, mode, catalogBinder), null);
    }

    /**
     * 按合法草稿实时显示整体保底时间，尚未保存的输入不写入设置。
     * @param content 当前单词配置视图
     * @param countField 草稿题量输入框
     * @param dwellField 草稿停留秒数输入框
     * @param catalogBinder 当前词库草稿，容量来自启动时已校验的词条
     */
    private void renderWordMinimum(View content, EditText countField, EditText dwellField,
            WordCatalogSettingsBinder catalogBinder) {
        long count = parseCount(countField);
        long dwell = parseDuration(dwellField, DecimalDuration.SECOND_MILLIS);
        int catalogSize = ZhijianStorage.get(activity).wordCatalog(catalogBinder.selectedCatalog()).size();
        TextView value = content.findViewById(R.id.word_minimum_duration_value);
        if (count > catalogSize) {
            InputError.show(countField, activity.getString(R.string.settings_word_count_exceeds_catalog));
        }
        if (WordChallengeSettings.isValidQuestionCount(count, catalogSize)
                && WordChallengeSettings.isValid((int) count, dwell)) {
            value.setText(activity.getString(R.string.settings_seconds_value,
                    DecimalDuration.format(count * dwell, DecimalDuration.SECOND_MILLIS)));
        } else {
            value.setText(R.string.settings_total_duration_pending);
        }
    }

    /**
     * 生成带模式标识的挑战弹窗标题。
     * @param titleRes 当前挑战名称
     * @return 供底部弹窗显示的标题
     */
    private String modeTitle(int titleRes) {
        return activity.getString(R.string.settings_mode_challenge_title,
                activity.getString(R.string.settings_repair_mode), activity.getString(titleRes));
    }

    /**
     * 校验所选词库容量、题量与停留时长，成功后原子保存并刷新摘要。
     *
     * @param countField 输入挑战题量的文本框
     * @param content 当前单词表单
     * @param dwellField 输入每题停留秒数的文本框
     * @param mode 当前编辑的模式
     * @param catalogBinder 当前词库选择草稿
     * @return 保存成功时允许底部弹窗关闭
     */
    private boolean saveWordChallengeSettings(View content, EditText countField,
            EditText dwellField, InterceptionMode mode, WordCatalogSettingsBinder catalogBinder) {
        long questionCount = parseCount(countField);
        long dwellMillis = parseDuration(dwellField, DecimalDuration.SECOND_MILLIS);
        BuiltInWordCatalog catalog = catalogBinder.selectedCatalog();
        int catalogSize = ZhijianStorage.get(activity).wordCatalog(catalog).size();
        boolean validCount = WordChallengeSettings.isValidQuestionCount(questionCount, catalogSize);
        boolean validDwell = dwellMillis >= WordChallengeSettings.MIN_DWELL_MILLIS
                && dwellMillis <= WordChallengeSettings.MAX_DWELL_MILLIS;
        if (!validCount) {
            String error = questionCount > catalogSize
                    ? activity.getString(R.string.settings_word_count_exceeds_catalog)
                    : activity.getString(R.string.settings_word_question_invalid, WordChallengeSettings.MIN_QUESTION_COUNT);
            SettingsValidation.show(content, countField, error);
            return false;
        }
        if (!validDwell) {
            SettingsValidation.show(content, dwellField, activity.getString(R.string.settings_word_dwell_invalid,
                    DecimalDuration.format(WordChallengeSettings.MIN_DWELL_MILLIS,
                            DecimalDuration.SECOND_MILLIS),
                    DecimalDuration.format(WordChallengeSettings.MAX_DWELL_MILLIS,
                            DecimalDuration.SECOND_MILLIS)));
            return false;
        }
        interactions.setWordSettings(mode,
                new WordChallengeSettings((int) questionCount, dwellMillis, catalog));
        renderInterceptionSettings();
        return true;
    }

    /**
     * 将题量草稿转换为长整数，便于对超大输入显示词库容量错误。
     *
     * @param field 承载当前数值输入的文本框
     */
    private static long parseCount(EditText field) {
        return UnsignedInteger.parse(field.getText().toString());
    }

    /**
     * 将允许一位小数的输入精确换算为毫秒。
     *
     * @param field 用户正在编辑的数字输入框
     * @param unitMillis 一个界面单位对应的毫秒数
     * @return 合法输入对应的毫秒数；非法输入返回 -1
     */
    private static long parseDuration(EditText field, long unitMillis) {
        return DecimalDuration.parseMillis(field.getText().toString(), unitMillis);
    }

    /**
     * 对固定值和随机区间使用不同摘要，避免显示“75–75 秒”。
     *
     * @param range 经过校验的时长区间
     */
    private String formatRange(SecondsRange range) {
        String min = DecimalDuration.format(range.minMillis, DecimalDuration.SECOND_MILLIS);
        String max = DecimalDuration.format(range.maxMillis, DecimalDuration.SECOND_MILLIS);
        return range.minMillis == range.maxMillis
                ? activity.getString(R.string.settings_seconds_fixed, min)
                : activity.getString(R.string.settings_seconds_range,
                        min, max);
    }

    /**
     * 把一天内的分钟值格式化为固定两位的 24 小时时间。
     *
     * @param minute 一天内对应的分钟值
     */
    private static String formatMinuteOfDay(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }
}
