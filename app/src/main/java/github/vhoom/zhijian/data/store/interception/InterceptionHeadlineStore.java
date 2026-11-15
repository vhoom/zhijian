package github.vhoom.zhijian.data.store.interception;

import android.content.Context;
import android.content.SharedPreferences;

import github.vhoom.zhijian.domain.interception.copy.HeadlineRotation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 主句轮换的存储适配器：按文案库保存队列，按规则的拦截会话保存已选正文。
 * 正文与抽取进度一起异步落盘；只保留当前及尚未清理的会话，避免历史无限增长。
 *
 * 维护日期：2026年11月13日
 */
public final class InterceptionHeadlineStore {
    private static final String FILE = "interception_headlines";
    private static final String POOL_PREFIX = "pool:";
    private static final String SESSION_PREFIX = "session:";
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SharedPreferences preferences;
    private final Random random;
    private final Supplier<Set<String>> activeSessions;

    /** @param context 打开私有存储的上下文，不保留 Activity 引用 */
    public InterceptionHeadlineStore(Context context) {
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), RANDOM,
                new PendingInterceptionStore(context)::sessionIds);
    }

    /**
     * 注入存储及选择依赖，便于在独立偏好文件中验证恢复和会话保留。
     * @param preferences 独立主句偏好存储
     * @param random 新一轮洗牌使用的随机源
     * @param activeSessions 当前持久化的未清理会话快照
     */
    InterceptionHeadlineStore(SharedPreferences preferences, Random random,
            Supplier<Set<String>> activeSessions) {
        this.preferences = preferences;
        this.random = random;
        this.activeSessions = activeSessions;
    }

    /**
     * 同一规则的当前拦截会话复用正文，仅首次显示此类主句时消耗一条。
     * @param pool 文案库稳定名称，普通首屏与闭关使用同一个名称
     * @param sessionId 本轮规则拦截的稳定身份，与控件打开种子无关
     * @param candidates 当前文案库；修改资源后只重置此库的队列
     * @return 本轮已经选定的主句正文
     */
    public String getOrCreate(String pool, String sessionId, List<String> candidates) {
        synchronized (LOCK) {
            String sessionKey = SESSION_PREFIX + sessionId;
            JSONObject selected = readObject(sessionKey);
            Object saved = selected.opt(pool);
            String cached = saved instanceof String ? (String) saved : "";
            if (!cached.isEmpty()) return cached;

            String poolKey = POOL_PREFIX + pool;
            JSONObject state = readObject(poolKey);
            String catalog = new JSONArray(candidates).toString();
            List<String> remaining = catalog.equals(state.optString("catalog"))
                    ? readQueue(state.optJSONArray("remaining")) : Collections.emptyList();
            HeadlineRotation.Draw draw = HeadlineRotation.draw(candidates, remaining,
                    state.optString("last", ""), random);
            try {
                selected.put(pool, draw.text);
                JSONObject next = new JSONObject().put("catalog", catalog)
                        .put("remaining", new JSONArray(draw.remaining)).put("last", draw.text);
                SharedPreferences.Editor editor = preferences.edit()
                        .putString(poolKey, next.toString()).putString(sessionKey, selected.toString());
                removeInactiveSessions(editor, sessionId);
                editor.apply();
                return draw.text;
            } catch (JSONException invalid) {
                throw new IllegalStateException("Could not save interception headline", invalid);
            }
        }
    }

    /** @param key 私有存储键 @return 当前格式的对象，缺失或损坏时返回空对象供重新抽取 */
    private JSONObject readObject(String key) {
        try {
            return new JSONObject(preferences.getString(key, "{}"));
        } catch (JSONException | ClassCastException invalid) {
            return new JSONObject();
        }
    }

    /** @param saved 已保存的剩余队列，可为空 @return 可交给选择策略检查的正文列表 */
    private static List<String> readQueue(JSONArray saved) {
        List<String> result = new ArrayList<>();
        if (saved != null) {
            for (int index = 0; index < saved.length(); index++) result.add(saved.optString(index, ""));
        }
        return result;
    }

    /** @param editor 与本次正文及队列一起提交的编辑器 @param sessionId 当前正在显示的会话 */
    private void removeInactiveSessions(SharedPreferences.Editor editor, String sessionId) {
        Set<String> retained = new HashSet<>(activeSessions.get());
        retained.add(sessionId);
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(SESSION_PREFIX) && !retained.contains(key.substring(SESSION_PREFIX.length()))) {
                editor.remove(key);
            }
        }
    }
}
