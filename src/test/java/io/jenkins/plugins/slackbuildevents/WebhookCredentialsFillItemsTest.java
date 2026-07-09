package io.jenkins.plugins.slackbuildevents;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

public class WebhookCredentialsFillItemsTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm()); // realm 먼저
        MockAuthorizationStrategy auth = new MockAuthorizationStrategy();
        auth.grant(Jenkins.ADMINISTER).everywhere().to("alice"); // admin
        auth.grant(Jenkins.READ).everywhere().to("bob"); // non-admin (ADMINISTER 없음 보장)
        j.jenkins.setAuthorizationStrategy(auth); // strategy 나중
    }

    private ListBoxModel fillAs(String username, String currentValue) {
        try (ACLContext ctx = ACL.as2(User.getById(username, true).impersonate2())) {
            return WebhookCredentials.fillItems(currentValue);
        }
    }

    private static boolean hasOptionValue(ListBoxModel model, String value) {
        return model.stream().anyMatch(o -> value.equals(o.value));
    }

    private static boolean noSecretAnywhere(ListBoxModel model, String token) {
        return model.stream()
                .allMatch(o -> (o.name == null || !o.name.contains(token)) && (o.value == null || !o.value.contains(token)));
    }

    /**
     * SystemCredentialsProvider.save()가 ADMINISTER를 요구하는데 @Before 이후 본문 스레드는
     * ANONYMOUS이므로, seed는 SYSTEM2 특권 컨텍스트 안에서 수행해야 한다.
     */
    private static void seedCredential(String id, String url) throws Exception {
        try (ACLContext ctx = ACL.as2(ACL.SYSTEM2)) {
            SlackTestHelpers.addWebhookCredential(id, url);
        }
    }

    @Test
    public void adminEnumeratesSystemScopedCredentialsWithEmptyAndCurrent() throws Exception {
        seedCredential("wh-a", "http://secret.example/ADMINLEAKTOKENA");
        seedCredential("wh-b", "http://secret.example/ADMINLEAKTOKENB");
        ListBoxModel model = fillAs("alice", "wh-current"); // wh-current: 크리덴셜 아닌 distinct id
        assertTrue(hasOptionValue(model, "wh-a")); // A1
        assertTrue(hasOptionValue(model, "wh-b")); // A1
        assertTrue(noSecretAnywhere(model, "ADMINLEAKTOKENA")); // A2
        assertTrue(noSecretAnywhere(model, "ADMINLEAKTOKENB")); // A2
        assertTrue(hasOptionValue(model, "")); // empty-option shape
        assertTrue(hasOptionValue(model, "wh-current")); // current-value shape
    }

    @Test
    public void nonAdminReturnsOnlyCurrentValueAndDoesNotEnumerate() throws Exception {
        seedCredential("wh-secret", "http://secret.example/NONADMINLEAKTOKEN");
        ListBoxModel model = fillAs("bob", "cur-distinct"); // cur-distinct != wh-secret, 크리덴셜 아님
        assertFalse(hasOptionValue(model, "wh-secret")); // B1 (anti-leak)
        assertTrue(hasOptionValue(model, "cur-distinct")); // B2 (current pass-through)
        assertTrue(noSecretAnywhere(model, "NONADMINLEAKTOKEN")); // B3

        ListBoxModel m0 = fillAs("bob", null); // B4 null 서브체크(null→"" coalescing)
        assertFalse(hasOptionValue(m0, "wh-secret")); // B4a: null 경로도 유출·열거 없음
    }
}
