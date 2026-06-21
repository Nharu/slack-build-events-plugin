package io.jenkins.plugins.slackbuildevents;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit coverage for {@link SlackText#escape} — the mrkdwn control-character escape. */
public class SlackTextTest {

    @Test
    public void escapesTheThreeControlCharsAmpersandFirst() {
        // '&' must be escaped first, otherwise the '&' in the &lt;/&gt; entities is double-escaped.
        assertEquals("a&amp;b&lt;c&gt;d", SlackText.escape("a&b<c>d"));
    }

    @Test
    public void neutralizesLinkMarkupInjection() {
        String escaped = SlackText.escape("<https://evil|click>");
        assertThat(escaped, containsString("&lt;https://evil|click&gt;"));
        assertThat(escaped, not(containsString("<")));
        assertThat(escaped, not(containsString(">")));
    }

    @Test
    public void leavesOrdinaryValuesUnchanged() {
        assertEquals("origin/main", SlackText.escape("origin/main"));
        assertEquals("N/A", SlackText.escape("N/A"));
    }
}
