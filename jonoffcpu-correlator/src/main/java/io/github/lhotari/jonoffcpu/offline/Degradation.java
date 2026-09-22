// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The ladder a run walks down when what it was asked to retain does not fit.
 *
 * <p>The rule being applied is not "exact or approximate" but "can the pairing be trusted". An
 * integrity failure — a digest that differs, an observation referencing an unannounced stack, a
 * duplicate cookie — means the two files do not describe the same capture, so nothing computed from
 * them means anything, and it still aborts. A volume failure means the answer is expensive, not
 * wrong, and the ladder trades precision or coverage for it and says which.
 *
 * <p>Steps one and two are not choices: streaming and interning are unconditional, and the
 * synthetic quantum is planned before any event is written. What is left is to drop the audit
 * outputs, which cost the most and contribute nothing to the flame graph; to thin the source and
 * reweight, which keeps the whole window at lower precision; and to narrow the window, which keeps
 * full precision over less of it. The first two keep the ordinary output names and exit zero,
 * because a stated estimator over the window that was asked for still answers the question. The
 * third does not, so it takes the INCOMPLETE names and exits two.
 */
final class Degradation {
    enum Policy {
        DEGRADE,
        FAIL,
        TRUNCATE;

        static Policy parse(String value) {
            return switch (value) {
                case "degrade" -> DEGRADE;
                case "fail" -> FAIL;
                case "truncate" -> TRUNCATE;
                default -> throw new IllegalArgumentException("Invalid limit policy: " + value);
            };
        }

        String text() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    record Settings(AuditLevel audit, Thinning thinning, Long narrowedToNanos) {}

    private static final String[] THINNING_LADDER = {"0.5", "0.2", "0.1", "0.05", "0.02", "0.01"};

    private final Policy policy;
    private final long budgetBytes;
    private final RetentionEstimate estimate;
    private final List<JsonObject> steps = new ArrayList<>();
    private final AuditLevel requestedAudit;
    private final Thinning requestedThinning;
    private AuditLevel audit;
    private Thinning thinning;
    private Long narrowedToNanos;
    private int thinningRung = -1;
    private int attempts;

    Degradation(
            Policy policy,
            AuditLevel requestedAudit,
            Thinning requestedThinning,
            long budgetBytes,
            RetentionEstimate estimate) {
        this.policy = policy;
        this.requestedAudit = requestedAudit;
        this.requestedThinning = requestedThinning;
        this.budgetBytes = budgetBytes;
        this.estimate = estimate;
        this.audit = requestedAudit;
        this.thinning = requestedThinning;
        // Choosing at the start is what keeps a restart rare; the watermark only catches a bad estimate.
        if (policy == Policy.DEGRADE && !requestedThinning.active()) {
            String chosen = estimate.thinningFor(budgetBytes);
            if (!chosen.equals("1")) {
                thinning = Thinning.of(chosen, requestedThinning.seed());
                thinningRung = indexOf(chosen);
                record("thin-source", detail -> {
                    detail.addProperty("q", chosen);
                    detail.addProperty("reason", "estimated retention exceeds the budget before decoding");
                    detail.addProperty("estimatedRetainedBytes", Long.toString(estimate.retainedBytes()));
                });
            }
        }
    }

    /**
     * An inert ladder for the library's default output path, which never streams under a watermark and
     * so never advances: its report simply states that degradation was considered and not needed.
     */
    static Degradation none() {
        return new Degradation(
                Policy.DEGRADE, AuditLevel.FULL, Thinning.NONE, Long.MAX_VALUE, new RetentionEstimate(0, 0, 0, 0, 0));
    }

    Settings settings() {
        attempts++;
        return new Settings(audit, thinning, narrowedToNanos);
    }

