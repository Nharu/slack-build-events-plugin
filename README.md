# Slack Templated Notifier (`slack-build-events`)

A standalone Jenkins plugin that sends Slack notifications on build **start** and
**completion** from a controller-global listener — without touching any
`Jenkinsfile`. An admin defines an opt-in allowlist of job full-name regex rules;
matching builds fire a fully templated Slack message via an Incoming Webhook.

It is independent of (and does not depend on) the upstream `slack-plugin`.

## Why a plugin (vs. an init-groovy listener)

A `@Extension RunListener` is auto-registered **once per plugin** by Jenkins, so
the idempotent-registration / de-duplication problems of a boot-script listener
disappear entirely — no registration guard code is needed.

## Features

- **Controller-global `RunListener`** — no per-job or per-`Jenkinsfile` changes.
- **Opt-in allowlist**, job full-name regex, **first-match-wins** ordered rules.
- **Per-event toggles**: start + per-result (SUCCESS/FAILURE/UNSTABLE/ABORTED/NOT_BUILT).
- **Fully templated** messages via [token-macro](https://plugins.jenkins.io/token-macro/),
  plus five built-in `${SLACK_*}` macros (duration, deployer, git commit/branch, build URL).
- **Asynchronous, best-effort** dispatch — Slack latency/outages never block builds.
- **Non-blocking 429 retry** honoring `Retry-After`.
- **JCasC-compatible** — every field round-trips; secrets are referenced by credential id only.
- **Opt-in SSRF / ReDoS hardening** — a send-time webhook host allowlist + https-only toggle,
  and a backtracking step budget on rule-pattern matching (both off by default).
- Built-in default templates are **language-neutral**; organization-specific wording
  is injected through configuration (global/rule template overrides), never the plugin source.

## Configuration

Configure under **Manage Jenkins → System → Slack Templated Notifier** (admin only).

- **Default channel / default webhook credential** — global fallbacks.
- **Rules** — each rule has a `jobNamePattern` (regex, full-match against
  `job.getFullName()`), per-event toggles, and optional channel / webhook
  credential / per-event template overrides.
- **Max retries on 429** — `0`–`5` (default `1`).
- **Webhook host allowlist** — optional; when set, a webhook is only sent if its host
  suffix-matches an entry. Empty (default) means unrestricted.
- **Require HTTPS webhook URLs** — optional; when on, plain-`http` webhooks are blocked
  at send time. Off (default) so internal HTTP receivers keep working.

> **Note — first-match is job-level, not event-level:** the first rule whose regex matches a job
> governs that job entirely. Only that rule's per-event toggles are consulted; once a job matches a
> rule, later rules are never evaluated for it — even for events the matched rule has turned off.
> Order broad rules after narrow ones if a narrow rule needs its own event set.

The webhook URL is a **Secret Text** (`StringCredentials`, `SYSTEM` scope) credential;
the plugin looks it up at fire time, so rotation needs no restart.

### Pattern matching note

The pattern is **full-matched** against the job's full name, so folder separators
(`/`) must be included explicitly. For a job `smegene/server`, use `smegene/.*`
(not `smegene`, which would not match and would silently fire nothing).

### Channel routing note

A modern Slack-app Incoming Webhook is bound to a single channel and ignores the
payload `channel` field. To deliver to different channels, define a separate
webhook per channel and select it per rule via the webhook credential override
(channel ↔ webhook is 1:1). The payload `channel` / global default channel is kept
only as a best-effort hint for legacy custom-integration webhooks.

### Security hardening (opt-in)

Both webhook configuration and rule patterns require administrator rights, so these controls
are **defense-in-depth against an already-trusted admin**, not a boundary against untrusted
users. All are off by default, preserving historical behavior (including self-hosted
Slack-compatible receivers over internal HTTP).

- **Webhook host allowlist** — enforced at send time. A host is allowed if it equals an entry
  or ends with `.` + the entry, so `slack.com` matches `hooks.slack.com` but not
  `evilslack.com`. Comparison is case-insensitive and IDN-normalized; entries are stored
  verbatim. Combined with **Require HTTPS** using AND.
- **ReDoS step budget** — each rule-pattern match is bounded by a backtracking step budget; a
  pathological pattern is aborted (treated as no-match) instead of pinning the notification
  thread, and later rules are still evaluated. The pattern editor also runs a config-time
  self-check that warns on catastrophic patterns.

Known limitations (accepted residual risk at this threat level):

- Allowlist IP-literal entries are plain suffix matches — **no CIDR** (`10.0.0.0` ≠ `10.0.0.1`);
  IPv6 literals must be entered in the bracketed form `URI.getHost()` returns (e.g. `[::1]`).
- The allowlist checks the resolved host name; a name re-resolving to an internal IP after the
  check (DNS rebinding) is not defended against.

### JCasC sample (`jenkins.yaml`)

```yaml
unclassified:
  slackTemplatedNotifier:
    defaultChannel: "#jenkins"
    defaultWebhookCredentialId: "slack-webhook-url"
    httpsOnly: true
    webhookHostAllowlist:
      - "hooks.slack.com"
    rules:
      - jobNamePattern: "dev/server.*"
        notifyStart: true
        notifySuccess: true
        notifyFailure: true
credentials:
  system:
    domainCredentials:
      - credentials:
          - string:
              scope: SYSTEM
              id: "slack-webhook-url"
              secret: "${SLACK_WEBHOOK_URL}"
              description: "Slack incoming webhook"
```

## Built-in `${SLACK_*}` token macros

| Macro                | Value                                                                 |
| -------------------- | --------------------------------------------------------------------- |
| `${SLACK_DURATION}`  | Build duration in a compact, language-neutral format (e.g. `2h 5m`), computed live if not yet recorded. |
| `${SLACK_DEPLOYER}`  | Triggering user id → upstream cause → `Jenkins`.                       |
| `${SLACK_GIT_COMMIT}`| Short SHA from env → git `BuildData` → SCM revision → empty.           |
| `${SLACK_GIT_BRANCH}`| Branch from env → git `BuildData` → SCM revision → `N/A`.              |
| `${SLACK_BUILD_URL}` | Absolute build URL, always normalized to a trailing `/`.              |

All five are Run-attached, read-only, and side-effect-free, so they also work on
pod/ephemeral agents where the workspace is gone by completion time.

> **Note — prefer the `SLACK_*` macros for SCM-derived data.** The SCM-derived macros
> (`${SLACK_GIT_BRANCH}`, `${SLACK_GIT_COMMIT}`, `${SLACK_DEPLOYER}`) escape Slack mrkdwn
> control characters in their values, so an attacker-influenced branch name cannot inject
> `<url|label>` link markup. A raw `${ENV,var="GIT_BRANCH"}` (or a build parameter) placed
> directly into a custom template bypasses that escaping — use the provided `SLACK_*` macros
> for SCM-derived values rather than raw env references.

## Versioning

Internal pinned releases use a strictly monotonic `0.x` line (`0.1.0 → 0.2.0 → …`;
never `-SNAPSHOT` installs). The first public release will be `1.0.0`, which is
greater than every internal `0.x`, so it supersedes a pinned internal install in
place (identifier/`groupId`/shortname are immutable).

## Building

```sh
mvn clean verify   # requires Maven 3.9+ and JDK 17
```

## License

MIT — see [LICENSE](LICENSE).
