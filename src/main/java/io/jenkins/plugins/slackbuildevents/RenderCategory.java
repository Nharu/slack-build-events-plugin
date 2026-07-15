package io.jenkins.plugins.slackbuildevents;

/**
 * Outcome of the two-pass render contract. Always encodes which of the four dispositions
 * applies, so the dispatcher can branch on send-vs-skip and signal severity without inspecting
 * exception types.
 *
 * <ul>
 *   <li>{@code CLEAN} — strict pass succeeded; send the fully rendered body.
 *   <li>{@code DEGRADED} — strict pass failed, lenient pass succeeded; send a partially rendered
 *       body where only the failed tokens survive as literals. Lower-severity signal.
 *   <li>{@code FALLBACK} — both passes failed; send a minimal, macro-independent marked message
 *       (never the raw template). Higher-severity signal.
 *   <li>{@code ABORTED} — an interrupt surfaced during render; send nothing. On a running pool this
 *       raises a rate-limited RENDER_ABORTED WARNING; while the pool is shutting down it stays silent.
 * </ul>
 */
enum RenderCategory {
    CLEAN,
    DEGRADED,
    FALLBACK,
    ABORTED
}
