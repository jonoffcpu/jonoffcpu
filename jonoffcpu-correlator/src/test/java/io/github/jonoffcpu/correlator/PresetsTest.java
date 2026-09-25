// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code preset:*}: every bundled preset meant for the option, as each preset's {@code # options:} line says. */
class PresetsTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "--hide-from, preset:jvm-dispatch",
        "--waiting-from, preset:jvm-waiting",
        "--exclude-from, preset:jvm-waiting",
        "--trim-root-from, preset:jvm-infra",
        "--collapse-leaf-from, preset:jvm-wait-machinery",
        "--machinery-from, preset:jvm-wait-machinery"
    })
    void presetsOfAnOption(String option, String presets) throws Exception {
        assertThat(Presets.all(option)).isEqualTo(List.of(presets.split(" ")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--include-from", "--root-at-from", "--leaf-at-from", "--app-from"})
    void optionsWithoutPresets(String option) {
        assertThatThrownBy(() -> Presets.all(option))
                .isInstanceOf(Presets.Unusable.class)
                .hasMessageContaining(option);
    }

    private static StackProfile.Entry entry(String thread, long nanos, String... frames) {
        List<StackProfile.Frame> stack = Arrays.stream(frames)
                .map(name -> new StackProfile.Frame(StackProfile.Kind.JAVA, name, ""))
                .toList();
        return new StackProfile.Entry(stack, null, null, OffCpuReason.BLOCKED, 1, thread, 1, nanos, 0);
    }

    private static Path profile(Path dir) throws Exception {
        Path profile = dir.resolve("profile.pb");
        new StackProfile(
                        new StackProfile.Header(List.of(), List.of("reason", "thread"), false, null, "", List.of()),
                        List.of(entry(
                                "pool-1",
                                2000,
                                "java.lang.Thread.run",
                                "java.util.concurrent.FutureTask.run",
                                "x.Svc$$Lambda.run",
                                "x.Svc.lambda$go$0",
                                "x.Pool.dispatch",
                                "x.Svc.work")))
                .write(profile);
        return profile;
    }

    private static CommandLineFixture.Invocation stacks(Path output, Path profile, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(
                List.of("stacks", "--profile", profile.toString(), "--output", output.toString()));
        command.addAll(List.of(args));
        return CommandLineFixture.invoke(command.toArray(String[]::new));
    }

    /**
     * {@code --hide-from 'preset:*'} is {@code --hide-from preset:jvm-dispatch}, combines with a file of the
     * consumer's own, and is recorded as the presets it expanded to.
     */
    @Test
    void wildcardInAFromOption(@TempDir Path dir) throws Exception {
        Path profile = profile(dir);
        Path named = dir.resolve("named.collapsed");
        Path wildcard = dir.resolve("wildcard.collapsed");
        Path summary = dir.resolve("wildcard.json");
        assertThat(stacks(named, profile, "--hide-from", "preset:jvm-dispatch").code())
                .isZero();
        assertThat(stacks(wildcard, profile, "--hide-from", "preset:*", "--summary", summary.toString())
                        .code())
                .isZero();
        assertThat(wildcard).hasSameTextualContentAs(named);
        assertThat(Files.readString(wildcard))
                .isEqualTo("java.lang.Thread.run;x.Svc.lambda$go$0;x.Pool.dispatch;x.Svc.work 2\n");
        AnalysisProto.SliceSummary json = CorrelationFixture.parse(summary, AnalysisProto.SliceSummary.newBuilder())
                .build();
        assertThat(json.getTransforms().getHideList())
                .as("Recorded as the preset it expanded to")
                .extracting(AnalysisProto.SourcedPattern::getSource)
                .containsOnly("preset:jvm-dispatch");

        Path own = Files.writeString(dir.resolve("own-hide.txt"), "^x\\.Pool\\.dispatch$\n");
        Path combined = dir.resolve("combined.collapsed");
        assertThat(stacks(combined, profile, "--hide-from", "preset:*", "--hide-from", own.toString())
                        .code())
                .isZero();
        assertThat(Files.readString(combined))
                .as("The preset's patterns and the file's")
                .isEqualTo("java.lang.Thread.run;x.Svc.lambda$go$0;x.Svc.work 2\n");
    }

    @Test
    void wildcardWithoutPresetsIsAUsageError(@TempDir Path dir) throws Exception {
        Path profile = profile(dir);
        CommandLineFixture.usageError(
                "--root-at-from preset:* matches no bundled preset",
                "stacks",
                "--profile",
                profile.toString(),
                "--output",
                dir.resolve("refused.collapsed").toString(),
                "--root-at-from",
                "preset:*");
        CommandLineFixture.usageError(
                "Unknown preset: nope",
                "stacks",
                "--profile",
                profile.toString(),
                "--output",
                dir.resolve("unknown.collapsed").toString(),
                "--hide-from",
                "preset:nope");
    }

    @Test
    void listingNamesTheOptions() throws Exception {
        assertThat(Presets.listing())
                .contains(
                        "preset:jvm-dispatch (preset:* of --hide-from)\n",
                        "preset:jvm-waiting (preset:* of --waiting-from, --exclude-from)\n",
                        "preset:jvm-wait-machinery (preset:* of --collapse-leaf-from, --machinery-from)\n",
                        "preset:jvm-infra (preset:* of --trim-root-from)\n");
    }
}
