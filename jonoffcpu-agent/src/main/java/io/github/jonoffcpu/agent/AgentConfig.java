// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import io.github.jonoffcpu.capture.CaptureProto.Sampling;
import io.github.jonoffcpu.capture.CaptureProto.SignalDelivery;
import io.github.jonoffcpu.capture.CaptureProto.TimeSplit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

record AgentConfig(
        Path correlationOutput,
        Path asyncProfilerLibrary,
        Path nativeCollectorLibrary,
        Path jfrOutput,
        long targetPid,
        String asyncProfilerOptions,
        SignalDelivery signalDelivery,
        Sampling sampling,
        TimeSplit timeSplit,
        long nativeStopTimeoutMillis,
        long deliveryGraceMillis,
        long shutdownTimeoutMillis) {
    private static final Set<String> CONFIG_KEYS = Set.of(
            "correlationOutput",
            "asyncProfilerLibrary",
            "nativeCollectorLibrary",
            "jfrOutput",
            "targetPid",
            "asyncProfilerOptions",
            "signalDelivery",
            "sampling",
            "timeSplit",
            "nativeStopTimeoutMillis",
            "deliveryGraceMillis",
            "shutdownTimeoutMillis");
    /** The largest configuration the agent reads, as YAML text or as a file. */
    static final int MAX_CONFIG_BYTES = 64 * 1024;

    static AgentConfig parse(String argument) throws IOException {
        if (argument == null || argument.isBlank()) {
            throw new IllegalArgumentException("Agent argument must be a YAML mapping or a path to a YAML config");
        }
        String yaml = argument;
        if (argument.indexOf('\n') < 0 && argument.indexOf('\r') < 0) {
            Path candidate = Path.of(argument);
            if (Files.isRegularFile(candidate)) {
                byte[] bytes = Files.readAllBytes(candidate);
                if (bytes.length > MAX_CONFIG_BYTES) {
                    throw new IllegalArgumentException("Agent config exceeds 64 KiB");
                }
                yaml = new String(bytes, StandardCharsets.UTF_8);
            }
        }
        if (yaml.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new IllegalArgumentException("Agent config exceeds 64 KiB");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_CONFIG_BYTES);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid agent YAML config", error);
        }
        if (!(loaded instanceof Map<?, ?> mapping)) {
            throw new IllegalArgumentException("Agent YAML config must be a mapping");
        }
        ConfigValues.requireKeys(mapping, CONFIG_KEYS, "agent YAML config");
        Map<Object, Object> value = new LinkedHashMap<>(mapping);
        if (!value.containsKey("asyncProfilerLibrary") && !value.containsKey("nativeCollectorLibrary")) {
            NativeBundleLoader.Bundle bundle = NativeBundleLoader.load();
            value.put("asyncProfilerLibrary", bundle.profiler().toString());
            value.put("nativeCollectorLibrary", bundle.agent().toString());
        } else if (!value.containsKey("asyncProfilerLibrary") || !value.containsKey("nativeCollectorLibrary")) {
            throw new IllegalArgumentException(
                    "asyncProfilerLibrary and nativeCollectorLibrary must either both be omitted or both be present");
        }
        if (!value.containsKey("jfrOutput") && value.containsKey("asyncProfilerOptions")) {
            String file = optionValue(ConfigValues.requireString(value, "asyncProfilerOptions"), "file");
            if (file != null) {
                value.put("jfrOutput", file);
            }
        }
        return fromMap(value);
    }

    static AgentConfig parseNativeOptions(String arguments, Path nativeLibrary) {
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("Native agent options are required");
        }
        String[] tokens = arguments.split(",", -1);
        Map<String, Object> value = new LinkedHashMap<>();
        // Sampling keys build the same nested mapping as the YAML form; the mapping only exists when one is given.
        Map<String, Object> sampling = new LinkedHashMap<>();
        Map<String, Object> admission = new LinkedHashMap<>();
        sampling.put("admission", admission);
        int asprof = -1;
        for (int i = 0; i < tokens.length; i++) {
            int equals = tokens[i].indexOf('=');
            if (equals <= 0 || equals == tokens[i].length() - 1) {
                throw new IllegalArgumentException("Malformed native agent option: " + tokens[i]);
            }
            String key = tokens[i].substring(0, equals);
            String option = tokens[i].substring(equals + 1);
            if (key.equals("asprofpath")) {
                value.put("asyncProfilerLibrary", option);
                asprof = i;
                break;
            }
            if (key.startsWith("sampling-") || key.endsWith("-micros")) value.put("sampling", sampling);
            switch (key) {
                case "jonoffcpudelivery" -> value.put("signalDelivery", option);
                case "jonoffcpuoutput" -> value.put("correlationOutput", option);
                case "sampling-policy" -> admission.put("policy", option);
                case "sampling-reasons" -> sampling.put("reasons", SamplingConfig.reasonsOption(key, option));
                case "sampling-probability" -> {
                    SamplingConfig.parseProbability(key, option);
                    admission.put("probability", option);
                }
                case "record-all-above-micros" -> admission.put("recordAllAboveMicros", parseOptionLong(key, option));
                case "min-off-cpu-micros" -> sampling.put("minOffCpuMicros", parseOptionLong(key, option));
                case "max-off-cpu-micros" -> sampling.put("maxOffCpuMicros", parseOptionLong(key, option));
                case "time-split" -> value.put("timeSplit", Map.of("source", option));
                case "nativestoptimeoutmillis" -> value.put("nativeStopTimeoutMillis", parseOptionLong(key, option));
                case "deliverygracemillis" -> value.put("deliveryGraceMillis", parseOptionLong(key, option));
                case "shutdowntimeoutmillis" -> value.put("shutdownTimeoutMillis", parseOptionLong(key, option));
                default ->
                    throw new IllegalArgumentException(
                            "Unknown JONOFFCPU native agent option before asprofpath: " + key);
            }
        }
        if (asprof < 0) throw new IllegalArgumentException("Native agent option asprofpath is required");
        if (asprof + 1 >= tokens.length)
            throw new IllegalArgumentException("Async-profiler options must follow asprofpath");
        String profilerOptions = String.join(",", Arrays.copyOfRange(tokens, asprof + 1, tokens.length));
        value.put("asyncProfilerOptions", profilerOptions);
        value.put(
                "nativeCollectorLibrary",
                nativeLibrary.toAbsolutePath().normalize().toString());
        String file = optionValue(profilerOptions, "file");
        if (file != null) value.put("jfrOutput", file);
        return fromMap(value);
    }

    private static AgentConfig fromMap(Map<?, ?> value) {
        long currentPid = ProcessHandle.current().pid();
        long targetPid = ConfigValues.optionalInteger(value, "targetPid", currentPid, 1, 0xffffffffL);
        if (targetPid != currentPid) {
            throw new IllegalArgumentException("Version 1 agent can only target its own JVM process");
        }
        // Sampling is mandatory so that a profiler-only run is always an explicit decision.
        Sampling sampling = SamplingConfig.parse(ConfigValues.requireMap(value, "sampling"));
        // The split is on by default; a kernel without the accounting needs an explicit source off.
        TimeSplit timeSplit = value.containsKey("timeSplit")
                ? TimeSplitConfig.parse(ConfigValues.requireMap(value, "timeSplit"))
                : TimeSplitConfig.DEFAULT;
        AgentConfig config = new AgentConfig(
                absolute(value, "correlationOutput"),
                absolute(value, "asyncProfilerLibrary"),
                absolute(value, "nativeCollectorLibrary"),
                value.containsKey("jfrOutput") ? absolute(value, "jfrOutput") : null,
                targetPid,
                ConfigValues.requireString(value, "asyncProfilerOptions"),
                value.containsKey("signalDelivery")
                        ? CaptureProtocol.parseDelivery(ConfigValues.requireString(value, "signalDelivery"))
                        : SignalDelivery.SIGNAL_DELIVERY_QUEUED,
                sampling,
                timeSplit,
                ConfigValues.optionalInteger(value, "nativeStopTimeoutMillis", 30_000, 1, 3_600_000),
                ConfigValues.optionalInteger(value, "deliveryGraceMillis", 100, 0, 60_000),
                ConfigValues.optionalInteger(value, "shutdownTimeoutMillis", 10_000, 1, 3_600_000));
        config.validatePaths();
        config.validateProfilerOptions();
        return config;
    }

    private static long parseOptionLong(String key, String value) {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Invalid decimal native agent option: " + key);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Out-of-range native agent option: " + key, error);
        }
    }

    private static Path absolute(Map<?, ?> value, String name) {
        Path path = Path.of(ConfigValues.requireString(value, name))
                .toAbsolutePath()
                .normalize();
        if (path.toString().indexOf(',') >= 0
                || path.toString().indexOf('\n') >= 0
                || path.toString().indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " contains characters unsupported by the AP command protocol");
        }
        return path;
    }

    private void validatePaths() {
        Path correlationParent = correlationOutput.getParent();
        if (correlationParent == null || !Files.isDirectory(correlationParent)) {
            throw new IllegalArgumentException(
                    "Correlation output parent must be an existing directory: " + correlationOutput);
        }
        if (Files.exists(correlationOutput)) {
            throw new IllegalArgumentException("Correlation output must not already exist: " + correlationOutput);
        }
        if (!Files.isRegularFile(asyncProfilerLibrary)) {
            throw new IllegalArgumentException("asyncProfilerLibrary is not a file: " + asyncProfilerLibrary);
        }
        if (!Files.isRegularFile(nativeCollectorLibrary)) {
            throw new IllegalArgumentException("nativeCollectorLibrary is not a file: " + nativeCollectorLibrary);
        }
        if (jfrOutput != null) {
            Path parent = jfrOutput.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw new IllegalArgumentException("JFR output parent must be an existing directory: " + jfrOutput);
            }
            if (Files.exists(jfrOutput)) {
                throw new IllegalArgumentException("JFR output must not already exist: " + jfrOutput);
            }
            if (jfrOutput.equals(correlationOutput)) {
                throw new IllegalArgumentException("Correlation and JFR output paths must be distinct");
            }
            try {
                Path correlationReal = correlationParent.toRealPath().resolve(correlationOutput.getFileName());
                Path jfrReal = parent.toRealPath().resolve(jfrOutput.getFileName());
                if (correlationReal.equals(jfrReal)) {
                    throw new IllegalArgumentException("Correlation and JFR output paths alias the same location");
                }
            } catch (IOException error) {
                throw new IllegalArgumentException("Cannot resolve output parent identity", error);
            }
        }
    }

    private void validateProfilerOptions() {
        if (signalDelivery != SignalDelivery.SIGNAL_DELIVERY_QUEUED
                && signalDelivery != SignalDelivery.SIGNAL_DELIVERY_COALESCING) {
            throw new IllegalArgumentException("Invalid signal delivery policy: " + signalDelivery);
        }
        if (asyncProfilerOptions.isBlank()
                || asyncProfilerOptions.indexOf('\n') >= 0
                || asyncProfilerOptions.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("asyncProfilerOptions must be a nonempty AP option list");
        }
        Set<String> actions = Set.of("start", "resume", "stop", "dump", "status", "metrics", "list", "version");
        for (String token : asyncProfilerOptions.split(",")) {
            String key = token.contains("=") ? token.substring(0, token.indexOf('=')) : token;
            if (actions.contains(key)
                    || key.equals("signalcookie")
                    || key.equals("signalid")
                    || key.equals("signalepoch")) {
                throw new IllegalArgumentException("asyncProfilerOptions contains controller-owned option: " + key);
            }
        }
        String optionFile = optionValue(asyncProfilerOptions, "file");
        if (optionFile != null && optionFile.indexOf('%') >= 0) {
            throw new IllegalArgumentException("AP file patterns are not supported for owned capture output");
        }
        if ((optionFile == null) != (jfrOutput == null)
                || optionFile != null
                        && !Path.of(optionFile).toAbsolutePath().normalize().equals(jfrOutput)) {
            throw new IllegalArgumentException("JFR file option and jfrOutput must identify the same unique path");
        }
    }

    private static String optionValue(String options, String wanted) {
        String found = null;
        for (String token : options.split(",")) {
            int equals = token.indexOf('=');
            String key = equals < 0 ? token : token.substring(0, equals);
            if (key.equals(wanted)) {
                if (equals < 0 || equals == token.length() - 1 || found != null) {
                    throw new IllegalArgumentException("Invalid or duplicate AP option: " + wanted);
                }
                found = token.substring(equals + 1);
            }
        }
        return found;
    }

    /**
     * Admission policy {@code none} disables the eBPF source entirely: the agent then only starts async-profiler,
     * so the same agent configuration acts as an off switch without unloading jonoffcpu.
     */
    boolean profilerOnly() {
        return SamplingConfig.profilerOnly(sampling);
    }
}
