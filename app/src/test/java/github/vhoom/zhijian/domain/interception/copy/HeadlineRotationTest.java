package github.vhoom.zhijian.domain.interception.copy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 主句每轮完整覆盖、跨轮防重复、队列恢复及单条文案库的纯逻辑回归。
 *
 * 维护日期：2026年11月13日
 */
public class HeadlineRotationTest {
    /** 新一轮把每条不同正文恰好抽取一次，不会提前重复。 */
    @Test public void aRoundVisitsEveryDistinctHeadlineOnce() {
        List<String> candidates = Arrays.asList("甲", "乙", "丙", "丁", "戊");
        List<String> remaining = Collections.emptyList();
        Set<String> seen = new HashSet<>();
        String previous = "";
        Random random = new Random(43);
        for (int index = 0; index < candidates.size(); index++) {
            HeadlineRotation.Draw draw = HeadlineRotation.draw(candidates, remaining, previous, random);
            assertTrue(seen.add(draw.text));
            remaining = draw.remaining;
            previous = draw.text;
        }
        assertEquals(new HashSet<>(candidates), seen);
        assertTrue(remaining.isEmpty());
    }

    /** 多轮抽取的边界不会连续出现上一轮最后一句。 */
    @Test public void newRoundsAvoidTheLastHeadlineOfThePreviousRound() {
        List<String> candidates = Arrays.asList("甲", "乙", "丙");
        List<String> remaining = Collections.emptyList();
        String previous = "";
        Random random = new Random(47);
        for (int index = 0; index < 90; index++) {
            HeadlineRotation.Draw draw = HeadlineRotation.draw(candidates, remaining, previous, random);
            assertNotEquals(previous, draw.text);
            previous = draw.text;
            remaining = draw.remaining;
        }
    }

    /** 恢复尚未耗尽的队列时按保存顺序继续，不重新洗牌。 */
    @Test public void restoredQueueKeepsItsRemainingOrder() {
        HeadlineRotation.Draw draw = HeadlineRotation.draw(Arrays.asList("甲", "乙", "丙"),
                Arrays.asList("丙", "乙"), "甲", new Random(53));
        assertEquals("丙", draw.text);
        assertEquals(Collections.singletonList("乙"), draw.remaining);
    }

    /** 单条文案库可以持续使用，不会为了去重而无法显示。 */
    @Test public void singleHeadlinePoolCanBeReused() {
        HeadlineRotation.Draw draw = HeadlineRotation.draw(Collections.singletonList("甲"),
                Collections.emptyList(), "甲", new Random(59));
        assertEquals("甲", draw.text);
        assertTrue(draw.remaining.isEmpty());
    }

    /** 同正文只参与一次抽取；损坏队列中的重复及失效正文不会被再次使用。 */
    @Test public void duplicateAndRemovedHeadlinesDoNotEnterTheQueue() {
        HeadlineRotation.Draw restored = HeadlineRotation.draw(Arrays.asList("甲", "甲", "乙"),
                Arrays.asList("失效", "乙", "乙"), "甲", new Random(61));
        assertEquals("乙", restored.text);
        assertTrue(restored.remaining.isEmpty());
        HeadlineRotation.Draw fresh = HeadlineRotation.draw(Arrays.asList("甲", "甲", "乙"),
                restored.remaining, restored.text, new Random(61));
        assertEquals("甲", fresh.text);
        assertEquals(Collections.singletonList("乙"), fresh.remaining);
    }

    /** 输入列表不被消耗或重排，返回的队列也不可被外部修改。 */
    @Test public void selectionDoesNotMutateCallerLists() {
        List<String> candidates = new ArrayList<>(Arrays.asList("甲", "乙", "丙"));
        List<String> remaining = new ArrayList<>(Arrays.asList("丙", "乙"));
        HeadlineRotation.Draw draw = HeadlineRotation.draw(candidates, remaining, "甲", new Random(67));
        assertEquals(Arrays.asList("甲", "乙", "丙"), candidates);
        assertEquals(Arrays.asList("丙", "乙"), remaining);
        try {
            draw.remaining.add("修改");
            fail("Remaining queue must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    /** 空文案库明确拒绝，避免返回无效主句。 */
    @Test(expected = IllegalArgumentException.class) public void emptyPoolIsRejected() {
        HeadlineRotation.draw(Collections.emptyList(), Collections.emptyList(), "", new Random(71));
    }
}
