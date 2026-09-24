// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.offline;

import static io.github.jonoffcpu.jonoffcpu.offline.CaptureInput.require;

import io.github.jonoffcpu.jonoffcpu.capture.CaptureProto;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.nio.file.Path;
import java.util.List;

/**
 * Streams a finalized capture and its combined JFR into primitive columns, joins them on the exact
 * 64-bit cookie, and accumulates duration-weighted stacks per interned stack id.
 *
 * <p>Nothing per-row survives the pass: an observation becomes 59 bytes of columns, a sample 38
 * plus a dictionary id, and the two audit files are written by re-reading the files afterwards.
 * Validation is unchanged, only relocated: every constant an observation is checked against comes
 * from {@code capture_start}, which the reader delivers before the first observation, and the one
 * test that needs {@code capture_end} — an interval ending after the source detached — is applied in
 * {@link #resolveDeferred(CaptureInput)} with the same precedence the retained chain had.
 */
final class CorrelationEngine implements CaptureInput.SourceVisitor {

    /** Fixed-point scale for the inverse-probability sum; see {@link #sourceAggregate}. */
    private static final int ESTIMATE_FRACTION_BITS = 64;

    /** The assumption under which an estimate scaled for an accounted sequence-contention loss stays unbiased. */
    static final String CONTENTION_INDEPENDENCE =
            "sequence contention is independent of the interval's stack and duration";

    /** Marks a cookie that was observed but thinned away, so duplicate detection still sees it. */
    static final int DROPPED = Integer.MAX_VALUE;

    /** {@code weighted} is the inverse-probability sum in fixed point, {@link #ESTIMATE_FRACTION_BITS} below one. */
    record SourceAggregate(BigInteger duration, BigInteger weighted, int rows) {
        BigInteger estimatedDuration() {
            return weighted.shiftRight(ESTIMATE_FRACTION_BITS);
        }

        /** The estimate scaled by {@code numerator / denominator} in fixed point, then truncated. */
        BigInteger estimatedDuration(BigInteger numerator, BigInteger denominator) {
            return weighted.multiply(numerator).divide(denominator).shiftRight(ESTIMATE_FRACTION_BITS);
        }
    }

    private final OfflineCorrelator.Limits limits;
    private final SourceColumns sources;
    private final SampleColumns samples;
    private final JfrDictionaries dictionaries = new JfrDictionaries();
    private final NativeStacks nativeStacks = new NativeStacks();
    private final ProfileAccumulator.Options profileOptions;
    private final LongIntMap sourceIndex;
    private final LongIntMap sampleIndex;
    private final Thinning thinning;
    private final Long narrowedToNanos;
    private CaptureInput.Budget budget;
    // selected / received when an available population estimate was scaled for an accounted loss.
    private BigInteger[] accountedScale;
    private LongIntMap announcedStacks;
    // Total JFR samples the exporter delivered, kept or thinned away: the JFR-side parse-completeness
    // check counts what was parsed, not what this stage retained.
    private long samplesSeen;

    // Hoisted out of capture_start, so an observation is validated without touching a message.
    private long captureEpoch;
    private int targetPid;
    private int hostTgid;
    private long processGenerationNanos;
    private long registrationToken;
    private long startedMonotonicNanos;
    private SamplingPolicy sampling;
    private TimeSplit.Source timeSplit;

    private CorrelationEngine(
            OfflineCorrelator.Limits limits,
            int expectedSources,
            int expectedSamples,
            Thinning thinning,
            Long narrowedToNanos,
            ProfileAccumulator.Options profileOptions) {
        this.limits = limits;
        this.profileOptions = profileOptions;
        this.sources = new SourceColumns(expectedSources);
        this.samples = new SampleColumns(expectedSamples);
        this.sourceIndex = new LongIntMap(expectedSources);
        this.sampleIndex = new LongIntMap(expectedSamples);
        this.thinning = thinning;
        this.narrowedToNanos = narrowedToNanos;
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
        return correlate(
                source, jfr, limits, selection, partial, new Degradation.Settings(AuditLevel.FULL, thinning, null));
    }

