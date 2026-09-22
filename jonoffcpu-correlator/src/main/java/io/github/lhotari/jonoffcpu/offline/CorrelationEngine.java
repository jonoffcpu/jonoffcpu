// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import static io.github.lhotari.jonoffcpu.offline.CaptureInput.decimal;
import static io.github.lhotari.jonoffcpu.offline.CaptureInput.identity;
import static io.github.lhotari.jonoffcpu.offline.CaptureInput.number;
import static io.github.lhotari.jonoffcpu.offline.CaptureInput.object;
import static io.github.lhotari.jonoffcpu.offline.CaptureInput.require;
import static io.github.lhotari.jonoffcpu.offline.CaptureInput.signedDecimal;
import static io.github.lhotari.jonoffcpu.offline.CaptureInput.text;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.github.lhotari.jonoffcpu.capture.CaptureProto;
import io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Streams a finalized capture and its combined JFR into primitive columns, joins them on the exact
 * 64-bit cookie, and accumulates duration-weighted stacks per interned stack id.
 *
 * <p>Nothing per-row survives the pass: an observation becomes 38 bytes of columns, a sample 38
 * plus a dictionary id, and the two audit files are written by re-reading the files afterwards.
 * Validation is unchanged, only relocated: every constant an observation is checked against comes
 * from {@code captureStart}, which the reader delivers before the first observation, and the one
 * test that needs {@code captureEnd} — an interval ending after the source detached — is applied in
 * {@link #resolveDeferred(CaptureInput)} with the same precedence the retained chain had.
 */
final class CorrelationEngine implements CaptureInput.SourceVisitor {
    /** Checked every this many decoded records, so a growing structure cannot pass the budget unseen. */
    private static final int WATERMARK_ROWS = 1 << 16;

    /** Fixed-point scale for the inverse-probability sum; see {@link #sourceAggregate}. */
    private static final int ESTIMATE_FRACTION_BITS = 64;

    /** Marks a cookie that was observed but thinned away, so duplicate detection still sees it. */
    private static final int DROPPED = Integer.MAX_VALUE;

    record SourceAggregate(BigInteger duration, BigInteger estimatedDuration, int rows) {}

    private final OfflineCorrelator.Limits limits;
    private final SourceColumns sources;
    private final SampleColumns samples;
    private final JfrDictionaries dictionaries = new JfrDictionaries();
    private final LongIntMap sourceIndex;
    private final LongIntMap sampleIndex;
    private final Thinning thinning;
    private CaptureInput.Budget budget;
    private LongIntMap announcedStacks;
    // Total JFR samples the exporter delivered, kept or thinned away: the JFR-side parse-completeness
    // check counts what was parsed, not what this stage retained.
    private long samplesSeen;

    // Hoisted out of captureStart, so an observation is validated without touching a document.
    private long captureEpoch;
    private long targetPid;
    private long hostTgid;
    private BigInteger processGenerationNs;
    private long registrationToken;
    private long startedMonotonicNanos;
    private SamplingPolicy sampling;

    private CorrelationEngine(
            OfflineCorrelator.Limits limits, int expectedSources, int expectedSamples, Thinning thinning) {
        this.limits = limits;
        this.sources = new SourceColumns(expectedSources);
        this.samples = new SampleColumns(expectedSamples);
        this.sourceIndex = new LongIntMap(expectedSources);
        this.sampleIndex = new LongIntMap(expectedSamples);
        this.thinning = thinning;
    }

    static CorrelationResult correlate(
            Path source,
            Path jfr,
            OfflineCorrelator.Limits limits,
            OfflineCorrelator.JfrSelection selection,
            boolean partial)
            throws IOException {
        return correlate(source, jfr, limits, selection, partial, Thinning.NONE);
    }

    static CorrelationResult correlate(
            Path source,
            Path jfr,
            OfflineCorrelator.Limits limits,
            OfflineCorrelator.JfrSelection selection,
            boolean partial,
            Thinning thinning)
            throws IOException {
        // One observation is 99 bytes of capture stream; the estimate only sizes the first allocation.
        int expected = (int) Math.min(1 << 22, Math.max(1024, java.nio.file.Files.size(source) / 96));
        CorrelationEngine engine = new CorrelationEngine(limits, expected, expected, thinning);
        boolean partialJfr = selection != null && selection.partialInput();
        CaptureInput capture = partial
                ? CaptureInput.readPartial(source, jfr, limits, engine)
                : CaptureInput.read(source, jfr, limits, partialJfr, engine);
        return engine.finish(capture, jfr, selection);
    }

    // ---- capture visitor -------------------------------------------------------------------

    @Override
    public void reading(CaptureInput.Budget readBudget, LongIntMap stacks) {
        this.budget = readBudget;
        this.announcedStacks = stacks;
    }

    @Override
    public void start(JsonObject captureStart) throws IOException {
        captureEpoch = number(captureStart, "captureEpoch");
        targetPid = number(captureStart, "targetPid");
        hostTgid = number(captureStart, "hostTgid");
        processGenerationNs = decimal(captureStart, "processGenerationNs");
        registrationToken = Long.parseUnsignedLong(text(captureStart, "registrationToken"), 16);
        startedMonotonicNanos = U64.requireSigned(decimal(captureStart, "startedMonotonicNanos"), "captureStart");
        sampling = SamplingPolicy.parse(object(captureStart, "sampling"));
    }

    @Override
    public void stack(long stackId, CaptureProto.Stack stack) {
        // The announced-stack set lives in CaptureInput; the frames are not retained in this pass.
    }

    @Override
    public void observation(int rowNumber, CaptureProto.Observation observation) throws IOException {
        long targetTid = Integer.toUnsignedLong(observation.getTargetTid());
        require(targetTid > 0, "Missing target namespace TID");
        long targetTgid = Integer.toUnsignedLong(observation.getTargetTgid());
        require(targetTgid > 0, "Invalid target TGID");
        require(Integer.toUnsignedLong(observation.getHostTid()) > 0, "Invalid host TID");
        long threshold = observation.getAdmissionThreshold();
        require(
                threshold >= 1 && threshold <= SamplingPolicy.CERTAIN_ADMISSION.longValueExact(),
                "Invalid admission threshold");
        // Stacks are interned: an observation names an announced stack id, or explains why there is none.
        requireStack(observation.getKernelStackId(), observation.getKernelStackError());
        requireStack(observation.getUserStackId(), observation.getUserStackError());

        long start = U64.requireSigned(observation.getStartMonotonicNs(), "startMonotonicNanos");
        long end = U64.requireSigned(observation.getEndMonotonicNs(), "endMonotonicNanos");
        long cookie = observation.getCorrelationId();
        boolean cookieValid = (cookie >>> 32) == captureEpoch && (cookie & 0xffffffffL) != 0;
        boolean policyMismatch = targetTgid != targetPid
                || Integer.toUnsignedLong(observation.getHostTgid()) != hostTgid
                // The kernel recorded the threshold it drew against; it must be the policy's for this length.
                || start > end
                || !BigInteger.valueOf(threshold).equals(sampling.admissionThreshold(BigInteger.valueOf(end - start)))
                || !unsigned(observation.getProcessGenerationNs()).equals(processGenerationNs)
                || observation.getRegistrationToken() != registrationToken;

        // The retained chain's precedence, minus the captureEnd half of OUTSIDE_CAPTURE.
        Reason reason;
        if (start > end) {
            reason = Reason.NEGATIVE_DURATION;
        } else if (start < startedMonotonicNanos) {
            reason = Reason.OUTSIDE_CAPTURE;
        } else if (policyMismatch) {
            reason = Reason.POLICY_MISMATCH;
        } else if (!cookieValid) {
            reason = Reason.INVALID_COOKIE;
        } else if (!sampling.withinBounds(BigInteger.valueOf(end - start))) {
            reason = Reason.DURATION_POLICY;
        } else {
            reason = Reason.NONE;
        }
        boolean kept = thinning.keeps(cookie);
        // Duplicate detection stays global and exact: a dropped row still claims its cookie.
        sourceIndex.observe(cookie, kept ? sources.size() : DROPPED);
        if (!kept) return;
        sources.add(cookie, start, end, threshold, (int) targetTid, observation.getSignalResult() != 0, reason);
        if (sources.size() % WATERMARK_ROWS == 0) {
            try {
                budget.structures(retainedBytes());
            } catch (RetentionLimitExceeded limit) {
                throw limit.withLastObservationEnd(end);
            }
        }
    }

    private void requireStack(long stackId, String error) throws IOException {
        if (error.isEmpty()) {
            require(stackId >= 0, "Unexplained negative stack id");
            require(announcedStacks.contains(stackId), "Observation references an unannounced stack");
        }
    }

    private static BigInteger unsigned(long bits) {
        return U64.big(bits);
    }

    // ---- JFR pass --------------------------------------------------------------------------

    private CorrelationResult finish(CaptureInput capture, Path jfr, OfflineCorrelator.JfrSelection selection)
            throws IOException {
        resolveDeferred(capture);
        JsonObject inputs = capture.inputs;
        Gson gson = new GsonBuilder().serializeNulls().create();
        JsonObject[] observedStats = new JsonObject[1];
        SignalJfrExporter.RowConsumer consumer = raw -> {
            capture.budget.countRow();
            String recordType = (String) raw.get("recordType");
            require(recordType != null, "Missing string: recordType");
            switch (recordType) {
                case "capture" -> {
                    JsonObject row = gson.toJsonTree(raw).getAsJsonObject();
                    capture.budget.charge(row);
                    identity(row, inputs);
                    require(
                            number(row, "signal") == number(inputs, "signal")
                                    && number(row, "processId") == number(inputs, "targetPid"),
                            "JFR/source target mismatch");
                    require(
                            text(row, "signalDelivery").equals(text(inputs, "signalDelivery")),
                            "JFR/source delivery policy mismatch");
                    if (capture.partial) capture.diagnostics.add("observedJfrCapture", row);
                }
                case "sample" -> sample(raw);
                case "stats" -> {
                    JsonObject row = gson.toJsonTree(raw).getAsJsonObject();
                    capture.budget.charge(row);
                    OfflineCorrelator.validateStats(row, inputs);
                    observedStats[0] = row;
                    if (capture.partial) capture.diagnostics.add("observedApStats", row);
                }
                case "end" ->
                    require(
                            Boolean.TRUE.equals(raw.get("parseComplete"))
                                    && Long.parseUnsignedLong((String) raw.get("samples")) == samplesSeen,
                            "Incomplete JFR parse");
                default -> throw new IOException("Unknown JFR row");
            }
        };
        JsonObject selectionMetadata = OfflineCorrelator.readJfr(capture, jfr, selection, consumer);
        capture.budget.structures(retainedBytes());
        JsonObject stats = inputs.has("apStats") ? object(inputs, "apStats") : observedStats[0];
        BigInteger notParsed = stats == null || selection != null
                ? null
                : decimal(stats, "submittedSamples").subtract(BigInteger.valueOf(samplesSeen));
        require(notParsed == null || notParsed.signum() >= 0, "JFR samples exceed submitted samples");
        return join(capture, selectionMetadata, notParsed);
    }

    private void sample(Map<String, Object> raw) throws IOException {
        Object frames = raw.get("frames");
        require(frames instanceof List<?>, "Missing stack frames");
        String cookieText = (String) raw.get("correlationId");
        long cookie = Long.parseUnsignedLong(cookieText, 16);
        samplesSeen++;
        boolean kept = thinning.keeps(cookie);
        // Duplicate detection stays global and exact: a dropped sample still claims its cookie.
        sampleIndex.observe(cookie, kept ? samples.size() : DROPPED);
        if (!kept) return;
        long monotonic = U64.requireSigned(unsignedDecimal(raw, "monotonicTimeNanos"), "monotonicTimeNanos");
        Long osThreadId = optionalTid(raw, "osThreadId");
        Long javaThreadId = (Long) raw.get("javaThreadId");
        int stackId = dictionaries.internStack(
                (List<?>) frames, Boolean.TRUE.equals(raw.get("stackTruncated")), limits.maxFrames());
        int threadId = dictionaries.internThread(osThreadId, javaThreadId, (String) raw.get("threadName"));
        samples.add(
                cookie,
                monotonic,
                epochNanos((String) raw.get("startTime")),
                osThreadId == null ? 0 : (int) (long) osThreadId,
                stackId,
                threadId);
        // The exporter already rejects a cookie outside the capture epoch, so this can only fire on zero.
        if ((cookie >>> 32) != captureEpoch || (cookie & 0xffffffffL) == 0) {
            samples.reason(samples.size() - 1, Reason.INVALID_COOKIE);
        }
        if (samples.size() % WATERMARK_ROWS == 0) budget.structures(retainedBytes());
    }

    private static BigInteger unsignedDecimal(Map<String, Object> raw, String key) throws IOException {
        Object value = raw.get(key);
        require(value instanceof String, "Missing string: " + key);
        String text = (String) value;
        require(text.matches("0|[1-9][0-9]{0,19}"), "Invalid unsigned decimal: " + key);
        BigInteger parsed = new BigInteger(text);
        require(parsed.compareTo(CaptureInput.U64_MAX) <= 0, "Unsigned overflow: " + key);
        return parsed;
    }

    private static Long optionalTid(Map<String, Object> raw, String key) throws IOException {
        require(raw.containsKey(key), "Missing thread identity: " + key);
        Object value = raw.get(key);
        if (value == null) return null;
        long tid = ((Number) value).longValue();
        require(tid > 0 && tid <= 0xffffffffL, "Invalid TID");
        return tid;
    }

    private static long epochNanos(String startTime) throws IOException {
        try {
            Instant instant = Instant.parse(startTime);
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
        } catch (DateTimeParseException | ArithmeticException | NullPointerException error) {
            throw new IOException("Invalid sample start time", error);
        }
    }

    // ---- join ------------------------------------------------------------------------------

    /**
     * Applies the one validation that needs {@code captureEnd}. The retained chain tested {@code
     * start < startedMonotonicNanos || end > detachedMonotonicNanos} in a single branch that sat
     * above the policy and duration-bound branches, so an interval ending after the source detached
     * outranks every reason except a negative duration and an already-recorded outside-capture.
     */
    private void resolveDeferred(CaptureInput capture) throws IOException {
        if (capture.end == null) return;
        long detached = U64.requireSigned(decimal(capture.end, "detachedMonotonicNanos"), "detachedMonotonicNanos");
        for (int slot = 0; slot < sources.size(); slot++) {
            Reason reason = sources.reason(slot);
            if (reason == Reason.NEGATIVE_DURATION || reason == Reason.OUTSIDE_CAPTURE) continue;
            if (sources.end(slot) > detached) sources.reason(slot, Reason.OUTSIDE_CAPTURE);
        }
    }

    private CorrelationResult join(CaptureInput capture, JsonObject selectionMetadata, BigInteger notParsed)
            throws IOException {
        // The cookie indices are populated as each row streams in (see observation/sample), so a
        // dropped row still claims its cookie for duplicate detection without landing in the columns.
        // A duplicated source cookie invalidates every copy and its counterpart.
        invalidate(sourceIndex);
        SourceAggregate aggregate = sourceAggregate();
        // AP-only ambiguity invalidates the stack pair, not an independently valid source duration.
        invalidate(sampleIndex);

        Long offset = capture.inputs.has("monotonicOffsetNanos")
                ? U64.requireSignedOffset(signedDecimal(capture.inputs, "monotonicOffsetNanos"), "monotonicOffsetNanos")
                : null;
        Long clipFrom = limits.fromNanos() == null ? null : limits.fromNanos().longValueExact();
        Long clipTo = limits.toNanos() == null ? null : limits.toNanos().longValueExact();
        Long delayLimit = limits.maxHandlerDelayNanos() == null
                ? null
                : limits.maxHandlerDelayNanos().longValueExact();
        // end + offset > apStop is the same test as end > apStop - offset, and the right side is constant.
        Long apStopBoundary = null;
        boolean apStopAlways = false;
        if (offset != null && capture.apStoppedAtNanos != null) {
            BigInteger boundary = new BigInteger(capture.apStoppedAtNanos).subtract(BigInteger.valueOf(offset));
            if (boundary.signum() < 0) apStopAlways = true;
            else if (boundary.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                apStopBoundary = boundary.longValueExact();
            }
        }

        long[] collapsedNanos = new long[dictionaries.collapsedCount()];
        long[] stackNanos = new long[dictionaries.stackCount()];
        long total = 0;
        int matched = 0;
        int unverified = 0;
        int withoutSelectedSample = 0;
        for (int slot = 0; slot < sources.size(); slot++) {
            if (apStopAlways || (apStopBoundary != null && sources.end(slot) > apStopBoundary)) {
                sources.outcome(slot, Outcome.AFTER_AP_STOP);
            }
            int sample = sampleIndex.get(sources.cookie(slot));
            if (sample < 0) {
                if (selectionMetadata != null && sources.outcome(slot) == Outcome.UNRESOLVED) {
                    sources.outcome(slot, Outcome.NOT_IN_SELECTED_JFR);
                    withoutSelectedSample++;
                }
                continue;
            }
            if (sources.reason(slot) != Reason.NONE || samples.reason(sample) != Reason.NONE) {
                if (sources.reason(slot) == Reason.NONE) sources.reason(slot, Reason.INVALID_PAIR);
                if (samples.reason(sample) == Reason.NONE) samples.reason(sample, Reason.INVALID_PAIR);
                continue;
            }
            if (sources.outcome(slot) != Outcome.UNRESOLVED) {
                sources.reason(slot, Reason.PAIR_AFTER_AP_STOP);
                samples.reason(sample, Reason.PAIR_AFTER_AP_STOP);
                continue;
            }
            Reason invalid = Reason.NONE;
            long sampleTid = Integer.toUnsignedLong(samples.osThreadId(sample));
            boolean verified = sampleTid != 0;
            Long delay = null;
            boolean delayInvalid = false;
            if (offset != null) {
                try {
                    delay = Math.subtractExact(
                            Math.subtractExact(samples.monotonicNanos(sample), sources.end(slot)), offset);
                    delayInvalid = delay < 0;
                } catch (ArithmeticException overflow) {
                    delayInvalid = true;
                }
            }
            if (verified && Integer.toUnsignedLong(sources.targetTid(slot)) != sampleTid) {
                invalid = Reason.THREAD_IDENTITY_MISMATCH;
            } else if (sources.signalFailed(slot)) {
                invalid = Reason.FAILED_SIGNAL_REQUEST;
            } else if (delayInvalid) {
                invalid = Reason.INVALID_HANDLER_DELAY;
            } else if (delay != null && delayLimit != null && delay > delayLimit) {
                invalid = Reason.HANDLER_DELAY_LIMIT;
            }
            if (invalid != Reason.NONE) {
                sources.reason(slot, invalid);
                samples.reason(sample, invalid);
                continue;
            }
            sources.outcome(slot, Outcome.MATCHED);
            samples.outcome(sample, Outcome.MATCHED);
            sources.verified(slot, verified);
            if (!verified) unverified++;
            matched++;
            long start = sources.start(slot);
            long end = sources.end(slot);
            long from = clipFrom == null ? start : Math.max(start, clipFrom);
            long to = clipTo == null ? end : Math.min(end, clipTo);
            long duration = to > from ? to - from : 0;
            total = U64.add(total, duration, "Selected duration");
            int stackId = samples.stackId(sample);
            stackNanos[stackId] = U64.add(stackNanos[stackId], duration, "Selected duration");
            if (duration > 0) {
                int collapsed = dictionaries.collapsedOf(stackId);
                collapsedNanos[collapsed] = U64.add(collapsedNanos[collapsed], duration, "Selected duration");
            }
        }

        int invalidSource = 0;
        for (int slot = 0; slot < sources.size(); slot++) {
            if (sources.reason(slot) != Reason.NONE) invalidSource++;
        }
        int invalidJfr = 0;
        for (int slot = 0; slot < samples.size(); slot++) {
            if (samples.reason(slot) != Reason.NONE) invalidJfr++;
        }
        OfflineCorrelator.PopulationEstimate estimate =
                capture.partial ? null : populationEstimate(capture, aggregate, BigInteger.valueOf(total));
        return new CorrelationResult(
                capture,
                selectionMetadata,
                notParsed == null ? null : notParsed.toString(),
                sources,
                samples,
                sourceIndex,
                dictionaries,
                sampleIndex,
                collapsedNanos,
                stackNanos,
                total,
                offset,
                clipFrom,
                clipTo,
                matched,
                sources.size() - matched - invalidSource,
                samples.size() - matched - invalidJfr,
                invalidSource,
                invalidJfr,
                unverified,
                withoutSelectedSample,
                estimate,
                aggregate,
                capture.budget.peak(),
                thinning);
    }

    /** Marks every row on both sides whose cookie this index saw more than once. */
    private void invalidate(LongIntMap index) {
        for (int slot = 0; slot < sources.size(); slot++) {
            if (index.get(sources.cookie(slot)) == LongIntMap.DUPLICATE) {
                sources.reason(slot, Reason.DUPLICATE_COOKIE);
            }
        }
        for (int slot = 0; slot < samples.size(); slot++) {
            if (index.get(samples.cookie(slot)) == LongIntMap.DUPLICATE) {
                samples.reason(slot, Reason.DUPLICATE_COOKIE);
            }
        }
    }

    /**
     * Inverse-probability source-duration estimate. Each row's truncation error is below 2^-64 ns,
     * so the truncated total is the exact floor of the true sum unless that sum lies within
     * rows * 2^-64 ns above an integer, in which case it is one nanosecond low. Exact rationals
     * would instead need the lcm of every distinct per-row threshold as a denominator. This is one
     * accumulator over the column, not one object per row.
     */
    private SourceAggregate sourceAggregate() {
        BigInteger duration = BigInteger.ZERO;
        BigInteger weighted = BigInteger.ZERO;
        int rows = 0;
        Long clipFrom = limits.fromNanos() == null ? null : limits.fromNanos().longValueExact();
        Long clipTo = limits.toNanos() == null ? null : limits.toNanos().longValueExact();
        for (int slot = 0; slot < sources.size(); slot++) {
            if (sources.reason(slot) != Reason.NONE) continue;
            long start = sources.start(slot);
            long end = sources.end(slot);
            long from = clipFrom == null ? start : Math.max(start, clipFrom);
            long to = clipTo == null ? end : Math.min(end, clipTo);
            BigInteger selected = BigInteger.valueOf(to > from ? to - from : 0);
            duration = duration.add(selected);
            weighted = weighted.add(selected.shiftLeft(32 + ESTIMATE_FRACTION_BITS)
                    .divide(BigInteger.valueOf(sources.threshold(slot))));
            rows++;
        }
        return new SourceAggregate(duration, weighted.shiftRight(ESTIMATE_FRACTION_BITS), rows);
    }

    private long retainedBytes() {
        return sources.retainedBytes()
                + samples.retainedBytes()
                + sourceIndex.retainedBytes()
                + sampleIndex.retainedBytes()
                + dictionaries.retainedBytes();
    }

    /**
     * Moved verbatim from the retained chain's aggregate-consistency check, except the row count now
     * comes from this pass's {@link SourceColumns} rather than a {@code List<Row>}.
     */
    private OfflineCorrelator.PopulationEstimate populationEstimate(
            CaptureInput capture, SourceAggregate aggregate, BigInteger matchedDuration) throws IOException {
        JsonObject counters = object(capture.end, "counters");
        JsonObject kernel = object(counters, "kernel");
        JsonObject userspace = object(counters, "userspace");
        List<String> reasons = new java.util.ArrayList<>();
        // A second, independent thinning stage composes with this inverse-probability estimate but
        // must not be silently folded into it: report the estimate unavailable instead.
        if (thinning.active()) {
            reasons.add("correlation-time-thinned-source");
        }
        if (aggregate.rows() != sources.size()) {
            reasons.add("intrinsically-invalid-or-duplicate-source-rows");
        }
        BigInteger sourceRows = BigInteger.valueOf(sources.size());
        BigInteger selected = optionalCounter(kernel, "selectedIntervals", reasons);
        BigInteger eligible = optionalCounter(kernel, "eligibleIntervals", reasons);
        BigInteger rejected = optionalCounter(kernel, "admissionRejections", reasons);
        BigInteger received = optionalCounter(userspace, "receivedObservations", reasons);
        BigInteger written = optionalCounter(userspace, "writtenObservations", reasons);
        if (selected != null
                && (!selected.equals(sourceRows)
                        || received != null && !selected.equals(received)
                        || written != null && !selected.equals(written))) {
            reasons.add("selected-source-row-count-mismatch");
        }
        if (eligible != null && rejected != null && selected != null && !eligible.equals(rejected.add(selected))) {
            reasons.add("eligible-selection-counter-mismatch");
        }
        for (String key : List.of(
                "targetNamespaceFailures",
                "ringReserveFailures",
                "sequenceExhaustions",
                "sequenceContentions",
                "lifetimeRejections",
                "threadStateFailures")) {
            BigInteger value = optionalCounter(kernel, key, reasons);
            if (value != null && value.signum() != 0) {
                reasons.add("nonzero-" + key);
            }
        }
        for (String key : List.of("writeFailures", "pollFailures")) {
            BigInteger value = optionalCounter(userspace, key, reasons);
            if (value != null && value.signum() != 0) {
                reasons.add("nonzero-" + key);
            }
        }
        reasons = reasons.stream().distinct().sorted().toList();
        boolean available = reasons.isEmpty();
        return new OfflineCorrelator.PopulationEstimate(
                "inverse-probability-source-duration",
                available ? "available" : "unavailable",
                "completed duration-eligible source intervals",
                sampling.policy(),
                aggregate.duration().toString(),
                matchedDuration.toString(),
                Integer.toString(aggregate.rows()),
                available ? aggregate.estimatedDuration().toString() : null,
                available,
                false,
                reasons);
    }

    private static BigInteger optionalCounter(JsonObject counters, String key, List<String> reasons) {
        com.google.gson.JsonElement value = counters.get(key);
        if (value == null || value.isJsonNull()) {
            reasons.add("missing-counter-" + key);
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            reasons.add("invalid-counter-" + key);
            return null;
        }
        try {
            String text = value.getAsString();
            if (!text.matches("0|[1-9][0-9]{0,19}")) {
                throw new NumberFormatException();
            }
            BigInteger parsed = new BigInteger(text);
            if (parsed.signum() < 0 || parsed.compareTo(CaptureInput.U64_MAX) > 0) {
                throw new NumberFormatException();
            }
            if (parsed.equals(CaptureInput.U64_MAX)) {
                reasons.add("saturated-counter-" + key);
            }
            return parsed;
        } catch (RuntimeException error) {
            reasons.add("invalid-counter-" + key);
            return null;
        }
    }
}
