// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Frame-level transforms of a Java stack, applied after the {@link StackProfileRenderer.Filter filters} have kept an
 * entry, so filters always see the untransformed stack. They change what a line looks like, never which intervals it
 * holds: lines that transform to the same text merge and their weights add, so totals are unchanged.
 *
 * <p>The order is fixed: {@code canonical names → hide → trim root → root at → leaf at → collapse leaf}. Package
 * names and the thread frame are applied afterwards by the renderer, as display. Patterns are searched for with
 * {@link java.util.regex.Matcher#find()} in each frame's full name, as filters are; anchor them with {@code ^…$}.
 */
record StackTransforms(
        boolean canonicalNames,
        List<Sourced> hide,
        List<Sourced> trimRoot,
        List<Sourced> rootAt,
        boolean keepUnmatchedRoot,
        List<Sourced> leafAt,
        List<Sourced> collapseLeaf,
        boolean categoryLabel,
        ThreadFrame threadFrame) {

    /** A pattern and where it came from: {@code inline}, a file path, or {@code preset:NAME}. */
    record Sourced(String pattern, String source) {
        Pattern compiled() {
            return Pattern.compile(pattern);
        }
    }

    /** What, if anything, precedes each line's stack: nothing, the thread's name, or its pool's name. */
    enum ThreadFrame {
        NONE,
        NAME,
        POOL;

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    static final StackTransforms NONE = new StackTransforms(
            false, List.of(), List.of(), List.of(), false, List.of(), List.of(), false, ThreadFrame.NONE);

    /** The single frame an entry without any {@code --root-at} match is rendered as. */
    static final String NO_APPLICATION_FRAME = "[no application frame]";

    /** A generated class's address: a lambda's, or a method handle's lambda form. */
    private static final Pattern GENERATED_ADDRESS =
            Pattern.compile("(\\$\\$Lambda|LambdaForm\\$(?:MH|DMH))(?:\\$\\d+)?[./]0x\\p{XDigit}+");

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    StackTransforms {
        hide = List.copyOf(hide);
        trimRoot = List.copyOf(trimRoot);
        rootAt = List.copyOf(rootAt);
        leafAt = List.copyOf(leafAt);
        collapseLeaf = List.copyOf(collapseLeaf);
    }

    /** Whether any transform of the Java stack is in effect; the thread frame is display only. */
    boolean active() {
        return canonicalNames
                || !hide.isEmpty()
                || !trimRoot.isEmpty()
                || !rootAt.isEmpty()
                || !leafAt.isEmpty()
                || !collapseLeaf.isEmpty();
    }

    /** {@code name} without generated-class addresses, so the same lambda compares equal across runs. */
    static String canonicalName(String name) {
        if (name.indexOf("0x") < 0) return name;
        return GENERATED_ADDRESS
                .matcher(name)
                .replaceAll(match -> match.group(1).startsWith("$$")
                        ? "\\$\\$Lambda"
                        : java.util.regex.Matcher.quoteReplacement(match.group(1)));
    }

    /** A thread name's pool: every run of digits replaced by {@code #}, so {@code pulsar-io-3-25} is {@code pulsar-io-#-#}. */
    static String poolName(String thread) {
        return DIGITS.matcher(thread).replaceAll("#");
    }

    /**
     * Compiled once per render; {@link #apply} is then a pure function of the stack, so a caller can memoise it per
     * distinct stack.
     */
    Compiled compile() {
        return new Compiled(this);
    }

    static final class Compiled {
        private final StackTransforms transforms;
        private final List<Pattern> hide;
        private final List<Pattern> trimRoot;
        private final List<Pattern> rootAt;
        private final List<Pattern> leafAt;
        private final List<Pattern> collapseLeaf;

        private Compiled(StackTransforms transforms) {
            this.transforms = transforms;
            hide = compile(transforms.hide);
            trimRoot = compile(transforms.trimRoot);
            rootAt = compile(transforms.rootAt);
            leafAt = compile(transforms.leafAt);
            collapseLeaf = compile(transforms.collapseLeaf);
        }

        private static List<Pattern> compile(List<Sourced> patterns) {
            return patterns.stream().map(Sourced::compiled).toList();
        }

        /** The transformed stack, root first; never empty for a non-empty input. */
        List<StackProfile.Frame> apply(List<StackProfile.Frame> stack) {
            if (stack == null || stack.isEmpty() || !transforms.active()) return stack;
            List<StackProfile.Frame> frames = new ArrayList<>(stack.size());
            for (StackProfile.Frame frame : stack) {
                frames.add(
                        transforms.canonicalNames
                                ? new StackProfile.Frame(frame.kind(), canonicalName(frame.name()), frame.module())
                                : frame);
            }
            if (!hide.isEmpty()) {
                List<StackProfile.Frame> kept = new ArrayList<>(frames.size());
                for (StackProfile.Frame frame : frames) {
                    if (!matches(hide, frame)) kept.add(frame);
                }
                // A stack of nothing but hidden frames keeps its leaf, so its time stays attributable.
                frames = kept.isEmpty() ? List.of(frames.get(frames.size() - 1)) : kept;
            }
            if (!trimRoot.isEmpty()) {
                int start = 0;
                while (start < frames.size() && matches(trimRoot, frames.get(start))) start++;
                frames = frames.subList(Math.min(start, frames.size() - 1), frames.size());
            }
            if (!rootAt.isEmpty()) {
                int start = -1;
                for (int index = 0; index < frames.size() && start < 0; index++) {
                    if (matches(rootAt, frames.get(index))) start = index;
                }
                if (start >= 0) {
                    frames = frames.subList(start, frames.size());
                } else if (!transforms.keepUnmatchedRoot) {
                    return List.of(synthetic(NO_APPLICATION_FRAME));
                }
            }
            if (!leafAt.isEmpty()) {
                for (int index = frames.size() - 1; index >= 0; index--) {
                    if (matches(leafAt, frames.get(index))) {
                        frames = frames.subList(0, index + 1);
                        break;
                    }
                }
            }
            if (!collapseLeaf.isEmpty()) {
                int start = frames.size();
                while (start > 0 && matches(collapseLeaf, frames.get(start - 1))) start--;
                if (start < frames.size()) {
                    StackProfile.Frame entry = frames.get(start);
                    List<StackProfile.Frame> collapsed = new ArrayList<>(frames.subList(0, start));
                    String category = transforms.categoryLabel ? category(entry) : null;
                    collapsed.add(category == null ? entry : synthetic(category));
                    frames = collapsed;
                }
            }
            return List.copyOf(frames);
        }

        private static boolean matches(List<Pattern> patterns, StackProfile.Frame frame) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(frame.name()).find()) return true;
            }
            return false;
        }
    }

    /** A frame the transforms made up; native-kinded so that no package-names mode rewrites it. */
    static StackProfile.Frame synthetic(String name) {
        return new StackProfile.Frame(StackProfile.Kind.JFR_NATIVE, name, "");
    }

    /**
     * The category of a collapsed wait, decided by the entry frame of its machinery; null when none applies, in
     * which case the entry frame itself is kept.
     */
    static String category(StackProfile.Frame entry) {
        String name = entry.name();
        if (name.endsWith("_[k]")) return "[kernel]";
        if (name.contains("complete_monitor_locking")
                || name.contains("monitorenter")
                || name.contains("ObjectMonitor::enter")) {
            return "[monitor]";
        }
        if (name.startsWith("java.util.concurrent.locks.LockSupport.park")
                || name.equals("jdk.internal.misc.Unsafe.park")
                || name.equals("sun.misc.Unsafe.park")) {
            return "[park]";
        }
        if (name.startsWith("java.lang.Object.wait")) return "[wait]";
        if (name.startsWith("java.lang.Thread.sleep")) return "[sleep]";
        if (name.startsWith("java.util.concurrent.locks.")) {
            String method = name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
            if (method.contains("lock") || method.contains("acquire")) return "[lock]";
        }
        if (isNative(entry)) return "[native]";
        return null;
    }

    /** A native frame by its kind, or, for inputs without kinds, by a name that reads as native. */
    static boolean isNative(StackProfile.Frame frame) {
        return frame.kind() != StackProfile.Kind.JAVA || looksNative(frame.name());
    }

    /** The name rule for frames without a kind: a C++ {@code ::}, a shared library, a path, a placeholder, a kernel frame. */
    static boolean looksNative(String name) {
        return name.contains("::")
                || name.contains(".so.")
                || name.startsWith("/")
                || name.startsWith("[")
                || name.endsWith("_[k]");
    }

    /** The options in effect, with every pattern and its source, for a summary. */
    JsonObject report() {
        JsonObject report = new JsonObject();
        report.addProperty("canonicalNames", canonicalNames);
        report.add("hide", patterns(hide));
        report.add("trimRoot", patterns(trimRoot));
        report.add("rootAt", patterns(rootAt));
        report.addProperty("rootAtUnmatched", keepUnmatchedRoot ? "keep" : "bucket");
        report.add("leafAt", patterns(leafAt));
        report.add("collapseLeaf", patterns(collapseLeaf));
        report.addProperty("collapseLeafLabel", categoryLabel ? "category" : "frame");
        report.addProperty("threadFrame", threadFrame.label());
        return report;
    }

    static JsonArray patterns(List<Sourced> patterns) {
        JsonArray array = new JsonArray();
        for (Sourced pattern : patterns) {
            JsonObject item = new JsonObject();
            item.addProperty("pattern", pattern.pattern());
            item.addProperty("source", pattern.source());
            array.add(item);
        }
        return array;
    }
}
