package github.vhoom.zhijian.data.model.policy;

import github.vhoom.zhijian.util.time.DecimalDuration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则组保存前的纯数据校验，不依赖 Android UI，便于编辑页和后续拦截引擎复用。
 *
 * 维护日期：2026年11月13日
 */
public final class RuleGroupValidator {

    /**
     * 规则校验失败的具体类型，供界面定位错误输入。
     */
    public enum Error {
        NONE,
        APPS_REQUIRED,
        TYPE_REQUIRED,
        PARAM_REQUIRED,
        WINDOW_REQUIRED,
        WINDOW_INVALID,
        WINDOW_DUPLICATE,
        WINDOW_OVERLAP,
        CROSS_RULE_OVERLAP,
        WINDOW_ID_DUPLICATE,
        WINDOW_LIMIT_EXCEEDS,
        INTERRUPTION_PAUSE_REQUIRED,
        PASS_REQUIRED,
        COUNT_OUT_OF_RANGE,
        VALUE_TOO_LARGE
    }

    /** 错误输入的参数角色，确保休息与次数不会定位到使用时长。 */
    public enum Field { VALUE, COUNT, REST, PASS }

    /**
     * 模型校验结果，保留错误类型和对应的条件位置。
     */
    public static final class Result {

        public final Error error;
        public final RuleType type;
        public final String windowId;
        /** 星期配置中的错误归属；0 表示全局参数或首次编辑草稿。 */
        public final int weekday;
        /** 完整周冲突的两侧位置，供界面解释错误而不覆盖尚未保存的输入。 */
        public final WeeklyWindowValidation.Conflict conflict;
        /** 输入位置与通用提示分类；不向展示层传递真实范围。 */
        public final Field field;
        public final boolean tooLarge;

        /**
         * 创建 Result 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param error 校验失败的具体分类
         * @param type 当前规则条件的类型
         */
        private Result(Error error, RuleType type) {
            this(error, type, null);
        }

        /**
         * 创建 Result 实例，并初始化本次操作依赖或不可变字段。
         *
         * @param error 校验失败的具体分类
         * @param type 当前规则条件的类型
         * @param windowId 当前时段的稳定标识
         */
        private Result(Error error, RuleType type, String windowId) {
            this(error, type, windowId, 0);
        }

        /** @param error 错误分类 @param type 条件类型 @param windowId 错误时段 @param weekday 错误归属星期 */
        private Result(Error error, RuleType type, String windowId, int weekday) {
            this(error, type, windowId, weekday, null);
        }

        /** @param error 错误类型 @param type 当前类型 @param windowId 当前窗口
         * @param weekday 当前归属星期 @param conflict 可空的完整周冲突信息 */
        private Result(Error error, RuleType type, String windowId, int weekday, WeeklyWindowValidation.Conflict conflict) {
            this(error, type, windowId, weekday, conflict, fieldFor(error),
                    error == Error.VALUE_TOO_LARGE || error == Error.WINDOW_LIMIT_EXCEEDS);
        }

        /** @param error 错误分类 @param type 条件类型 @param windowId 可空时段身份
         * @param field 输入角色 @param tooLarge 是否超过上界 */
        private Result(Error error, RuleType type, String windowId, Field field, boolean tooLarge) {
            this(error, type, windowId, 0, null, field, tooLarge);
        }

        /** @param error 错误分类 @param type 条件类型 @param windowId 可空时段身份
         * @param weekday 错误星期 @param conflict 可空冲突 @param field 输入角色
         * @param tooLarge 是否超过数值上界 */
        private Result(Error error, RuleType type, String windowId, int weekday,
                WeeklyWindowValidation.Conflict conflict, Field field, boolean tooLarge) {
            this.error = error;
            this.type = type;
            this.windowId = windowId;
            this.weekday = weekday;
            this.conflict = conflict;
            this.field = field;
            this.tooLarge = tooLarge;
        }

