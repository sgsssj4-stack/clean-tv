package com.poppy.scoreboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class SessionsTest {
    private static final long M = 60_000L;

    private static Sessions.Event e(String pkg, int type, long minute) {
        return new Sessions.Event(pkg, type, minute * M);
    }

    private static Sessions.Event ev(String pkg, int type, long ms) {
        return new Sessions.Event(pkg, type, ms);
    }

    @Test
    public void anotherAppComingForwardEndsTheSitting() {
        Map<String, List<Sessions.Session>> s = Sessions.build(Arrays.asList(
                e("netflix", Sessions.RESUMED, 0), e("netflix", Sessions.PAUSED, 50),
                e("home", Sessions.RESUMED, 50), e("youtube", Sessions.RESUMED, 52),
                e("youtube", Sessions.PAUSED, 80), e("youtube", Sessions.STOPPED, 80)), 200 * M);
        assertEquals(1, s.get("netflix").size());
        assertEquals(50 * M, s.get("netflix").get(0).length());
        assertEquals(2 * M, s.get("home").get(0).length());
        assertEquals(28 * M, s.get("youtube").get(0).length());
    }

    @Test
    public void aQuickReturnToTheSameAppIsOneSittingALongOneIsTwo() {
        Map<String, List<Sessions.Session>> s = Sessions.build(Arrays.asList(
                e("netflix", Sessions.RESUMED, 0), e("netflix", Sessions.PAUSED, 10),
                e("netflix", Sessions.RESUMED, 10), e("netflix", Sessions.PAUSED, 30),
                e("netflix", Sessions.RESUMED, 95), e("netflix", Sessions.PAUSED, 100)), 200 * M);
        List<Sessions.Session> n = s.get("netflix");
        assertEquals(2, n.size());
        assertEquals(30 * M, n.get(0).length());
        assertEquals(95 * M, n.get(1).start);
    }

    @Test
    public void screenOffClosesAndTinyPassesAreDropped() {
        Map<String, List<Sessions.Session>> s = Sessions.build(Arrays.asList(
                new Sessions.Event("flash", Sessions.RESUMED, 0), new Sessions.Event("flash", Sessions.PAUSED, 2000),
                e("zee5", Sessions.RESUMED, 1), e("x", Sessions.SCREEN_OFF, 41),
                e("zee5", Sessions.PAUSED, 42)), 300 * M);
        assertNull(s.get("flash"));
        assertEquals(40 * M, s.get("zee5").get(0).length());
        assertEquals(1, s.get("zee5").size());
    }

    @Test
    public void aSplashScreensLateStopDoesNotEndTheSitting() {
        Map<String, List<Sessions.Session>> s = Sessions.build(Arrays.asList(
                ev("yt", Sessions.RESUMED, 0), ev("yt", Sessions.PAUSED, 1), ev("yt", Sessions.RESUMED, 2),
                ev("yt", Sessions.STOPPED, 30), ev("yt", Sessions.PAUSED, 15_002), ev("home", Sessions.RESUMED, 15_002)),
                20_000);
        assertEquals(1, s.get("yt").size());
        assertEquals(15_002, s.get("yt").get(0).length());
    }

    @Test
    public void anOpenSittingRunsUntilNow() {
        Map<String, List<Sessions.Session>> s = Sessions.build(Arrays.asList(e("jio", Sessions.RESUMED, 10)), 70 * M);
        assertEquals(60 * M, s.get("jio").get(0).length());
    }

    @Test
    public void wordsForTimes() {
        assertEquals("0m", ScoreboardActivity.duration(0));
        assertEquals("<1m", ScoreboardActivity.duration(30_000));
        assertEquals("45m", ScoreboardActivity.duration(45 * M));
        assertEquals("2h", ScoreboardActivity.duration(120 * M));
        assertEquals("84h 12m", ScoreboardActivity.duration((84 * 60 + 12) * M));
        assertEquals("25%", ScoreboardActivity.percent(1, 4));
    }
}
