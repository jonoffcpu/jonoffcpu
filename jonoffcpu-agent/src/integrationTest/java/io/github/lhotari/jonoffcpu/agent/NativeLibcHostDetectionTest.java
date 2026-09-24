// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** C-library detection in the running Linux JVM, which must agree with the loader mapped into it. */
@Tag("host-native")
class NativeLibcHostDetectionTest {
    @Test
    void hostDetectionAgreesWithMaps() throws Exception {
        String osArch = System.getProperty("os.arch");
        String architecture = osArch.equals("amd64") ? "x86_64" : osArch;
        NativeLibc host = NativeLibc.detect(architecture);
        assertThat(NativeLibc.fromMaps(Files.readString(Path.of("/proc/self/maps"))))
                .as("host detection agrees with /proc/self/maps")
                .contains(host);
    }
}
