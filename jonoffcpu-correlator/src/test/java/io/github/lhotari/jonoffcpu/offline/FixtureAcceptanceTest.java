// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.offline;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The reference numbers the 0.5.0 specs were written against, measured on recordings of an Apache Pulsar broker. The
 * recordings are not part of the repository; run with {@code -Djonoffcpu.fixtures=DIR} (Gradle:
 * {@code -PjonoffcpuFixtures=DIR}) pointing at a directory holding {@code pulsar-broker-2026-09-23-wolfi/} and
 * {@code pulsar-broker-2026-09-23-wolfi-cpu/}, and it is skipped otherwise.
 */
public final class FixtureAcceptanceTest {
    static final String IDLE_BOOKKEEPER =
            "^org\\.apache\\.bookkeeper\\.common\\.collections\\.[\\w$]*BlockingQueue\\.take(All)?$";
    static final String APP = "^org\\.apache\\.";

    private FixtureAcceptanceTest() {}

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** One row of the transforms reference table: the options, then lines and depth for CPU and off-CPU. */
    record Row(String name, List<String> options, int cpuLines, String cpuDepth, int offLines, String offDepth) {}

    static final List<Row> TRANSFORMS = List.of(
            new Row("none", List.of(), 9_839, "28.9", 164, "23.0"),
            new Row("hide", List.of("--hide-from", "preset:jvm-infra"), 9_605, "20.7", 157, "17.6"),
            new Row("trim-root", List.of("--trim-root-from", "preset:jvm-infra"), 9_829, "21.2", 164, "18.4"),
            new Row(
                    "collapse-leaf",
                    List.of("--collapse-leaf-from", "preset:jvm-wait-machinery"),
                    7_392,
                    "24.1",
                    109,
                    "9.6"),
            new Row(
                    "trim-root+collapse-leaf",
                    List.of(
                            "--trim-root-from",
                            "preset:jvm-infra",
                            "--collapse-leaf-from",
                            "preset:jvm-wait-machinery"),
                    7_381,
                    "16.4",
                    109,
                    "5.0"),
            new Row("root-at", List.of("--root-at", APP), 5_231, "9.8", 79, "8.2"),
            new Row(
                    "root-at+collapse-leaf",
                    List.of("--root-at", APP, "--collapse-leaf-from", "preset:jvm-wait-machinery"),
                    4_925,
                    "9.6",
                    78,
                    "4.1"),
            new Row("root-at+leaf-at", List.of("--root-at", APP, "--leaf-at", APP), 1_234, "7.3", 66, "3.6"));

    /** Lines and weight-averaged depth of a collapsed file, as the reference table counts them. */
    static String shape(Path collapsed) throws Exception {
        List<String> lines = Files.readAllLines(collapsed, StandardCharsets.UTF_8);
        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal frames = BigDecimal.ZERO;
        for (String line : lines) {
            int space = line.lastIndexOf(' ');
            BigDecimal value = new BigDecimal(line.substring(space + 1));
            weight = weight.add(value);
            frames = frames.add(
                    value.multiply(BigDecimal.valueOf(line.substring(0, space).split(";", -1).length)));
        }
        return lines.size() + " lines at " + frames.divide(weight, 1, java.math.RoundingMode.HALF_EVEN);
    }

    static Path run(Path output, List<String> args) throws Exception {
        List<String> command = new ArrayList<>(args);
        command.addAll(List.of("--output", output.toString()));
        CommandLineTest.Invocation invocation = CommandLineTest.invoke(command.toArray(String[]::new));
        check(invocation.code() == 0, "Failed: " + args + ": " + invocation);
        return output;
    }

    private static void transforms(Path fixtures, Path dir) throws Exception {
        Path cpu = fixtures.resolve("pulsar-broker-2026-09-23-wolfi-cpu/broker-cpu.collapsed");
        Path profile = fixtures.resolve("pulsar-broker-2026-09-23-wolfi/jonoffcpu-offcpu-profile.pb");
        for (int index = 0; index < TRANSFORMS.size(); index++) {
            Row row = TRANSFORMS.get(index);
            List<String> cpuArgs = new ArrayList<>(List.of("stacks", "--collapsed-input", cpu.toString()));
            cpuArgs.addAll(row.options());
            String cpuShape = shape(run(dir.resolve("cpu-" + index + ".collapsed"), cpuArgs));
            List<String> offArgs = new ArrayList<>(List.of(
                    "stacks",
                    "--profile",
                    profile.toString(),
                    "--exclude-from",
                    "preset:jvm-idle",
                    "--exclude",
                    IDLE_BOOKKEEPER));
            offArgs.addAll(row.options());
            String offShape = shape(run(dir.resolve("off-" + index + ".collapsed"), offArgs));
            String expectedCpu = row.cpuLines() + " lines at " + row.cpuDepth();
            String expectedOff = row.offLines() + " lines at " + row.offDepth();
            check(
                    cpuShape.equals(expectedCpu) && offShape.equals(expectedOff),
                    row.name() + ": CPU " + cpuShape + " (expected " + expectedCpu + "), off-CPU " + offShape
                            + " (expected " + expectedOff + ")");
            System.out.println(row.name() + ": CPU " + cpuShape + ", off-CPU " + offShape);
        }
    }

    public static void main(String[] args) throws Exception {
        String fixtures = System.getProperty("jonoffcpu.fixtures", "");
        if (fixtures.isEmpty()) {
            System.out.println("Skipping the fixture acceptance checks: -Djonoffcpu.fixtures is not set");
            return;
        }
        Path dir = Files.createTempDirectory("jonoffcpu-fixture-acceptance-");
        try {
            transforms(Path.of(fixtures), dir);
            System.out.println("Fixture acceptance checks passed");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
