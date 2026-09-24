// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Ulimit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.utility.DockerImageName;

/**
 * A privileged Linux container that runs the packaged agent against the host kernel, for one C library: the glibc
 * or musl Corretto image the build pins, whose JVM selects the matching bundle from the agent JAR. The agent JAR, the
 * workload classes and the kernel's BTF are mounted read-only, tracefs read-write, and {@code /out} is a host
 * directory the capture lands in. The build passes the images for the C libraries it selected, and the JARs.
 */
record AgentRuntime(String libc, String image) {
    static final String OUT = "/out";
    private static final String AGENT = "/artifacts/jonoffcpu-agent.jar";
    private static final String WORKLOADS = "/workloads";

    /** One runtime for each C library the build selected for this host's architecture. */
    static List<AgentRuntime> selected() {
        String images = System.getProperty("jonoffcpu.runtimeImages", "");
        List<AgentRuntime> runtimes = new ArrayList<>();
        for (String entry : images.split(",")) {
            if (entry.isBlank()) continue;
            int separator = entry.indexOf('=');
            runtimes.add(new AgentRuntime(entry.substring(0, separator), entry.substring(separator + 1)));
        }
        return runtimes;
    }

    static Path agentJar() {
        return property("jonoffcpu.agentJar");
    }

    static Path correlatorJar() {
        return property("jonoffcpu.correlatorJar");
    }

    private static Path property(String name) {
        String value = System.getProperty(name);
        assertThat(value).as("system property " + name).isNotBlank();
        return Path.of(value);
    }

    /**
     * A container that runs {@code workload} with {@code args} under the agent, configured by {@code config} (the
     * agent's YAML, written to {@code out}). The container's own process is the JVM, so stopping or killing the
     * container signals it.
     */
    GenericContainer<?> agent(Path out, String config, String workload, String... args) throws Exception {
        Files.writeString(out.resolve("jonoffcpu.yaml"), config);
        List<String> command = new ArrayList<>();
        if (libc.equals("musl")) {
            // The bundle is chosen from the JVM's own loader, so the image must really be musl.
            command.addAll(List.of(
                    "sh",
                    "-c",
                    "grep -q ld-musl /proc/self/maps && test -f /lib/ld-musl-*.so.1"
                            + " || { echo 'not a musl runtime' >&2; exit 97; }; exec \"$@\"",
                    "sh"));
        }
        command.addAll(List.of(
                "java",
                "--enable-native-access=ALL-UNNAMED",
                "-Xms128m",
                "-Xmx256m",
                "-javaagent:" + AGENT + "=" + OUT + "/jonoffcpu.yaml",
                "-cp",
                AGENT + ":" + WORKLOADS,
                workload));
        command.addAll(Arrays.asList(args));
        return container(out)
                .withPrivilegedMode(true)
                .withFileSystemBind(agentJar().toString(), AGENT, BindMode.READ_ONLY)
                .withFileSystemBind(System.getProperty("jonoffcpu.workloadClasses"), WORKLOADS, BindMode.READ_ONLY)
                .withFileSystemBind("/sys/kernel/btf", "/sys/kernel/btf", BindMode.READ_ONLY)
                .withFileSystemBind("/sys/kernel/tracing", "/sys/kernel/tracing", BindMode.READ_WRITE)
                .withCreateContainerCmdModifier(create ->
                        create.getHostConfig().withMemory(1L << 30).withUlimits(List.of(new Ulimit("core", 0L, 0L))))
                .withCommand(command.toArray(String[]::new));
    }

    private GenericContainer<?> container(Path out) {
        return new GenericContainer<>(DockerImageName.parse(image))
                .withFileSystemBind(out.toString(), OUT, BindMode.READ_WRITE)
                .withWorkingDirectory(OUT);
    }

    /** Starts the container and waits, at most {@code timeout}, for its process to exit; returns the exit code. */
    static long runToExit(GenericContainer<?> container, Duration timeout) {
        container
                .withStartupCheckStrategy(new UntilExited().withTimeout(timeout))
                .start();
        return exitCode(container);
    }

    /** The exit code of the container's process, once it has exited. */
    static long exitCode(GenericContainer<?> container) {
        return container.getCurrentContainerInfo().getState().getExitCodeLong();
    }

    /**
     * Starts the container, waits for its workload to write {@code ready} and keep running for one more second, and
     * returns while it still runs.
     */
    static void startUntilReady(GenericContainer<?> container, Path ready) {
        container.start();
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            assertThat(container.isRunning())
                    .as("the workload exited before it was ready:%n%s", container.getLogs())
                    .isTrue();
            return Files.isRegularFile(ready);
        });
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).until(container::isRunning);
    }

    /** Sends the container's process {@code signal} the way {@code docker stop} or {@code docker kill} would. */
    static long stop(GenericContainer<?> container, String signal) {
        DockerClient client = DockerClientFactory.instance().client();
        String id = container.getContainerId();
        if (signal.equals("TERM")) {
            client.stopContainerCmd(id).withTimeout(45).exec();
        } else {
            client.killContainerCmd(id).withSignal(signal).exec();
        }
        return client.waitContainerCmd(id).start().awaitStatusCode(60, TimeUnit.SECONDS);
    }

    /** Makes what the container wrote as root into {@code out} deletable by the test's own user. */
    void makeReadable(Path out) {
        try (GenericContainer<?> chmod = container(out).withCommand("chmod", "-R", "a+rwX", OUT)) {
            assertThat(runToExit(chmod, Duration.ofSeconds(30)))
                    .as("chmod of the output")
                    .isZero();
        }
    }

    @Override
    public String toString() {
        return libc;
    }

    /** Startup completes once the container's process has exited, whatever its exit code. */
    private static final class UntilExited extends StartupCheckStrategy {
        @Override
        public StartupStatus checkStartupState(DockerClient client, String containerId) {
            return Boolean.TRUE.equals(getCurrentState(client, containerId).getRunning())
                    ? StartupStatus.NOT_YET_KNOWN
                    : StartupStatus.SUCCESSFUL;
        }
    }
}