        /** @param error 既有校验错误 @return 对应参数角色 */
        private static Field fieldFor(Error error) {
            if (error == Error.COUNT_OUT_OF_RANGE) return Field.COUNT;
            if (error == Error.INTERRUPTION_PAUSE_REQUIRED) return Field.REST;
            if (error == Error.PASS_REQUIRED) return Field.PASS;
            return Field.VALUE;
        }

        /**
         * 返回校验结果或输入是否满足当前模型的完整约束。
         */
        public boolean isValid() {
            return error == Error.NONE;
        }
    }

    private static final Result VALID = new Result(Error.NONE, null);

    /**
     * 工具类不允许实例化，调用方直接使用静态方法。
     */
    private RuleGroupValidator() {
    }

    /**
     * 逐项检查已启用规则的参数和时段，并返回精确错误位置。
     *
     * @param group 待读取、校验或编辑的规则组
     */
    public static Result validate(RuleGroup group) {
        if (group == null || group.apps.isEmpty())
            return new Result(Error.APPS_REQUIRED, null);
        for (String app : group.apps) {
            if (app == null || app.trim().isEmpty()) return new Result(Error.APPS_REQUIRED, null);
        }
        // 完整七日空配置表示用户已明确清除规则，允许保存最后一个星期的清除结果。
        if (!group.hasAnyRules() && group.weekdayRules.isEmpty())
            return new Result(Error.TYPE_REQUIRED, null);
        return validateConfiguration(group);
    }

    /**
     * 检查共同配置约束，允许全局策略没有应用绑定和选中条件。
     * @param group 待检查的策略草稿
     * @return 页面与存储共用的精确校验结果
     */
    public static Result validateConfiguration(RuleGroup group) {
        Result fields = validateConfigurationFields(group);
        if (!fields.isValid()) return fields;
        WeeklyWindowValidation.Conflict conflict = WeeklyWindowValidation.firstConflict(group);
        if (conflict != null) return new Result(conflict.window.type != conflict.otherWindow.type
                ? Error.CROSS_RULE_OVERLAP : conflict.duplicate ? Error.WINDOW_DUPLICATE : Error.WINDOW_OVERLAP,
                conflict.window.type, conflict.window.id, group.weekdayRules.isEmpty() ? 0 : conflict.weekday, conflict);
        return VALID;
    }

    /** @param group 单日或完整七日配置 @return 字段错误；跨日冲突由完整候选统一校验 */
    private static Result validateConfigurationFields(RuleGroup group) {
        if (!group.weekdayRules.isEmpty()) {
            if (group.weekdayRules.size() != 7) return new Result(Error.PARAM_REQUIRED, null);
            for (int day = 1; day <= 7; day++) {
                RuleDayParameters parameters = group.weekdayRules.get(day);
                if (parameters == null) return new Result(Error.PARAM_REQUIRED, null, null, day);
                Result result = validateConfigurationFields(RuleDayParameterAdapter.withParameters(group, parameters));
                if (!result.isValid()) return onWeekday(result, day);
            }
            return VALID;
        }
        Result storedFields = validateStoredFields(group);
        if (!storedFields.isValid()) return storedFields;
        Result boundedFields = validateInputBounds(group);
        if (!boundedFields.isValid()) return boundedFields;
        for (RuleType type : RuleType.values()) {
            if (!group.has(type)) continue;
            if (type.isWindow()) {
                List<RuleWindow> windows = group.windowsFor(type);
                if (windows.isEmpty()) return new Result(Error.WINDOW_REQUIRED, type);
                for (RuleWindow window : windows) {
                    if (!RuleWindowValidation.hasValidEndpoints(window)) {
                        return new Result(Error.WINDOW_INVALID, type, window.id);
                    }
                    if (type == RuleType.WINDOW_COUNT && !CountLimit.isValid(window.limitCount)) {
                        return countError(window.limitCount, type, window.id);
                    }
                    if (type != RuleType.WINDOW_BAN && !type.isCount()
                            && !validPositiveDuration(window.limitDurationMillis)) {
                        return new Result(Error.PARAM_REQUIRED, type, window.id);
                    }
                    long durationMs = RuleWindowValidation.durationMinutes(window) * DecimalDuration.MINUTE_MILLIS;
                    if (type != RuleType.WINDOW_BAN && !type.isCount()
                            && RuleInputLimits.usage(type, durationMs).checkValue(window.limitDurationMillis, true)
                            == RuleInputLimits.Status.TOO_LARGE) {
                        return new Result(Error.WINDOW_LIMIT_EXCEEDS, type, window.id);
                    }
                    if (type == RuleType.WINDOW_CONTINUOUS && !validPositiveDuration(window.interruptionPauseMillis)) {
                        return new Result(Error.INTERRUPTION_PAUSE_REQUIRED, type, window.id);
                    }
                }
            } else if (type == RuleType.DAILY_COUNT && !CountLimit.isValid(group.count)) {
                return countError(group.count, type, null);
            } else if (type == RuleType.ROLLING_COUNT && !CountLimit.isValid(group.frequencyCount)) {
                return countError(group.frequencyCount, type, null);
            } else if (type != RuleType.DAILY_LIMIT && parameterOf(group, type) <= 0) {
                return new Result(Error.PARAM_REQUIRED, type);
            } else if (type == RuleType.CONTINUOUS && !validPositiveDuration(group.interruptionPauseMillis)) {
                return new Result(Error.INTERRUPTION_PAUSE_REQUIRED, type);
            }
        }
        if (!validPositiveDuration(group.passDurationMillis)) return new Result(Error.PASS_REQUIRED, null);
        return VALID;
    }

