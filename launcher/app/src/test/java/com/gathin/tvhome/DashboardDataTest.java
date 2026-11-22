package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class DashboardDataTest {
    private static final String JSON = "[{\"title\":\"Panchayat\",\"package\":\"com.amazon.amazonvideo.livingroom\","
            + "\"poster\":\"panchayat.jpg\",\"rating\":\"8.9\",\"deeplink\":\"https://www.primevideo.com/detail/x\"},"
            + "{\"title\":\"Kota Factory\",\"package\":\"com.netflix.ninja\",\"poster\":\"../../etc/passwd\"},"
            + "{\"title\":\"\",\"package\":\"com.netflix.ninja\"},"
            + "{\"title\":\"Bad package\",\"package\":\"not a package\"},"
            + "{\"title\":\"Local file\",\"package\":\"com.example.app\",\"deeplink\":\"file:///data/x\"},"
            + "{\"title\":\"Sacred Games\",\"package\":\"com.netflix.ninja\",\"poster\":\"sacred.webp\"}]";

    @Test
    public void parseKeepsGoodEntriesAndCleansTheRest() {
        List<DashboardData.Title> t = DashboardData.parse(JSON);
        assertEquals(4, t.size());
        assertEquals("Panchayat", t.get(0).title);
        assertEquals("panchayat.jpg", t.get(0).poster);
        assertEquals("8.9", t.get(0).rating);
        assertEquals("", t.get(1).poster);          // a path is not a poster name
        assertEquals("", t.get(2).deeplink);        // file: links are dropped
        assertEquals("com.netflix.ninja|Sacred Games", t.get(3).key());
        assertTrue(DashboardData.parse("nope").isEmpty());
        assertTrue(DashboardData.parse(null).isEmpty());
    }

    @Test
    public void forYouPutsLikedFirstAndDropsHidden() {
        List<DashboardData.Title> t = DashboardData.parse(JSON);
        Map<String, String> r = new HashMap<>();
        r.put(t.get(0).key(), DashboardData.SKIP);
        r.put(t.get(1).key(), DashboardData.HIDE);
        r.put(t.get(3).key(), DashboardData.LIKE);
        List<DashboardData.Title> f = DashboardData.forYou(t, r);
        assertEquals(3, f.size());
        assertEquals("Sacred Games", f.get(0).title);
        assertEquals("Local file", f.get(1).title);
        assertEquals("Panchayat", f.get(2).title);
        assertEquals(1, DashboardData.unrated(t, r).size());
    }

    @Test
    public void ratingsRoundTripThroughTheFile() throws Exception {
        File dir = Files.createTempDirectory("dash").toFile();
        List<DashboardData.Title> t = DashboardData.parse(JSON);
        assertTrue(DashboardData.saveRating(dir, t.get(0), DashboardData.LIKE));
        assertTrue(DashboardData.saveRating(dir, t.get(3), DashboardData.HIDE));
        Map<String, String> r = DashboardData.loadRatings(dir);
        assertEquals(DashboardData.LIKE, r.get(t.get(0).key()));
        assertEquals(DashboardData.HIDE, r.get(t.get(3).key()));
        assertFalse(new File(dir, DashboardData.RATINGS + ".tmp").exists());
        assertTrue(DashboardData.parseRatings("{\"a|b\":\"love\"}").isEmpty());
    }

    @Test
    public void greetingFollowsTheHour() {
        assertEquals("Good night", DashboardData.partOfDay(4));
        assertEquals("Good morning", DashboardData.partOfDay(5));
        assertEquals("Good morning", DashboardData.partOfDay(11));
        assertEquals("Good afternoon", DashboardData.partOfDay(12));
        assertEquals("Good evening", DashboardData.partOfDay(17));
        assertEquals("Good night", DashboardData.partOfDay(21));
    }

    @Test
    public void weatherOlderThanThreeHoursIsOffline() {
        long now = 1_800_000_000_000L;
        assertTrue(DashboardData.weatherFresh(now - 60_000L, now));
        assertTrue(DashboardData.weatherFresh(now - 3 * 3_600_000L, now));
        assertFalse(DashboardData.weatherFresh(now - 3 * 3_600_000L - 1, now));
        assertFalse(DashboardData.weatherFresh(0, now));
        assertFalse(DashboardData.weatherFresh(now + 3_600_000L, now));
    }

    @Test
    public void stackBlurSmoothsAnEdgeAndKeepsFlatColour() {
        int w = 20, h = 4;
        int[] flat = new int[w * h];
        java.util.Arrays.fill(flat, 0xFF336699);
        GlassBlur.blur(flat, w, h, 4);
        for (int p : flat) assertEquals(0xFF336699, p);

        int[] edge = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) edge[y * w + x] = x < w / 2 ? 0xFF000000 : 0xFFFFFFFF;
        GlassBlur.blur(edge, w, h, 3);
        int left = edge[w + 9] & 0xFF, right = edge[w + 10] & 0xFF;
        assertTrue(left > 0 && left < 255);
        assertTrue(right > left && right < 255);
        assertEquals(0, edge[w] & 0xFF);
        assertEquals(255, edge[w + w - 1] & 0xFF);
    }
}
