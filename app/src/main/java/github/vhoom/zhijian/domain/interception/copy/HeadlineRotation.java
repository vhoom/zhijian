package github.vhoom.zhijian.domain.interception.copy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * 主句的不放回抽取策略：每轮独立洗牌，换轮时避开上一轮最后一句。
 *
 * 维护日期：2026年11月13日
 */
public final class HeadlineRotation {
    /** 纯选择策略通过静态入口使用。 */
    private HeadlineRotation() { }

    /**
     * 从剩余队列抽取一句；队列耗尽后重新洗牌，不修改调用方的列表。
     * @param candidates 当前文案库，重复正文仅参与一次抽样
     * @param remaining 上次保存的剩余队列，失效正文会被移除
     * @param previous 上一次新会话选中的正文
     * @param random 可注入的随机源，生产环境使用系统安全随机源
     * @return 本次正文和下一次抽取使用的只读剩余队列
     */
    public static Draw draw(List<String> candidates, List<String> remaining,
            String previous, Random random) {
        LinkedHashSet<String> available = new LinkedHashSet<>(candidates);
        if (available.isEmpty()) throw new IllegalArgumentException("Empty headline pool");
        LinkedHashSet<String> restored = new LinkedHashSet<>(remaining);
        restored.retainAll(available);
        List<String> queue = new ArrayList<>(restored);
        if (queue.isEmpty()) {
            queue.addAll(available);
            Collections.shuffle(queue, random);
            if (queue.size() > 1 && queue.get(0).equals(previous)) {
                Collections.swap(queue, 0, 1 + random.nextInt(queue.size() - 1));
            }
        }
        return new Draw(queue.remove(0), queue);
    }

    /** 一次抽取的不可变结果，由存储适配器保存正文和剩余队列。 */
    public static final class Draw {
        public final String text;
        public final List<String> remaining;

        /** @param text 本次正文 @param remaining 本次抽取后的剩余队列 */
        private Draw(String text, List<String> remaining) {
            this.text = text;
            this.remaining = Collections.unmodifiableList(new ArrayList<>(remaining));
        }
    }
}
