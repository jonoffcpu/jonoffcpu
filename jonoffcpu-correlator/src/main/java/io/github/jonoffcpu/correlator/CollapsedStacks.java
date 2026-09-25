// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Any collapsed file ({@code root;…;leaf weight}) as input to the same filters and transforms a stack profile gets,
 * such as a CPU, allocation or lock view the converter wrote from the recording, or jonoffcpu's own collapsed file.
 * Weights keep the input's unit, integer or decimal.
 *
 * <p>The converter's Java frames are normalised first: their compilation marker ({@code _[j]}, {@code _[i]},
 * {@code _[0]}, {@code _[1]}) is removed and the {@code /} of their class names becomes {@code .}. Every other frame is
 * kept verbatim. A frame with a marker is Java; in a file with markers every other frame is native, and in a file
 * without them a frame is native when its name reads as native ({@link StackTransforms#looksNative}, or a space).
 */
final class CollapsedStacks {
    /** One input line: its frames, root first, and its weight. */
    record Line(List<StackProfile.Frame> frames, BigDecimal weight) {}

    /**
     * The rendered lines, the kept total, what the filter removed, and the kept lines that {@link
     * StackTransforms.UnmatchedRoot#HIDE} left out of the rendered lines and the total.
     */
    record Slice(
            Map<String, BigDecimal> weights,
            BigDecimal total,
            long filteredLines,
            BigDecimal filteredWeight,
            long hiddenLines,
            BigDecimal hiddenWeight) {}

    private static final Pattern JAVA_MARKER = Pattern.compile("_\\[[ji01]\\]$");

    private CollapsedStacks() {}

    static List<Line> read(Path file) throws IOException {
        List<List<String>> names = new ArrayList<>();
        List<List<Boolean>> java = new ArrayList<>();
        List<BigDecimal> weights = new ArrayList<>();
        boolean marked = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String text;
            int number = 0;
            while ((text = reader.readLine()) != null) {
                number++;
                if (text.isBlank()) continue;
                int space = text.lastIndexOf(' ');
                BigDecimal weight;
                try {
                    if (space <= 0) throw new NumberFormatException();
                    weight = new BigDecimal(text.substring(space + 1));
                    if (weight.signum() < 0) throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    throw new IOException(file + " line " + number + " is not 'frames weight': " + text);
                }
                List<String> frames = new ArrayList<>();
                List<Boolean> kinds = new ArrayList<>();
                for (String frame : text.substring(0, space).split(";", -1)) {
                    boolean isJava = JAVA_MARKER.matcher(frame).find();
                    marked |= isJava;
                    frames.add(isJava ? frame.substring(0, frame.length() - 4).replace('/', '.') : frame);
                    kinds.add(isJava);
                }
                names.add(frames);
                java.add(kinds);
                weights.add(weight);
            }
        }
        // Frames are shared between lines, as a profile's are, so transforms can memoise per distinct frame.
        Map<String, StackProfile.Frame> frames = new HashMap<>();
        List<Line> lines = new ArrayList<>(names.size());
        for (int index = 0; index < names.size(); index++) {
            List<StackProfile.Frame> stack = new ArrayList<>(names.get(index).size());
            for (int frame = 0; frame < names.get(index).size(); frame++) {
                String name = names.get(index).get(frame);
                boolean isJava = java.get(index).get(frame)
                        || !marked && !StackTransforms.looksNative(name) && name.indexOf(' ') < 0;
                stack.add(frames.computeIfAbsent(
                        (isJava ? "J" : "N") + name,
                        key -> new StackProfile.Frame(
                                isJava ? StackProfile.Kind.JAVA : StackProfile.Kind.JFR_NATIVE, name, "")));
            }
            lines.add(new Line(List.copyOf(stack), weights.get(index)));
        }
        return lines;
    }

    /** Filters on the normalised, untransformed frames, then transforms and merges. */
    static Slice render(
            List<Line> lines,
            StackProfileRenderer.Filter filter,
            StackTransforms transforms,
            StackProfileRenderer.PackageNames packages) {
        StackTransforms.Compiled transform = transforms.compile();
        Map<StackProfile.Frame, String> shown = new HashMap<>();
        Map<String, BigDecimal> weights = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        long filteredLines = 0;
        BigDecimal filteredWeight = BigDecimal.ZERO;
        long hiddenLines = 0;
        BigDecimal hiddenWeight = BigDecimal.ZERO;
        for (Line line : lines) {
            if (!filter.keeps(
                    line.frames().stream().map(StackProfile.Frame::name).toList())) {
                filteredLines++;
                filteredWeight = filteredWeight.add(line.weight());
                continue;
            }
            List<StackProfile.Frame> stack = transform.apply(line.frames());
            if (StackTransforms.hidden(line.frames(), stack)) {
                hiddenLines++;
                hiddenWeight = hiddenWeight.add(line.weight());
                continue;
            }
            StringBuilder key = new StringBuilder();
            StackProfileRenderer.appendJava(key, stack, packages, shown);
            weights.merge(key.toString(), line.weight(), BigDecimal::add);
            total = total.add(line.weight());
        }
        return new Slice(weights, total, filteredLines, filteredWeight, hiddenLines, hiddenWeight);
    }

    static void write(Slice slice, BufferedWriter writer) throws IOException {
        for (var line : slice.weights().entrySet()) {
            writer.write(line.getKey());
            writer.write(' ');
            writer.write(line.getValue().toPlainString());
            writer.newLine();
        }
    }
}
