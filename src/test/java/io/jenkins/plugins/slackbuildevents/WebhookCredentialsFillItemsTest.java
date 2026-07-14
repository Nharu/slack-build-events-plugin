package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

/**
 * The permission gate on {@link WebhookCredentials#fillItems}. Enumeration runs as {@code SYSTEM2}
 * and therefore bypasses per-credential ACLs, which leaves the {@code hasPermission(ADMINISTER)}
 * check as the only access control on this path. These tests pin that gate from three sides: an
 * ADMINISTER holder enumerates every SYSTEM-scoped webhook credential; a plain READ user gets back
 * the current value and nothing else; and a holder of a credentials-domain permission
 * ({@code CredentialsProvider.VIEW} or {@code USE_ITEM}) but not ADMINISTER is refused just the
 * same, so weakening the gate to either permission turns the suite red instead of silently exposing
 * the credential list. The non-admin option count is asserted exactly, so a credential id the test
 * was never told about is caught as well. {@code noSecretAnywhere} separately guards the option
 * name surface, which the option-count assertions cannot see.
 */
public class WebhookCredentialsFillItemsTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.ADMINISTER).everywhere().to("alice");
        // Every non-admin identity also holds READ, so the gate is always exercised by an
        // authenticated user who merely lacks ADMINISTER, never by an anonymous one.
        auth.grant(Jenkins.READ).everywhere().to("bob", "carol", "dave");
        // carol and dave pin the gate at ADMINISTER. VIEW and USE_ITEM do not imply each other, so
        // a gate weakened to either one needs its own identity to be caught.
        auth.grant(CredentialsProvider.VIEW).everywhere().to("carol");
        auth.grant(CredentialsProvider.USE_ITEM).everywhere().to("dave");
        j.jenkins.setAuthorizationStrategy(auth);
    }

    @Test
    public void adminEnumeratesSystemScopedCredentialsWithEmptyAndCurrent() throws Exception {
        seedCredential("wh-a", "http://secret.example/ADMINLEAKTOKENA");
        seedCredential("wh-b", "http://secret.example/ADMINLEAKTOKENB");

        // "cur-distinct" is not a seeded id, so the empty option can only come from
        // includeEmptyValue() and the current option only from includeCurrentValue().
        ListBoxModel model = fillAs("alice", "cur-distinct");

        assertEquals("admin model holds the empty option, the current value and both credential ids", 4, model.size());
        assertTrue("admin enumerates wh-a", hasOptionValue(model, "wh-a"));
        assertTrue("admin enumerates wh-b", hasOptionValue(model, "wh-b"));
        assertTrue("admin gets the empty option", hasOptionValue(model, ""));
        assertTrue("admin gets the current value option", hasOptionValue(model, "cur-distinct"));
        assertTrue("no secret in any option name or value", noSecretAnywhere(model, "ADMINLEAKTOKENA"));
        assertTrue("no secret in any option name or value", noSecretAnywhere(model, "ADMINLEAKTOKENB"));
    }

    @Test
    public void adminWithNullCurrentValueStillEnumeratesAndKeepsOneEmptyOption() throws Exception {
        seedCredential("wh-a", "http://secret.example/ADMINLEAKTOKENA");

        ListBoxModel model = fillAs("alice", null);

        // A null current value adds no current option and does not duplicate the empty one:
        // includeCurrentValue("") delegates to includeEmptyValue(), which returns early.
        assertEquals("a null current value adds no current option and no duplicate empty option", 2, model.size());
        assertTrue("admin still gets the empty option", hasOptionValue(model, ""));
        assertTrue("admin still enumerates wh-a", hasOptionValue(model, "wh-a"));
    }

    @Test
    public void nonAdminReturnsOnlyCurrentValueAndDoesNotEnumerate() throws Exception {
        seedCredential("wh-secret", "http://secret.example/NONADMINLEAKTOKEN");

        ListBoxModel model = fillAs("bob", "cur-distinct");

        // Exactly one option: no credential id may surface, however many are seeded.
        assertEquals("non-admin gets the current value and nothing else", 1, model.size());
        assertTrue("the current value passes through", hasOptionValue(model, "cur-distinct"));
        assertTrue("no secret in any option name or value", noSecretAnywhere(model, "NONADMINLEAKTOKEN"));

        // The gate ignores currentValue, so the null path must stay closed too, and the coalescing
        // must actually produce the empty option rather than an empty model.
        ListBoxModel m0 = fillAs("bob", null);

        assertEquals("a null current value still yields exactly one option", 1, m0.size());
        assertTrue("a null current value collapses to the empty option", hasOptionValue(m0, ""));
    }

    @Test
    public void credentialsPermissionsAloneDoNotEnumerate() throws Exception {
        seedCredential("wh-secret", "http://secret.example/BANDLEAKTOKEN");

        ListBoxModel viewOnly = fillAs("carol", "cur-distinct");

        assertEquals("CredentialsProvider.VIEW alone gets the current value and nothing else", 1, viewOnly.size());
        assertTrue("the current value passes through", hasOptionValue(viewOnly, "cur-distinct"));
        assertTrue("no secret in any option name or value", noSecretAnywhere(viewOnly, "BANDLEAKTOKEN"));

        ListBoxModel useItemOnly = fillAs("dave", "cur-distinct");

        assertEquals(
                "CredentialsProvider.USE_ITEM alone gets the current value and nothing else", 1, useItemOnly.size());
        assertTrue("the current value passes through", hasOptionValue(useItemOnly, "cur-distinct"));
        assertTrue("no secret in any option name or value", noSecretAnywhere(useItemOnly, "BANDLEAKTOKEN"));
    }

    private ListBoxModel fillAs(String username, String currentValue) {
        try (ACLContext ctx = ACL.as2(User.getById(username, true).impersonate2())) {
            return WebhookCredentials.fillItems(currentValue);
        }
    }

    private static boolean hasOptionValue(ListBoxModel model, String value) {
        return model.stream().anyMatch(o -> value.equals(o.value));
    }

    /** Guards the {@code Option.name} surface, which the option-count assertions cannot see. */
    private static boolean noSecretAnywhere(ListBoxModel model, String token) {
        return model.stream()
                .allMatch(o ->
                        (o.name == null || !o.name.contains(token)) && (o.value == null || !o.value.contains(token)));
    }

    /**
     * {@code SystemCredentialsProvider.save()} requires {@link Jenkins#ADMINISTER}, and the thread
     * running a test body after {@code @Before} is anonymous, so seeding must happen inside a
     * {@code SYSTEM2} privileged context.
     */
    private static void seedCredential(String id, String url) throws Exception {
        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
            SlackTestHelpers.addWebhookCredential(id, url);
        }
    }
}
