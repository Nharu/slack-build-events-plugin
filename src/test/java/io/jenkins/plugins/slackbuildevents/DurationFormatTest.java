package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;

import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit coverage for {@link DurationFormat#format} — the language-neutral duration formatter. */
public class DurationFormatTest {

    private Locale savedDefault;

    @Before
    public void saveDefaultLocale() {
        savedDefault = Locale.getDefault();
    }

    @After
    public void restoreDefaultLocale() {
        Locale.setDefault(savedDefault);
    }

    /** Millis input paired with its expected rendering (the boundary table). */
    private static final long[][] TABLE_MILLIS = {
        {0L}, {820L}, {999L}, {1000L}, {1001L}, {3000L}, {59999L}, {60000L}, {303000L},
        {3599999L}, {3600000L}, {3900000L}, {7500000L}, {7503000L}, {86399999L}, {86400000L},
        {93784000L}, {88200000L}, {3456789000L}
    };

    private static final String[] TABLE_EXPECTED = {
        "0s", "820ms", "999ms", "1s", "1s 1ms", "3s", "59s 999ms", "1m", "5m 3s",
        "59m 59s", "1h", "1h 5m", "2h 5m", "2h 5m", "23h 59m", "1d",
        "1d 2h", "1d", "40d"
    };

    @Test
    public void rendersBoundaryTable() {
        for (int i = 0; i < TABLE_MILLIS.length; i++) {
            long millis = TABLE_MILLIS[i][0];
            assertEquals("millis=" + millis, TABLE_EXPECTED[i], DurationFormat.format(millis));
        }
    }

    @Test
    public void negativeMillisClampToZero() {
        assertEquals("0s", DurationFormat.format(-1L));
        assertEquals("0s", DurationFormat.format(-123456L));
    }

    @Test
    public void outputIsIdenticalAcrossLocales() {
        // The formatter must never consult Locale.getDefault(); prove it byte-for-byte across
        // an English, German, Japanese, and Korean default — the async dispatch thread renders
        // under whatever the server JVM's default happens to be.
        Locale[] locales = {Locale.ENGLISH, Locale.GERMAN, Locale.JAPANESE, Locale.KOREAN};
        for (long[] row : TABLE_MILLIS) {
            long millis = row[0];
            Locale.setDefault(Locale.ENGLISH);
            String reference = DurationFormat.format(millis);
            for (Locale locale : locales) {
                Locale.setDefault(locale);
                assertEquals(
                        "millis=" + millis + " locale=" + locale, reference, DurationFormat.format(millis));
            }
        }
    }
}
