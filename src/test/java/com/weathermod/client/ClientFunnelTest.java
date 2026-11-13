package com.weathermod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.weathermod.core.FunnelSpine;
import java.util.List;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * Checks how the client funnel blends from the spine it shows to each new keyframe. Pure arithmetic on spines, no
 * Minecraft bootstrap.
 */
class ClientFunnelTest {
    private static final float EPSILON = 1.0E-5F;

    /**
     * A two-point spine whose ground point is offset by {@code x} and whose every other number follows from it: the
     * top point is offset by {@code 2 x}, the base radius is {@code 2 + x}, the top radius {@code 8 + x}.
     */
    private static FunnelSpine spineAt(float x, boolean grounded) {
        return new FunnelSpine(List.of(new Vector3f(x, 64.0F, 0.0F), new Vector3f(2.0F * x, 160.0F, 0.0F)),
            2.0F + x, 8.0F + x, 0.5F, grounded ? 1.0F : 0.5F, grounded);
    }

    private static float groundX(FunnelSpine spine) {
        return spine.points().get(0).x();
    }

    private static void tick(ClientFunnel funnel, int ticks) {
        for (int i = 0; i < ticks; i++) {
            funnel.tick();
        }
    }

    @Test
    void aNewFunnelShowsItsFirstSpine() {
        FunnelSpine first = spineAt(0.0F, true);
        ClientFunnel funnel = new ClientFunnel(17, first);

        assertEquals(17, funnel.stormId());
        assertSame(first, funnel.keyframe());
        assertSame(first, funnel.spine(0.0F));
        assertSame(first, funnel.spine(0.5F));
        assertSame(first, funnel.spine(1.0F));
        assertEquals(0, funnel.ticksSinceKeyframe());

        tick(funnel, 30);
        assertSame(first, funnel.spine(0.5F), "without a second keyframe there is nothing to blend toward");
        assertEquals(30, funnel.ticksSinceKeyframe());
        assertThrows(NullPointerException.class, () -> new ClientFunnel(1, null));
    }

    @Test
    void aKeyframeIsApproachedLinearlyOverTheInterval() {
        ClientFunnel funnel = new ClientFunnel(1, spineAt(0.0F, true));
        FunnelSpine target = spineAt(10.0F, true);

        funnel.accept(target, 10);

        assertSame(target, funnel.keyframe());
        assertEquals(0, funnel.ticksSinceKeyframe());
        assertEquals(0.0F, groundX(funnel.spine(0.0F)), EPSILON, "the blend starts at the spine that was shown");
        assertEquals(0.5F, groundX(funnel.spine(0.5F)), EPSILON, "half a tick is a twentieth of the way");
        assertEquals(1.0F, groundX(funnel.spine(1.0F)), EPSILON);

        tick(funnel, 3);
        assertEquals(3, funnel.ticksSinceKeyframe());
        assertEquals(3.0F, groundX(funnel.spine(0.0F)), EPSILON);
        assertEquals(3.5F, groundX(funnel.spine(0.5F)), EPSILON);
        assertEquals(4.0F, groundX(funnel.spine(1.0F)), EPSILON);
        assertEquals(7.0F, funnel.spine(0.5F).points().get(1).x(), EPSILON, "every point is blended");
        assertEquals(5.5F, funnel.spine(0.5F).baseRadius(), EPSILON, "and so are the radii");
        assertEquals(11.5F, funnel.spine(0.5F).topRadius(), EPSILON);
    }

    @Test
    void aNewKeyframeStartsFromTheShownSpine() {
        ClientFunnel funnel = new ClientFunnel(1, spineAt(0.0F, true));
        funnel.accept(spineAt(10.0F, true), 10);
        tick(funnel, 5);
        FunnelSpine shown = funnel.spine(0.0F);
        assertEquals(5.0F, groundX(shown), EPSILON, "half way to the first keyframe");

        funnel.accept(spineAt(-20.0F, true), 10);

        assertEquals(shown, funnel.spine(0.0F), "no jump: the blend restarts exactly where the funnel was");
        assertEquals(0, funnel.ticksSinceKeyframe());
        assertEquals(5.0F - 2.5F, groundX(funnel.spine(1.0F)), EPSILON, "a tenth of the 25 blocks to the new keyframe");

        tick(funnel, 4);
        assertEquals(5.0F - 10.0F, groundX(funnel.spine(0.0F)), EPSILON);
        tick(funnel, 6);
        assertEquals(-20.0F, groundX(funnel.spine(0.0F)), EPSILON, "after the interval the keyframe is reached");
    }

    @Test
    void aKeyframeThatArrivesAfterTheBlendFinishedStartsFromTheOldKeyframe() {
        ClientFunnel funnel = new ClientFunnel(1, spineAt(0.0F, true));
        FunnelSpine first = spineAt(10.0F, true);
        funnel.accept(first, 10);
        tick(funnel, 10);
        assertSame(first, funnel.spine(0.0F));

        funnel.accept(spineAt(20.0F, true), 10);

        assertEquals(first, funnel.spine(0.0F), "the origin of the new blend is the keyframe that was shown");
        assertEquals(11.0F, groundX(funnel.spine(1.0F)), EPSILON);
    }

