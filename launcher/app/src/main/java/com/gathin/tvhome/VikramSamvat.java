package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.ZoneId;

/** Vikram Samvat calendar date formatter with 1-15 Tithi & Krishna / Shukla Paksha. */
final class VikramSamvat {

    private static final String[] M = {
            "CHAITRA", "VAISĀKHA", "JYESHTHA", "ĀSHĀDHA",
            "SHRĀVANA", "BHĀDRAPADA", "ĀSHVINA", "KĀRTIKA",
            "MĀRGASHĪRSHA", "PAUSHA", "MĀGHA", "PHĀLGUNA"
    };
    private static final long EPOCH = 1730465220L;
    private static final double SYN = 29.53058867 * 86400.0;
    private static final int[] CUTS = {14, 13, 14, 14, 15, 15, 16, 17, 17, 17, 16, 16};
    private static final int[] MM = {9, 10, 11, 0, 1, 2, 3, 4, 5, 6, 7, 8};

    private VikramSamvat() {}

    static String today() {
        LocalDate d = LocalDate.now();
        long sec = d.atStartOfDay(ZoneId.systemDefault()).plusHours(12).toEpochSecond();
        double ph = ((sec - EPOCH) % SYN + SYN) % SYN;
        boolean shukla = ph < SYN * 0.5;
        double half = SYN * 0.5;
        int t = (int) ((shukla ? ph : ph - half) / (half / 15.0)) + 1;
        t = Math.max(1, Math.min(15, t));

        int m = d.getMonthValue() - 1;
        int day = d.getDayOfMonth();
        int vm = (day >= CUTS[m]) ? (MM[m] + 1) % 12 : MM[m];
        int vy = (m > 2 || (m == 2 && day >= 14)) ? d.getYear() + 57 : d.getYear() + 56;

        return t + (shukla ? " SHUKLA PAKSHA · " : " KRISHNA PAKSHA · ") + M[vm] + " · VIKRAM SAMVAT " + vy;
    }
}
