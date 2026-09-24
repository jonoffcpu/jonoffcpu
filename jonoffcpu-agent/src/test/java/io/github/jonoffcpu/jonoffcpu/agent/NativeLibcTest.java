// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** C-library detection that selects the glibc or musl native bundle. */
class NativeLibcTest {
    private static final String GLIBC_MAPS = """
            7f0a00000000-7f0a00001000 r--p 00000000 fd:01 1 /usr/lib/jvm/lib/server/libjvm.so
            7f0a10000000-7f0a10001000 r--p 00000000 fd:01 2 /usr/lib/x86_64-linux-gnu/libc.so.6
            7f0a20000000-7f0a20001000 r--p 00000000 fd:01 3 /usr/lib64/ld-linux-x86-64.so.2
            7ffd00000000-7ffd00001000 rw-p 00000000 00:00 0 [stack]
            """;
    private static final String MUSL_MAPS = """
            7f0a00000000-7f0a00001000 r--p 00000000 fd:01 1 /usr/lib/jvm/lib/server/libjvm.so
            7f0a10000000-7f0a10001000 r--p 00000000 fd:01 2 /lib/ld-musl-x86_64.so.1
            7ffd00000000-7ffd00001000 rw-p 00000000 00:00 0 [stack]
            """;

    @Test
    void mapsIdentifyTheLoadedLibc() {
        assertThat(NativeLibc.fromMaps(GLIBC_MAPS)).contains(NativeLibc.GLIBC);
        assertThat(NativeLibc.fromMaps(MUSL_MAPS)).contains(NativeLibc.MUSL);
        assertThat(NativeLibc.fromMaps(
                        "7f0a10000000-7f0a10001000 r--p 00000000 fd:01 2 /usr/lib/libc.musl-aarch64.so.1\n"))
                .as("musl alias maps")
                .contains(NativeLibc.MUSL);
        assertThat(NativeLibc.fromMaps("7ffd00000000-7ffd00001000 rw-p 00000000 00:00 0 [stack]\n"))
                .as("maps without a libc are inconclusive")
                .isEmpty();
        assertThat(NativeLibc.fromMaps("")).as("empty maps are inconclusive").isEmpty();
        // A path that merely mentions musl inside a directory name is not a musl loader.
        assertThat(NativeLibc.fromMaps(
                        "7f0a10000000-7f0a10001000 r--p 00000000 fd:01 2 /opt/ld-musl-tools/libc.so.6\n"))
                .as("directory names do not count")
                .contains(NativeLibc.GLIBC);
    }

    @Test
    void bothLoadersMappedAreRejected() {
        String mixedMaps =
                GLIBC_MAPS + "7f0a30000000-7f0a30001000 r--p 00000000 fd:01 4 /opt/musl/lib/ld-musl-x86_64.so.1\n";
        assertThatIllegalStateException().isThrownBy(() -> NativeLibc.fromMaps(mixedMaps));
    }

    @Test
    void loaderFilesIdentifyMusl(@TempDir Path root) throws Exception {
        assertThat(NativeLibc.fromLoaderFiles(root, "x86_64"))
                .as("no loader file is inconclusive")
                .isEmpty();
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/ld-musl-x86_64.so.1"), "");
        assertThat(NativeLibc.fromLoaderFiles(root, "x86_64")).contains(NativeLibc.MUSL);
        assertThat(NativeLibc.fromLoaderFiles(root, "aarch64"))
                .as("the loader file is architecture specific")
                .isEmpty();
    }

    @Test
    void overrideNamesALibc() {
        assertThat(NativeLibc.fromOverride("musl")).contains(NativeLibc.MUSL);
        assertThat(NativeLibc.fromOverride("GLIBC")).contains(NativeLibc.GLIBC);
        assertThat(NativeLibc.fromOverride(null)).isEmpty();
        assertThat(NativeLibc.fromOverride(" ")).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> NativeLibc.fromOverride("bionic"));
    }

    @Test
    void platformNames() {
        assertThat(NativeLibc.GLIBC.platform("x86_64")).isEqualTo("linux-x86_64");
        assertThat(NativeLibc.MUSL.platform("aarch64")).isEqualTo("linux-musl-aarch64");
    }
}