    /**
     * 保存时为全部可保留参数检查绝对上界；时段关联与启用下界继续由原校验处理。
     * @param group 单日候选，包含启用与关闭参数
     * @return 上界错误及原参数位置
     */
    private static Result validateInputBounds(RuleGroup group) {
        for (RuleType type : RuleType.values()) {
            // 次数的绝对范围已经由结构检查验证；频率规则此处检查分钟窗口。
            if (type.isWindow() || type == RuleType.DAILY_COUNT) continue;
            RuleInputLimits.Range range = RuleInputLimits.usage(type, 0L);
            if (range.checkValue(parameterOf(group, type), false) == RuleInputLimits.Status.TOO_LARGE) {
                return new Result(Error.VALUE_TOO_LARGE, type, null, Field.VALUE, true);
            }
        }
        if (RuleInputLimits.rest().checkValue(group.interruptionPauseMillis, false) == RuleInputLimits.Status.TOO_LARGE) {
            return new Result(Error.VALUE_TOO_LARGE, RuleType.CONTINUOUS, null, Field.REST, true);
        }
        if (RuleInputLimits.pass().checkValue(group.passDurationMillis, false) == RuleInputLimits.Status.TOO_LARGE) {
            return new Result(Error.VALUE_TOO_LARGE, null, null, Field.PASS, true);
        }
        for (RuleWindow window : group.windows) {
            if (window.type != RuleType.WINDOW_BAN && !window.type.isCount()
                    && RuleInputLimits.usage(window.type, 0L).checkValue(window.limitDurationMillis, false)
                    == RuleInputLimits.Status.TOO_LARGE) {
                return new Result(Error.VALUE_TOO_LARGE, window.type, window.id, Field.VALUE, true);
            }
            if (window.type == RuleType.WINDOW_CONTINUOUS
                    && RuleInputLimits.rest().checkValue(window.interruptionPauseMillis, false)
                    == RuleInputLimits.Status.TOO_LARGE) {
                return new Result(Error.VALUE_TOO_LARGE, window.type, window.id, Field.REST, true);
            }
        }
        return VALID;
    }

    /** @param count 失败的次数 @param type 所属规则 @param windowId 可空时段身份 @return 通用提示所需的分类 */
    private static Result countError(long count, RuleType type, String windowId) {
        return new Result(Error.COUNT_OUT_OF_RANGE, type, windowId, Field.COUNT, count > CountLimit.MAX_COUNT);
    }

    /** @param result 原字段错误 @param weekday 错误所属星期 @return 保留参数角色与提示分类的副本 */
    private static Result onWeekday(Result result, int weekday) {
        return new Result(result.error, result.type, result.windowId, weekday, result.conflict, result.field, result.tooLarge);
    }