    static CorrelationResult correlate(
            Path source,
            Path jfr,
            OfflineCorrelator.Limits limits,
            OfflineCorrelator.JfrSelection selection,
            boolean partial,
            Degradation.Settings settings)
            throws IOException {
        return correlate(source, jfr, limits, selection, partial, settings, ProfileAccumulator.Options.defaults());
    }

    static CorrelationResult correlate(
            Path source,
            Path jfr,
            OfflineCorrelator.Limits limits,
            OfflineCorrelator.JfrSelection selection,
            boolean partial,
            Degradation.Settings settings,
            ProfileAccumulator.Options profileOptions)
            throws IOException {
        // One observation is 99 bytes of capture stream and one sample 35 bytes of JFR; the estimate
        // only sizes the first allocation, off each side's own file so a much smaller JFR does not
        // preallocate as though it were as dense as the capture.
        int expectedSources = (int) Math.min(1 << 22, Math.max(1024, java.nio.file.Files.size(source) / 96));
        int expectedSamples = (int) Math.min(1 << 22, Math.max(1024, java.nio.file.Files.size(jfr) / 35));
        CorrelationEngine engine = new CorrelationEngine(
                limits,
                expectedSources,
                expectedSamples,
                settings.thinning(),
                settings.narrowedToNanos(),
                profileOptions);
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
    public void start(CaptureProto.CaptureStart captureStart) throws IOException {
        captureEpoch = Integer.toUnsignedLong(captureStart.getCaptureEpoch());
        targetPid = captureStart.getTargetPid();
        hostTgid = captureStart.getHostTgid();
        processGenerationNanos = captureStart.getVerifiedIdentity().getProcessGenerationNanos();
        registrationToken = captureStart.getVerifiedIdentity().getRegistrationToken();
        startedMonotonicNanos = U64.requireSigned(captureStart.getStartedMonotonicNanos(), "captureStart");
        sampling = SamplingPolicy.parse(captureStart.getSampling());
        timeSplit = TimeSplit.source(captureStart.getTimeSplit());
    }

    @Override
    public void stack(long stackId, CaptureProto.Stack stack) {
        // The announced-stack set lives in CaptureInput. The frames are retained once per distinct stack
        // for the stack profile, which groups by them.
        nativeStacks.add(stackId, stack);
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

        long start = U64.requireSigned(observation.getStartMonotonicNanos(), "startMonotonicNanos");
        long end = U64.requireSigned(observation.getEndMonotonicNanos(), "endMonotonicNanos");
        long cookie = observation.getCorrelationId();
        boolean cookieValid = (cookie >>> 32) == captureEpoch && (cookie & 0xffffffffL) != 0;
        // The kernel derived the reason from the two raw sched_switch arguments it recorded beside it, and its
        // filter only passes selected reasons: both are recomputed, like the admission threshold.
        OffCpuReason switchOut = OffCpuReason.fromWire(observation.getReasonValue());
        boolean classificationMismatch = switchOut == null
                || switchOut != OffCpuReason.classify(observation.getPreempted(), observation.getPrevTaskState())
                || !sampling.selects(switchOut);
        if (switchOut == null) switchOut = OffCpuReason.UNSPECIFIED;
        boolean policyMismatch = classificationMismatch
                // Only a capture that reads the scheduler's run delay may carry a run-queue part.
                || observation.hasRunqueueNanos() && !timeSplit.available()
                || observation.getTargetTgid() != targetPid
                || observation.getHostTgid() != hostTgid
                // The kernel recorded the threshold it drew against; it must be the policy's for this length.
                || start > end
                || !BigInteger.valueOf(threshold).equals(sampling.admissionThreshold(BigInteger.valueOf(end - start)))
                || observation.getProcessGenerationNanos() != processGenerationNanos
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
        // A narrowed window contributes nothing past its cut; dropping here (not just clipping later)
        // is what actually shrinks retention on the restart that follows a narrow-window ladder step.
        boolean kept = CorrelationResult.keepsSource(thinning, narrowedToNanos, cookie, start);
        // Duplicate detection stays global and exact: a dropped row still claims its cookie.
        sourceIndex.observe(cookie, kept ? sources.size() : DROPPED);
        if (!kept) return;
        sources.add(
                cookie,
                start,
                end,
                threshold,
                (int) targetTid,
                observation.getSignalResult() != 0,
                reason,
                switchOut,
                observation.getPrevTaskState(),
                stackColumn(observation.getKernelStackId(), observation.getKernelStackError()),
                stackColumn(observation.getUserStackId(), observation.getUserStackError()),
                observation.hasRunqueueNanos(),
                observation.getRunqueueNanos());
        if (sources.size() % limits.watermarkRows() == 0) {
            try {
                budget.structures(retainedBytes());
            } catch (RetentionLimitExceeded limit) {
                // The cut excludes this observation itself (its own start, not its end): a retry that
                // re-admitted it would retrace the identical column growth up to this exact watermark and
                // reproduce the identical failure, making no progress. Cutting at its start guarantees the
                // retry's kept-row count for this window strictly decreases.
                throw limit.withLastObservationEnd(start);
            }
        }
    }

    private void requireStack(long stackId, String error) throws IOException {
        if (error.isEmpty()) {
            require(stackId >= 0, "Unexplained negative stack id");
            require(announcedStacks.contains(stackId), "Observation references an unannounced stack");
        }
    }

    /** An announced stack id, or {@link SourceColumns#NO_STACK} when the kernel produced none. */
    private static int stackColumn(long stackId, String error) {
        return error.isEmpty() ? Math.toIntExact(stackId) : SourceColumns.NO_STACK;
    }

    // ---- JFR pass --------------------------------------------------------------------------

    private CorrelationResult finish(CaptureInput capture, Path jfr, OfflineCorrelator.JfrSelection selection)
            throws IOException {
        resolveDeferred(capture);
        CaptureProto.AsyncProfilerStats[] observedStats = new CaptureProto.AsyncProfilerStats[1];
        SignalJfrExporter.RowConsumer consumer = row -> {
            capture.budget.countRow();
            switch (row.getRecordCase()) {
                case CAPTURE -> {
                    SignalProto.SignalCapture context = row.getCapture();
                    capture.budget.charge(context);
                    CaptureInput.jfrIdentity(
                            context.getSessionId(), Integer.toUnsignedLong(context.getCaptureEpoch()), capture);
                    require(
                            context.getSignal() == capture.start.getSignal()
                                    && context.getProcessId() == Integer.toUnsignedLong(capture.start.getTargetPid()),
                            "JFR/source target mismatch");
                    require(
                            context.getSignalDelivery() == capture.start.getSignalDelivery(),
                            "JFR/source delivery policy mismatch");
                    if (capture.partial) capture.diagnostics.setObservedJfrCapture(context);
                }
                case SAMPLE -> sample(row.getSample());
                case STATS -> {
                    SignalProto.SignalCaptureStats stats = row.getStats();
                    capture.budget.charge(stats);
                    OfflineCorrelator.validateStats(stats, capture);
                    observedStats[0] = stats.getCounters();
                    if (capture.partial) capture.diagnostics.setObservedApStats(stats);
                }
                case END ->
                    require(
                            row.getEnd().getParseComplete() && row.getEnd().getSamples() == samplesSeen,
                            "Incomplete JFR parse");
                default -> throw new IOException("Unknown JFR row");
            }
        };
        ReportProto.JfrSelection selectionMetadata = OfflineCorrelator.readJfr(capture, jfr, selection, consumer);
        capture.budget.structures(retainedBytes());
        CaptureProto.AsyncProfilerStats stats = capture.apStats() != null ? capture.apStats() : observedStats[0];
        BigInteger notParsed = stats == null || selection != null
                ? null
                : U64.big(stats.getSubmittedSamples()).subtract(BigInteger.valueOf(samplesSeen));
        require(notParsed == null || notParsed.signum() >= 0, "JFR samples exceed submitted samples");
        return join(capture, selectionMetadata, notParsed);
    }

    private void sample(SignalProto.SignalSample raw) throws IOException {
        long cookie = raw.getCorrelationId();
        samplesSeen++;
        // The source pass (already complete by the time samples stream) marks a cookie DROPPED when its
        // observation was thinned or fell outside a narrowed window; the matching sample follows it down.
        boolean kept = CorrelationResult.keepsSample(thinning, sourceIndex, cookie);
        // Duplicate detection stays global and exact: a dropped sample still claims its cookie.
        sampleIndex.observe(cookie, kept ? samples.size() : DROPPED);
        if (!kept) return;
        long monotonic = U64.requireSigned(raw.getMonotonicTimeNanos(), "monotonicTimeNanos");
        Long osThreadId = raw.hasOsThreadId() ? tid(raw.getOsThreadId()) : null;
        Long javaThreadId = raw.hasJavaThreadId() ? raw.getJavaThreadId() : null;
        int stackId = dictionaries.internStack(
                raw.getFramesList(), raw.hasStackTruncated() && raw.getStackTruncated(), limits.maxFrames());
        int threadId =
                dictionaries.internThread(osThreadId, javaThreadId, raw.hasThreadName() ? raw.getThreadName() : null);
        samples.add(
                cookie,
                monotonic,
                epochNanos(raw.getStartTime()),
                osThreadId == null ? 0 : (int) (long) osThreadId,
                stackId,
                threadId);
        // The exporter already rejects a cookie outside the capture epoch, so this can only fire on zero.
        if ((cookie >>> 32) != captureEpoch || (cookie & 0xffffffffL) == 0) {
            samples.reason(samples.size() - 1, Reason.INVALID_COOKIE);
        }
        if (samples.size() % limits.watermarkRows() == 0) {
            try {
                budget.structures(retainedBytes());
            } catch (RetentionLimitExceeded limit) {
                // A sample's cookie resolves to its source slot in the same clock the narrow cut is
                // expressed in: reusing it here means a budget exceeded while reading JFR samples can
                // still narrow, instead of only ever refusing with no cut point. An orphan sample (no
                // source row at all, so the cookie is merely absent from the index, not DROPPED) falls
                // back to the last kept source slot's start: the source pass is already complete by the
                // time samples stream, so that slot exists and is the latest one this window still
                // admits, which still costs a rung rather than refusing outright.
                int slot = sourceIndex.get(cookie);
                if (slot < 0 && sources.size() > 0) slot = sources.size() - 1;
                throw slot >= 0 ? limit.withLastObservationEnd(sources.start(slot)) : limit;
            }
        }
    }

    private static long tid(long tid) throws IOException {
        require(tid > 0 && tid <= 0xffffffffL, "Invalid TID");
        return tid;
    }

    private static long epochNanos(com.google.protobuf.Timestamp startTime) throws IOException {
        try {
            return Math.addExact(Math.multiplyExact(startTime.getSeconds(), 1_000_000_000L), startTime.getNanos());
        } catch (ArithmeticException error) {
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
        long detached = U64.requireSigned(capture.end.getDetachedMonotonicNanos(), "detachedMonotonicNanos");
        for (int slot = 0; slot < sources.size(); slot++) {
            Reason reason = sources.reason(slot);
            if (reason == Reason.NEGATIVE_DURATION || reason == Reason.OUTSIDE_CAPTURE) continue;
            if (sources.end(slot) > detached) sources.reason(slot, Reason.OUTSIDE_CAPTURE);
        }
    }

    private CorrelationResult join(
            CaptureInput capture, ReportProto.JfrSelection selectionMetadata, BigInteger notParsed) throws IOException {
        // The cookie indices are populated as each row streams in (see observation/sample), so a
        // dropped row still claims its cookie for duplicate detection without landing in the columns.
        // A duplicated source cookie invalidates every copy and its counterpart.
        invalidate(sourceIndex);
        SourceAggregate aggregate = sourceAggregate();
        // AP-only ambiguity invalidates the stack pair, not an independently valid source duration.
        invalidate(sampleIndex);

        Long offset = capture.monotonicOffsetNanos();
        Long clipFrom = limits.fromNanos() == null ? null : limits.fromNanos().longValueExact();
        Long clipTo = effectiveToNanos();
        Long delayLimit = limits.maxHandlerDelayNanos() == null
                ? null
                : limits.maxHandlerDelayNanos().longValueExact();
        // end + offset > apStop is the same test as end > apStop - offset, and the right side is constant.
        Long apStopBoundary = null;
        boolean apStopAlways = false;
        if (offset != null && capture.apStoppedAtNanos != null) {
            BigInteger boundary = U64.big(capture.apStoppedAtNanos).subtract(BigInteger.valueOf(offset));
            if (boundary.signum() < 0) apStopAlways = true;
            else if (boundary.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
                apStopBoundary = boundary.longValueExact();
            }
        }

        long[] collapsedNanos = new long[dictionaries.collapsedCount()];
        long[][] collapsedNanosByReason = new long[OffCpuReason.values().length][];
        long[] stackNanos = new long[dictionaries.stackCount()];
        ProfileAccumulator profile = new ProfileAccumulator(profileOptions);
        long[] parts = new long[3];
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
            int collapsed = dictionaries.collapsedOf(stackId);
            OffCpuReason switchOut = sources.offCpuReason(slot);
            TimeSplit.split(switchOut, start, end, sources.hasRunqueue(slot), sources.runqueue(slot), from, to, parts);
            if (duration > 0) {
                collapsedNanos[collapsed] = U64.add(collapsedNanos[collapsed], duration, "Selected duration");
                long[] byReason = collapsedNanosByReason[switchOut.ordinal()];
                if (byReason == null) {
                    byReason = collapsedNanosByReason[switchOut.ordinal()] = new long[collapsedNanos.length];
                }
                byReason[collapsed] = U64.add(byReason[collapsed], duration, "Selected duration");
            }
            profile.add(
                    collapsed,
                    sources.kernelStack(slot),
                    sources.userStack(slot),
                    switchOut,
                    sources.taskState(slot),
                    dictionaries.thread(samples.threadId(sample)).name(),
                    parts,
                    sources.threshold(slot));
        }
        capture.budget.structures(retainedBytes() + profile.retainedBytes());

        int invalidSource = 0;
        for (int slot = 0; slot < sources.size(); slot++) {
            if (sources.reason(slot) != Reason.NONE) invalidSource++;
        }
        int invalidJfr = 0;
        for (int slot = 0; slot < samples.size(); slot++) {
            if (samples.reason(slot) != Reason.NONE) invalidJfr++;
        }
        ReportProto.PopulationEstimate estimate =
                capture.partial ? null : populationEstimate(capture, aggregate, BigInteger.valueOf(total));
        if (accountedScale != null) {
            // The per-entry estimates are the same inverse-probability sum, so they take the same scale.
            profile.scaleEstimates(accountedScale[0], accountedScale[1]);
        }
        return new CorrelationResult(
                capture,
                selectionMetadata,
                notParsed == null ? null : notParsed.longValueExact(),
                sources,
                samples,
                sourceIndex,
                dictionaries,
                sampleIndex,
                collapsedNanos,
                collapsedNanosByReason,
                stackNanos,
                nativeStacks,
                profile,
                sampling,
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
                thinning,
                narrowedToNanos);
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
        Long clipTo = effectiveToNanos();
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
        return new SourceAggregate(duration, weighted, rows);
    }

    /** The configured {@code --to-ns} bound narrowed further by a ladder-chosen window cut, whichever is tighter. */
    private Long effectiveToNanos() {
        Long configured = limits.toNanos() == null ? null : limits.toNanos().longValueExact();
        if (narrowedToNanos == null) return configured;
        return configured == null ? narrowedToNanos : Math.min(configured, narrowedToNanos);
    }

    private long retainedBytes() {
        return sources.retainedBytes()
                + samples.retainedBytes()
                + sourceIndex.retainedBytes()
                + sampleIndex.retainedBytes()
                + dictionaries.retainedBytes()
                + nativeStacks.retainedBytes();
    }

    /**
     * The aggregate-consistency check: whether the kernel's and the collector's counters prove that the source rows
     * are the whole selected population, or a population short by exactly the sequence contentions it counted.
     */
    private ReportProto.PopulationEstimate populationEstimate(
            CaptureInput capture, SourceAggregate aggregate, BigInteger matchedDuration) throws IOException {
        CaptureProto.KernelCounters kernel = capture.end.getKernelCounters();
        CaptureProto.UserspaceCounters userspace = capture.end.getUserspaceCounters();
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
        BigInteger selected = counter("selectedIntervals", kernel.getSelectedIntervals(), reasons);
        BigInteger eligible = counter("eligibleIntervals", kernel.getEligibleIntervals(), reasons);
        BigInteger rejected = counter("admissionRejections", kernel.getAdmissionRejections(), reasons);
        BigInteger received = counter("receivedObservations", userspace.getReceivedObservations(), reasons);
        BigInteger written = counter("writtenObservations", userspace.getWrittenObservations(), reasons);
        if (!eligible.equals(rejected.add(selected))) {
            reasons.add("eligible-selection-counter-mismatch");
        }
        BigInteger contentions = counter("sequenceContentions", kernel.getSequenceContentions(), reasons);
        nonzero("targetNamespaceFailures", kernel.getTargetNamespaceFailures(), reasons);
        nonzero("ringReserveFailures", kernel.getRingReserveFailures(), reasons);
        nonzero("sequenceExhaustions", kernel.getSequenceExhaustions(), reasons);
        nonzero("lifetimeRejections", kernel.getLifetimeRejections(), reasons);
        nonzero("threadStateFailures", kernel.getThreadStateFailures(), reasons);
        nonzero("writeFailures", userspace.getWriteFailures(), reasons);
        nonzero("pollFailures", userspace.getPollFailures(), reasons);
        // Sequence contention drops a selected interval after the kernel counted it, and counts the drop. When
        // that count alone closes the gap between selection and every downstream count, and nothing else is
        // wrong, the loss is accounted for; any other discrepancy keeps the reasons it always had.
        boolean contended = contentions.signum() != 0;
        boolean rowsMismatch = !selected.equals(sourceRows) || !selected.equals(received) || !selected.equals(written);
        ReportProto.AccountedLoss accountedLoss = null;
        List<String> assumptions = List.of();
        if (contended
                && reasons.isEmpty()
                && selected.subtract(contentions).equals(sourceRows)
                && sourceRows.equals(received)
                && sourceRows.equals(written)) {
            accountedLoss = ReportProto.AccountedLoss.newBuilder()
                    .setIntervals(contentions.longValue())
                    .setFraction(new BigDecimal(contentions)
                            .divide(new BigDecimal(selected), new MathContext(6))
                            .toPlainString())
                    .setReason("sequence-contention")
                    .build();
            assumptions = List.of(CONTENTION_INDEPENDENCE);
            if (new BigDecimal(contentions).compareTo(limits.maxAccountedLoss().multiply(new BigDecimal(selected)))
                    > 0) {
                reasons.add("accounted-loss-above-limit");
            }
        } else {
            if (contended) reasons.add("nonzero-sequenceContentions");
            if (rowsMismatch) reasons.add("selected-source-row-count-mismatch");
        }
        reasons = reasons.stream().distinct().sorted().toList();
        boolean available = reasons.isEmpty();
        ReportProto.PopulationEstimate.Builder estimate = ReportProto.PopulationEstimate.newBuilder()
                .setMethod("inverse-probability-source-duration")
                .setStatus(
                        available
                                ? ReportProto.EstimateStatus.ESTIMATE_STATUS_AVAILABLE
                                : ReportProto.EstimateStatus.ESTIMATE_STATUS_UNAVAILABLE)
                .setScope("completed duration-eligible source intervals")
                .setAdmissionPolicy(sampling.policy())
                .setSourceSelectedObservedDurationNanos(aggregate.duration().longValueExact())
                .setSourceRowsUsed(aggregate.rows())
                .setMatchedSelectedObservedDurationNanos(matchedDuration.longValueExact())
                .setSourceCoverageComplete(available && accountedLoss == null)
                .setStackDeliveryCorrectionApplied(false)
                .addAllUnavailableReasons(reasons)
                .addAllAssumptions(assumptions);
        if (accountedLoss != null) estimate.setAccountedLoss(accountedLoss);
        if (available && accountedLoss != null) {
            accountedScale = new BigInteger[] {selected, sourceRows};
            estimate.setEstimatedDurationNanos(
                    aggregate.estimatedDuration(selected, sourceRows).longValueExact());
        } else if (available) {
            estimate.setEstimatedDurationNanos(aggregate.estimatedDuration().longValueExact());
        }
        return estimate.build();
    }

    /** A counter as an unsigned value; one saturated at the u64 maximum proves nothing. */
    private static BigInteger counter(String name, long value, List<String> reasons) {
        if (value == -1L) reasons.add("saturated-counter-" + name);
        return U64.big(value);
    }

    private static void nonzero(String name, long value, List<String> reasons) {
        counter(name, value, reasons);
        if (value != 0) reasons.add("nonzero-" + name);
    }
}