    /** Takes the next rung, or returns false when the ladder is exhausted or the policy forbids it. */
    boolean advance(RetentionLimitExceeded limit) {
        if (policy == Policy.FAIL) return false;
        if (policy == Policy.DEGRADE && audit != AuditLevel.NONE) {
            // Dropping the audit outputs costs nothing the engine's own retention accounting sees
            // today: they come from a second, post-hoc read that never touches this budget. Spending a
            // whole retry on a step guaranteed to reproduce the identical failure would be a wasted
            // full pass, so every remaining audit level is dropped in this one call instead — each
            // still recorded as its own step — before falling through to a step that can actually help.
            while (audit != AuditLevel.NONE) {
                AuditLevel next = audit == AuditLevel.FULL ? AuditLevel.MATCHES : AuditLevel.NONE;
                AuditLevel from = audit;
                audit = next;
                record("drop-audit-outputs", detail -> {
                    detail.addProperty("from", from.text());
                    detail.addProperty("to", next.text());
                    detail.addProperty("reason", "the audit outputs cost the most and are not in the flame graph");
                });
            }
        }
        if (policy == Policy.DEGRADE && thinningRung + 1 < THINNING_LADDER.length) {
            String next = THINNING_LADDER[++thinningRung];
            thinning = Thinning.of(next, requestedThinning.seed());
            record("thin-source", detail -> {
                detail.addProperty("q", next);
                detail.addProperty("reason", "retained bytes reached the budget during the pass");
                detail.addProperty("retainedBytes", Long.toString(limit.retainedBytes()));
            });
            return true;
        }
        if (limit.lastObservationEndNanos() != null) {
            long cut = limit.lastObservationEndNanos();
            narrowedToNanos = narrowedToNanos == null ? cut : Math.min(narrowedToNanos, cut);
            long effective = narrowedToNanos;
            record("narrow-window", detail -> {
                detail.addProperty("effectiveToNanos", Long.toString(effective));
                detail.addProperty("clock", "source CLOCK_MONOTONIC; no wall-time translation");
                detail.addProperty(
                        "reason", "analysing a prefix completely rather than the whole window approximately");
            });
            return true;
        }
        return false;
    }

    boolean narrowed() {
        return narrowedToNanos != null;
    }

    /** The cut point a narrowed run's window ends at, or {@code null} when no narrowing was applied. */
    Long narrowedToNanos() {
        return narrowedToNanos;
    }

    Thinning thinning() {
        return thinning;
    }

    AuditLevel audit() {
        return audit;
    }

    void quantumRaised(long from, long to) {
        record("coarsen-synthetic-quantum", detail -> {
            detail.addProperty("fromNanos", Long.toString(from));
            detail.addProperty("toNanos", Long.toString(to));
            detail.addProperty("reason", "the requested quantum would have exceeded the synthetic event limit");
        });
    }

    /** Records that the synthetic JFR was omitted because even a coarser quantum could not fit. */
    void syntheticOmitted(String reason) {
        record("omit-synthetic-jfr", detail -> {
            detail.addProperty("reason", reason);
        });
    }

    /** The message a refusal carries: the limit, what was already tried, and what would allow more. */
    String refusal(RetentionLimitExceeded limit) {
        StringBuilder message = new StringBuilder(limit.getMessage())
                .append(": retained ")
                .append(limit.retainedBytes())
                .append(" of ")
                .append(limit.limitBytes())
                .append(" bytes after ")
                .append(steps.size())
                .append(" degradation step(s)");
        if (policy == Policy.FAIL) {
            message.append(
                    requestedThinning.active()
                            // Thinning was already requested explicitly, so the ladder's own thin-source
                            // rung is not the thing degrade would newly contribute here.
                            ? "; --on-limit degrade would drop the audit outputs and, if that is not enough,"
                                    + " narrow the window"
                            : "; --on-limit degrade would thin the source and report the estimator");
        } else if (policy == Policy.TRUNCATE) {
            // A truncate run that reaches refusal has already narrowed as far as the watermarks allow;
            // --from-ns/--to-ns would only narrow further by hand, which is what this ladder already did.
            message.append(
                    "; the window is already narrowed as far as the watermarks allow: raise" + " --max-retained-bytes");
        } else {
            message.append("; raise --max-retained-bytes or narrow --from-ns/--to-ns");
        }
        return message.toString();
    }

    JsonObject report(long peakRetainedBytes) {
        JsonObject value = new JsonObject();
        value.addProperty("policy", policy.text());
        value.addProperty("requestedAudit", requestedAudit.text());
        value.addProperty("audit", audit.text());
        value.addProperty("retainedBytesLimit", Long.toString(budgetBytes));
        value.addProperty("estimatedRetainedBytes", Long.toString(estimate.retainedBytes()));
        value.addProperty("peakRetainedBytes", Long.toString(peakRetainedBytes));
        // Symmetric with peakRetainedBytes: a consumer reading only the top-level object, not scanning
        // stepsApplied for the narrow-window entries and taking their minimum, still learns the window.
        value.addProperty("narrowedToNanos", narrowedToNanos == null ? null : Long.toString(narrowedToNanos));
        value.addProperty("attempts", attempts);
        JsonArray applied = new JsonArray();
        steps.forEach(applied::add);
        value.add("stepsApplied", applied);
        return value;
    }

    private void record(String step, java.util.function.Consumer<JsonObject> detail) {
        JsonObject entry = new JsonObject();
        entry.addProperty("step", step);
        detail.accept(entry);
        steps.add(entry);
    }

    private static int indexOf(String probability) {
        for (int index = 0; index < THINNING_LADDER.length; index++) {
            if (THINNING_LADDER[index].equals(probability)) return index;
        }
        return -1;
    }
}