    /**
     * 校验持久化字段的非负值、精度、分钟范围和稳定身份；允许未完成的关闭条件。
     * @param group 保存草稿或升级时读取的旧策略
     * @return 页面和编解码入口共用的结构检查结果
     */
    public static Result validateStoredFields(RuleGroup group) {
        if (!group.weekdayRules.isEmpty()) {
            if (group.weekdayRules.size() != 7) return new Result(Error.PARAM_REQUIRED, null);
            for (int day = 1; day <= 7; day++) {
                RuleDayParameters parameters = group.weekdayRules.get(day);
                if (parameters == null) return new Result(Error.PARAM_REQUIRED, null, null, day);
                Result result = validateStoredFields(RuleDayParameterAdapter.withParameters(group, parameters));
                if (!result.isValid()) return onWeekday(result, day);
            }
        }
        for (RuleType type : RuleType.values()) {
            if (type.isWindow()) continue;
            int count = type == RuleType.DAILY_COUNT ? group.count : group.frequencyCount;
            if (type.isCount() && !CountLimit.isValidStored(count)) {
                return countError(count, type, null);
            }
            long value = parameterOf(group, type);
            if (value < 0 || type != RuleType.DAILY_COUNT && !validMinuteDuration(value)) {
                return new Result(Error.PARAM_REQUIRED, type);
            }
        }
        if (!validMinuteDuration(group.interruptionPauseMillis)) {
            return new Result(Error.INTERRUPTION_PAUSE_REQUIRED, RuleType.CONTINUOUS);
        }
        if (!validMinuteDuration(group.passDurationMillis)) return new Result(Error.PASS_REQUIRED, null);
        Set<String> windowIds = new HashSet<>();
        for (RuleWindow window : group.windows) {
            if (!windowIds.add(window.id)) {
                return new Result(Error.WINDOW_ID_DUPLICATE, window.type, window.id);
            }
            if (window.startMinute < 0 || window.startMinute >= RuleWindowValidation.MINUTES_PER_DAY
                    || window.endMinute < 0 || window.endMinute >= RuleWindowValidation.MINUTES_PER_DAY) {
                return new Result(Error.WINDOW_INVALID, window.type, window.id);
            }
            if (!validMinuteDuration(window.limitDurationMillis)) {
                return new Result(Error.PARAM_REQUIRED, window.type, window.id);
            }
            if (!CountLimit.isValidStored(window.limitCount)) {
                return countError(window.limitCount, window.type, window.id);
            }
            if (!validMinuteDuration(window.interruptionPauseMillis)) {
                return new Result(Error.INTERRUPTION_PAUSE_REQUIRED, window.type, window.id);
            }
        }
        return VALID;
    }

    /**
     * 检查规则时长为正数并可由分钟输入框无损回填。
     * @param millis 规则时长的整数毫秒数
     * @return 满足一分钟单位的一位小数精度时返回 true
     */
    private static boolean validPositiveDuration(long millis) {
        return millis > 0 && validMinuteDuration(millis);
    }

    /**
     * 检查可保留的时长字段，零值表示未完成或关闭。
     * @param millis 持久化时长的毫秒值
     * @return 非负且可按一位小数分钟无损显示时返回 true
     */
    private static boolean validMinuteDuration(long millis) {
        return DecimalDuration.hasOneDecimalPrecision(millis, DecimalDuration.MINUTE_MILLIS);
    }

    /**
     * 按条件类型读取对应阈值，避免不同条件共用参数。
     *
     * @param group 待读取、校验或编辑的规则组
     * @param type 当前规则条件的类型
     */
    public static long parameterOf(RuleGroup group, RuleType type) {
        switch (type) {
            case DAILY_COUNT:
                return group.count;
            case CONTINUOUS:
                return group.continuousLimitMillis;
            case DAILY_LIMIT:
                return group.dailyLimitMillis;
            case ROLLING_COUNT:
                return group.frequencyWindowMillis;
            default:
                throw new IllegalArgumentException("Window parameters belong to individual windows");
        }
    }
}
