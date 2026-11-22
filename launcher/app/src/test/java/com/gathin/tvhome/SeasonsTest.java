package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.Test;

public class SeasonsTest {

    private static String on(String day) {
        return Seasons.collectionOn(Seasons.defaults(), LocalDate.parse(day));
    }

    @Test
    public void defaultScheduleMatchesTheAgreedDates() {
        assertEquals("everyday", on("2026-12-22"));
        assertEquals("christmas", on("2026-12-23"));
        assertEquals("christmas", on("2026-12-30"));
        assertEquals("new-year", on("2026-12-31"));
        assertEquals("new-year", on("2027-01-07"));
        assertEquals("everyday", on("2027-01-08"));
        assertEquals("holi", on("2027-03-22"));
        assertEquals("dussehra", on("2027-10-09"));
        assertEquals("diwali", on("2027-10-29"));
    }

    @Test
    public void shippedSeasonsJsonMatchesTheDefaults() {
        String json = "[{\"name\":\"christmas\",\"from\":\"2026-12-23\",\"to\":\"2026-12-30\",\"collection\":\"christmas\","
                + "\"greeting\":\"Merry Christmas\",\"accent\":\"#E0463C\",\"rotation_minutes\":10,\"order\":\"sequential\"},"
                + "{\"name\":\"new-year\",\"from\":\"2026-12-31\",\"to\":\"2027-01-07\",\"collection\":\"new-year\"},"
                + "{\"name\":\"holi\",\"from\":\"2027-03-19\",\"to\":\"2027-03-24\",\"collection\":\"holi\"},"
                + "{\"name\":\"dussehra\",\"from\":\"2027-10-06\",\"to\":\"2027-10-11\",\"collection\":\"dussehra\"},"
                + "{\"name\":\"diwali\",\"from\":\"2027-10-25\",\"to\":\"2027-11-02\",\"collection\":\"diwali\"}]";
        List<Seasons.Season> parsed = Seasons.parse(json);
        assertEquals(5, parsed.size());
        for (String day : new String[]{"2026-12-22", "2026-12-23", "2026-12-30", "2026-12-31", "2027-01-07",
                "2027-01-08", "2027-03-22", "2027-10-09", "2027-10-29"}) {
            assertEquals(day, on(day), Seasons.collectionOn(parsed, LocalDate.parse(day)));
        }
        Seasons.Season christmas = Seasons.activeOn(parsed, LocalDate.parse("2026-12-25"));
        assertEquals("Merry Christmas", christmas.greeting);
        assertEquals(0xFFE0463C, christmas.accent);
        assertEquals(10, christmas.rotationMinutes);
        assertEquals("sequential", christmas.order);
    }

    @Test
    public void badEntriesAreSkippedNotGuessed() {
        List<Seasons.Season> parsed = Seasons.parse("[{\"name\":\"x\",\"from\":\"2026-13-01\",\"to\":\"2026-12-30\"},"
                + "{\"name\":\"rev\",\"from\":\"2026-12-30\",\"to\":\"2026-12-01\"},"
                + "{\"name\":\"../up\",\"from\":\"2026-12-01\",\"to\":\"2026-12-30\"},"
                + "{\"name\":\"ok\",\"from\":\"2026-12-01\",\"to\":\"2026-12-02\",\"order\":\"backwards\"}]");
        assertEquals(1, parsed.size());
        assertEquals("ok", parsed.get(0).collection);
        assertEquals("", parsed.get(0).order);
        assertTrue(Seasons.parse("not json").isEmpty());
        assertTrue(Seasons.parse(null).isEmpty());
    }

    @Test
    public void firstMatchingWindowWinsAndEverydayWindowIsNoSeason() {
        List<Seasons.Season> parsed = Seasons.parse("[{\"name\":\"quiet\",\"from\":\"2026-12-24\",\"to\":\"2026-12-24\","
                + "\"collection\":\"everyday\"},{\"name\":\"christmas\",\"from\":\"2026-12-23\",\"to\":\"2026-12-30\"}]");
        assertNull(Seasons.activeOn(parsed, LocalDate.parse("2026-12-24")));
        assertEquals("christmas", Seasons.collectionOn(parsed, LocalDate.parse("2026-12-25")));
        assertNull(Seasons.activeOn(null, LocalDate.parse("2026-12-25")));
    }

    @Test
    public void onePictureCanBeTaggedIntoSeveralCollections() throws Exception {
        java.util.Map<String, List<String>> tags = Seasons.parseTags("{\"wallpapers/01_ayodhya_twilight.jpg\":"
                + "[\"everyday\",\"Diwali\",\"dussehra\"],\"wallpapers/02_steps.jpg\":[\"diwali\",\"dussehra\"],"
                + "\"../etc/passwd.jpg\":[\"diwali\"],\"wallpapers/x.gif\":[\"diwali\"]}");
        assertEquals(java.util.Arrays.asList("wallpapers/01_ayodhya_twilight.jpg"), tags.get("everyday"));
        assertEquals(java.util.Arrays.asList("wallpapers/01_ayodhya_twilight.jpg", "wallpapers/02_steps.jpg"),
                tags.get("diwali"));
        assertEquals(2, tags.get("dussehra").size());
        assertTrue(Seasons.parseTags("[]").isEmpty());

        java.io.File dir = java.nio.file.Files.createTempDirectory("seasons").toFile();
        java.io.File wallpapers = new java.io.File(dir, "wallpapers");
        java.io.File diwali = new java.io.File(dir, "collections/diwali");
        assertTrue(wallpapers.mkdirs() && diwali.mkdirs());
        java.nio.file.Files.write(new java.io.File(wallpapers, "01_ayodhya_twilight.jpg").toPath(), new byte[]{1});
        java.nio.file.Files.write(new java.io.File(diwali, "00_lamps.jpg").toPath(), new byte[]{1});
        java.nio.file.Files.write(new java.io.File(dir, "collections.json").toPath(),
                "{\"wallpapers/01_ayodhya_twilight.jpg\":[\"everyday\",\"diwali\"],\"wallpapers/gone.jpg\":[\"diwali\"]}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<java.io.File> d = Seasons.images(dir, "diwali");
        assertEquals(2, d.size());
        assertEquals("00_lamps.jpg", d.get(0).getName());
        assertEquals("01_ayodhya_twilight.jpg", d.get(1).getName());
        assertEquals(1, Seasons.images(dir, "everyday").size());
        assertTrue(Seasons.images(dir, "holi").isEmpty());
        assertTrue(Seasons.enabled(dir));
    }
}
