package github.vhoom.zhijian.domain.usage;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 日期滑动释放时的小位移归位、滑动意图和未来边界保护。
 *
 * 维护日期：2026年11月08日
 */
public class UsageDateSnapTest {
    /** 低速小幅拖动只吸附回原位，不改变日期。 */
    @Test public void slowShortDragReturnsToOriginalDates() {
        assertEquals(0, UsageDateSnap.direction(30, 280, 0, 50, true));
    }

    /** 较长跟手拖动或明确滑动速度进入对应的相邻日期段。 */
    @Test public void releaseUsesBothDisplacementAndVelocity() {
        assertEquals(-1, UsageDateSnap.direction(120, 280, 0, 50, true));
        assertEquals(1, UsageDateSnap.direction(-30, 280, -100, 50, true));
    }

    /** 到达今天所在日期段后，向未来滑动仍归位。 */
    @Test public void futureEdgeDoesNotAdvanceEvenWithFastGesture() {
        assertEquals(0, UsageDateSnap.direction(-200, 280, -1000, 50, false));
    }
}
