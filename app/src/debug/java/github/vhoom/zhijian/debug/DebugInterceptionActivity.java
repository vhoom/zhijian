package github.vhoom.zhijian.debug;

import github.vhoom.zhijian.data.model.interception.InterceptionChallenge;

import github.vhoom.zhijian.data.model.interception.InterceptionStage;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import github.vhoom.zhijian.data.model.policy.ControlLevel;
import github.vhoom.zhijian.domain.interception.InterceptionDecision;
import github.vhoom.zhijian.ui.interception.InterceptionActivity;
import github.vhoom.zhijian.ui.interception.preview.InterceptionPreviewCatalog;
import github.vhoom.zhijian.ui.interception.request.InterceptionIntents;
import github.vhoom.zhijian.util.diagnostics.DiagnosticLog;

import java.util.Locale;

/**
 * ADB 可直接启动的 Debug 预览入口。
 *
 * <p>使用 Activity 而不是广播接收器，是为了避免 Android 10+ 和部分厂商系统禁止
 * 后台 Receiver 拉起页面。该入口只存在于 Debug 变体，不会进入正式包。</p>
 *
 * 维护日期：2026年11月08日
 */
public final class DebugInterceptionActivity extends Activity {
    /**
     * 接收 ADB 预览参数，转发到真正拦截页后立即退出。
     *
     * @param savedInstanceState 系统保存的上次页面状态，首次创建时为 null
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchPreview(getIntent());
        finish();
    }

    /**
     * 规范化预览档位、阶段和会话，再启动对应页面。
     *
     * @param command 调试入口收到的启动请求
     */
    private void launchPreview(Intent command) {
        if (command.getBooleanExtra("gallery", false)) {
            startActivity(InterceptionIntents.debugPageIntent(this, command.getIntExtra("page", 0),
                    command.getIntExtra("sample", 0), InterceptionPreviewCatalog.Presentation.parse(
                            command.getStringExtra("presentation"))));
            return;
        }
        ControlLevel level = parseLevel(command.getStringExtra("level"));
        InterceptionDecision.Kind kind = "interrupt".equalsIgnoreCase(
                command.getStringExtra("kind"))
                ? InterceptionDecision.Kind.INTERRUPT : InterceptionDecision.Kind.BLOCK;
        InterceptionStage stage = parseStage(command.getStringExtra("stage"));
        if (stage == InterceptionStage.LOCKED) {
            level = ControlLevel.SEAL;
        } else if (stage == InterceptionStage.FINAL_CONFIRM) {
            level = ControlLevel.REPAIR;
        } else if ((stage == InterceptionStage.HOLD
                || stage == InterceptionStage.WORD_TRANSLATION)) {
            level = ControlLevel.REPAIR;
        }
        String challenge = stage == InterceptionStage.WORD_TRANSLATION
                ? "word" : command.getStringExtra("challenge");
        String session = sessionFor(challenge);
        Intent page = InterceptionActivity.debugIntent(this, kind, level, stage, session)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        DiagnosticLog.debug("debug-preview", "launch kind=" + kind.name()
                + " level=" + level.code + " stage=" + (stage == null ? "initial" : stage.name())
                + " session=" + session);
        startActivity(page);
    }

    /**
     * 将 ADB 的档位文本映射到稳定枚举，缺省为修心。
     *
     * @param value 本次需要读取、转换或写入的值
     */
    private static ControlLevel parseLevel(String value) {
        if (value == null) return ControlLevel.REPAIR;
        switch (value.toLowerCase(Locale.ROOT)) {
            case "mortal": return ControlLevel.MORTAL;
            case "seal": return ControlLevel.SEAL;
            default: return ControlLevel.REPAIR;
        }
    }

    /**
     * 将 ADB 状态名解析为预览页，缺省时使用正常初始页。
     *
     * @param value 本次需要读取、转换或写入的值
     */
    private static InterceptionStage parseStage(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return InterceptionStage.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidStage) {
            return null;
        }
    }

    /**
     * 生成可预测的会话 ID，并让指定的按钮或单词摩擦稳定出现。
     *
     * @param challenge 本次会话选择的摩擦挑战
     */
    private static String sessionFor(String challenge) {
        InterceptionChallenge expected = "word".equalsIgnoreCase(challenge)
                ? InterceptionChallenge.WORD_TRANSLATION
                : InterceptionChallenge.BUTTON_HOLD;
        String session = "debug-" + (expected == InterceptionChallenge.WORD_TRANSLATION
                ? "word" : "button");
        while (InterceptionChallenge.selectFor(session) != expected) session += 'a';
        return session;
    }
}
