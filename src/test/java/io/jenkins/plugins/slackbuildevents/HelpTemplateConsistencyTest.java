package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/**
 * Drift guard for the 12 template help files. Everything from the first security paragraph onward —
 * the two escaping-guidance paragraphs plus the lint-limitation note — must be byte-identical across
 * all of them, so the limitation note cannot silently drift out of sync. Only the event-specific
 * intro line above the first {@code <p>} may differ.
 */
public class HelpTemplateConsistencyTest {

    private static final String GLOBAL = "/io/jenkins/plugins/slackbuildevents/SlackNotifierGlobalConfig/";
    private static final String RULE = "/io/jenkins/plugins/slackbuildevents/NotificationRule/";

    private static final List<String> HELP_FILES = List.of(
            GLOBAL + "help-defaultStartTemplate.html",
            GLOBAL + "help-defaultSuccessTemplate.html",
            GLOBAL + "help-defaultFailureTemplate.html",
            GLOBAL + "help-defaultUnstableTemplate.html",
            GLOBAL + "help-defaultAbortedTemplate.html",
            GLOBAL + "help-defaultNotBuiltTemplate.html",
            RULE + "help-startTemplate.html",
            RULE + "help-successTemplate.html",
            RULE + "help-failureTemplate.html",
            RULE + "help-unstableTemplate.html",
            RULE + "help-abortedTemplate.html",
            RULE + "help-notBuiltTemplate.html");

    @Test
    public void sharedSecurityRegionIsByteIdenticalAcrossAllTemplateHelpFiles() throws Exception {
        String reference = null;
        String referenceFile = null;
        for (String path : HELP_FILES) {
            String shared = sharedRegion(read(path), path);
            if (reference == null) {
                reference = shared;
                referenceFile = path;
            } else {
                assertEquals(
                        "help " + path + " shared region drifted from " + referenceFile, reference, shared);
            }
        }
        assertNotNull(reference);
        // Sanity: the shared region really is the security guidance plus the lint-limitation note.
        assertTrue("shared region should mention the ${SLACK_*} macros", reference.contains("${SLACK_*}"));
        assertTrue("shared region should contain the lint-limitation note", reference.contains("only checks the"));
    }

    /** The region from the first {@code <p>} to end — the shared security guidance + limitation note. */
    private static String sharedRegion(String html, String path) {
        int idx = html.indexOf("<p>");
        assertTrue("help " + path + " must contain a shared <p> block", idx >= 0);
        return html.substring(idx);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = HelpTemplateConsistencyTest.class.getResourceAsStream(resource)) {
            assertNotNull("help resource missing on classpath: " + resource, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