    @Test
    void twoKeyframesInOneTickDoNotMoveTheFunnel() {
        ClientFunnel funnel = new ClientFunnel(1, spineAt(0.0F, true));
        funnel.accept(spineAt(10.0F, true), 10);
        tick(funnel, 2);
        FunnelSpine shown = funnel.spine(0.0F);

        funnel.accept(spineAt(40.0F, true), 10);
        funnel.accept(spineAt(-40.0F, true), 10);

        assertEquals(shown, funnel.spine(0.0F));
        assertEquals(2.0F, groundX(funnel.spine(0.0F)), EPSILON);
        assertEquals(2.0F - 4.2F, groundX(funnel.spine(1.0F)), EPSILON, "it heads for the keyframe that came last");
    }

    @Test
    void progressSaturatesAtOne() {
        ClientFunnel funnel = new ClientFunnel(1, spineAt(0.0F, true));
        FunnelSpine target = spineAt(8.0F, true);
        funnel.accept(target, 4);

        tick(funnel, 3);
        assertEquals(6.0F, groundX(funnel.spine(0.0F)), EPSILON);
        assertEquals(7.0F, groundX(funnel.spine(0.5F)), EPSILON);
        assertSame(target, funnel.spine(1.0F), "the end of the last tick of the interval is the keyframe");

        tick(funnel, 1);
        assertSame(target, funnel.spine(0.0F));
        assertSame(target, funnel.spine(1.0F));

        tick(funnel, 500);
        assertSame(target, funnel.spine(0.0F), "a funnel whose keyframes stopped holds the last one");
        assertSame(target, funnel.spine(0.75F));
        assertEquals(504, funnel.ticksSinceKeyframe(), "the tick counter keeps counting past the interval");
    }

    @Test
    void intervalZeroIsTreatedAsOne() {
        ClientFunnel funnel = new ClientFunnel(1, spineAt(0.0F, true));
        FunnelSpine target = spineAt(6.0F, true);

        funnel.accept(target, 0);

        assertEquals(0.0F, groundX(funnel.spine(0.0F)), EPSILON, "still no jump at the start of the tick");
        assertEquals(3.0F, groundX(funnel.spine(0.5F)), EPSILON, "the whole way is covered within one tick");
        assertSame(target, funnel.spine(1.0F));
        funnel.tick();
        assertSame(target, funnel.spine(0.0F));

        FunnelSpine next = spineAt(-6.0F, true);
        funnel.accept(next, -5);
        assertEquals(0.0F, groundX(funnel.spine(0.5F)), EPSILON, "a negative interval is treated as 1 too");
        funnel.tick();
        assertSame(next, funnel.spine(0.0F));
    }

    @Test
    void groundedAndDescentFollowTheKeyframe() {
        ClientFunnel funnel = new ClientFunnel(1, spineAt(0.0F, false));
        funnel.accept(spineAt(0.0F, true), 10);
        tick(funnel, 5);

        FunnelSpine shown = funnel.spine(0.0F);

        assertEquals(0.75F, shown.descent(), EPSILON, "the descent is blended from 0.5 to 1");
        assertTrue(shown.grounded(), "grounded is the keyframe's from the moment it arrives");
    }

    @Test
    void aKeyframeWithAnotherPointCountIsShownAtOnce() {
        ClientFunnel funnel = new ClientFunnel(1, spineAt(0.0F, true));
        FunnelSpine three = new FunnelSpine(List.of(new Vector3f(1.0F, 64.0F, 0.0F), new Vector3f(2.0F, 100.0F, 0.0F),
            new Vector3f(3.0F, 160.0F, 0.0F)), 3.0F, 10.0F, 1.0F, 1.0F, true);

        funnel.accept(three, 10);

        assertSame(three, funnel.spine(0.0F), "spines of different length cannot be blended");
        assertSame(three, funnel.spine(1.0F));
        assertThrows(NullPointerException.class, () -> funnel.accept(null, 10));
    }

    @Test
    void theSpineOfOnePartialTickIsSharedUntilTheNextTick() {
        ClientFunnel funnel = new ClientFunnel(1, spineAt(0.0F, true));
        funnel.accept(spineAt(10.0F, true), 10);
        funnel.tick();

        FunnelSpine once = funnel.spine(1.0F);
        assertSame(once, funnel.spine(1.0F), "many callers in one tick get one spine");
        assertEquals(1.5F, groundX(funnel.spine(0.5F)), EPSILON, "another partial tick is computed afresh");
        assertEquals(2.0F, groundX(funnel.spine(1.0F)), EPSILON);

        funnel.tick();
        assertEquals(3.0F, groundX(funnel.spine(1.0F)), EPSILON, "a tick invalidates the shared spine");
        funnel.accept(spineAt(10.0F, true), 10);
        assertEquals(2.0F + 0.8F, groundX(funnel.spine(1.0F)), EPSILON, "and so does a keyframe");
    }
}
