package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The failedMacroHint hardening: {@code parseFailedMacroHint} only accepts an allowlisted identifier
 * taken from token-macro's canonical {@code Unrecognized macro 'NAME'} prefix (Layer a), and
 * {@code renderMessage} scrubs the whole assembled headline once so a crafted hint cannot forge a log
 * line even if the shape check were bypassed (Layer b). Together they close the log-forging vector a
 * registered macro could otherwise open by echoing untrusted input into its exception message.
 */
public class HintForgingWitnessTest {

    private static Throwable withMessage(String message) {
        return new RuntimeException(message);
    }

    @Test
    public void acceptsCanonicalIdentifierName() {
        assertEquals(
                "GOOD_NAME",
                NotificationDispatcher.parseFailedMacroHint(
                        withMessage("Unrecognized macro 'GOOD_NAME' in 'x=${GOOD_NAME}'")));
        // A digit-first identifier is still allowlisted ([A-Za-z0-9_]).
        assertEquals(
                "1st_TOKEN",
                NotificationDispatcher.parseFailedMacroHint(withMessage("Unrecognized macro '1st_TOKEN' in 'x'")));
    }

    @Test
    public void rejectsNewlineInName() {
        assertNull(NotificationDispatcher.parseFailedMacroHint(
                withMessage("Unrecognized macro 'BAD\nFORGED-LINE' in 'x'")));
    }

    @Test
    public void rejectsNonCanonicalMessage() {
        // Not the canonical prefix (e.g. a registered macro's own exception message) → no hint.
        assertNull(NotificationDispatcher.parseFailedMacroHint(withMessage("Error replacing 'NAME' - boom")));
        assertNull(NotificationDispatcher.parseFailedMacroHint(withMessage("some other 'NAME' text")));
    }

    @Test
    public void rejectsWhitespaceAndOverLengthName() {
        assertNull(NotificationDispatcher.parseFailedMacroHint(
                withMessage("Unrecognized macro 'has space' in 'x'")));
        String tooLong = "A".repeat(65);
        assertNull(NotificationDispatcher.parseFailedMacroHint(
                withMessage("Unrecognized macro '" + tooLong + "' in 'x'")));
    }

    @Test
    public void craftedHintCannotForgeLogLineInHeadline() {
        // Bypass Layer (a) by injecting a crafted hint straight into the event; Layer (b)'s whole-headline
        // scrub must still leave no line-breaking characters in the headline.
        RenderFailureEvent event = new RenderFailureEvent(
                RenderCategory.DEGRADED,
                new RuntimeException("cause"),
                new RuntimeException("root"),
                "job",
                1,
                EventType.SUCCESS,
                "wh",
                "template",
                "EVIL\r\nFORGED-LINE");
        FailureMessage message = NotificationDispatcher.renderMessage(event);
        assertFalse("no newline in headline", message.headline.contains("\n"));
        assertFalse("no carriage return in headline", message.headline.contains("\r"));
    }
}
