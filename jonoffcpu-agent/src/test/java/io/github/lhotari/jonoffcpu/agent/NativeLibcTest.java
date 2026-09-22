// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Fixture for C-library detection that selects the glibc or musl native bundle. */
public final class NativeLibcTest {
    private NativeLibcTest() {}

    public static void main(String[] args) throws Exception {
        String glibcMaps = """
                7f0a00000000-7f0a00001000 r--p 00000000 fd:01 1 /usr/lib/jvm/lib/server/libjvm.so
                7f0a10000000-7f0a10001000 r--p 00000000 fd:01 2 /usr/lib/x86_64-linux-gnu/libc.so.6
                7f0a20000000-7f0a20001000 r--p 00000000 fd:01 3 /usr/lib64/ld-linux-x86-64.so.2
                7ffd00000000-7ffd00001000 rw-p 00000000 00:00 0 [stack]
                """;
        String muslMaps = """
                7f0a00000000-7f0a00001000 r--p 00000000 fd:01 1 /usr/lib/jvm/lib/server/libjvm.so
                7f0a10000000-7f0a10001000 r--p 00000000 fd:01 2 /lib/ld-musl-x86_64.so.1
                7ffd00000000-7ffd00001000 rw-p 00000000 00:00 0 [stack]
                """;
        String muslAliasMaps = "7f0a10000000-7f0a10001000 r--p 00000000 fd:01 2 /usr/lib/libc.musl-aarch64.so.1\n";
        String mixedMaps =
                glibcMaps + "7f0a30000000-7f0a30001000 r--p 00000000 fd:01 4 /opt/musl/lib/ld-musl-x86_64.so.1\n";
        String noLibcMaps = "7ffd00000000-7ffd00001000 rw-p 00000000 00:00 0 [stack]\n";

        check(NativeLibc.fromMaps(glibcMaps).equals(Optional.of(NativeLibc.GLIBC)), "glibc maps");
        check(NativeLibc.fromMaps(muslMaps).equals(Optional.of(NativeLibc.MUSL)), "musl maps");
        check(NativeLibc.fromMaps(muslAliasMaps).equals(Optional.of(NativeLibc.MUSL)), "musl alias maps");
        check(NativeLibc.fromMaps(noLibcMaps).isEmpty(), "maps without a libc are inconclusive");
        check(NativeLibc.fromMaps("").isEmpty(), "empty maps are inconclusive");
        // A path that merely mentions musl inside a directory name is not a musl loader.
        check(
                NativeLibc.fromMaps("7f0a10000000-7f0a10001000 r--p 00000000 fd:01 2 /opt/ld-musl-tools/libc.so.6\n")
                        .equals(Optional.of(NativeLibc.GLIBC)),
                "directory names do not count");
        boolean rejectedMixed;
        try {
            NativeLibc.fromMaps(mixedMaps);
            rejectedMixed = false;
        } catch (IllegalStateException expected) {
            rejectedMixed = true;
        }
        check(rejectedMixed, "both loaders mapped must be rejected");

        Path root = Files.createTempDirectory("jonoffcpu-libc-");
        try {
            check(NativeLibc.fromLoaderFiles(root, "x86_64").isEmpty(), "no loader file is inconclusive");
            Files.createDirectories(root.resolve("lib"));
            Files.writeString(root.resolve("lib/ld-musl-x86_64.so.1"), "");
            check(NativeLibc.fromLoaderFiles(root, "x86_64").equals(Optional.of(NativeLibc.MUSL)), "musl loader file");
            check(NativeLibc.fromLoaderFiles(root, "aarch64").isEmpty(), "loader file is architecture specific");
        } finally {
            try (var walk = Files.walk(root)) {
                for (Path path :
                        walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }

        check(NativeLibc.fromOverride("musl").equals(Optional.of(NativeLibc.MUSL)), "override musl");
        check(NativeLibc.fromOverride("GLIBC").equals(Optional.of(NativeLibc.GLIBC)), "override glibc");
        check(NativeLibc.fromOverride(null).isEmpty(), "missing override");
        check(NativeLibc.fromOverride(" ").isEmpty(), "blank override");
        boolean rejectedOverride;
        try {
            NativeLibc.fromOverride("bionic");
            rejectedOverride = false;
        } catch (IllegalArgumentException expected) {
            rejectedOverride = true;
        }
        check(rejectedOverride, "unknown override must be rejected");

        check(NativeLibc.GLIBC.platform("x86_64").equals("linux-x86_64"), "glibc platform name");
        check(NativeLibc.MUSL.platform("aarch64").equals("linux-musl-aarch64"), "musl platform name");

        // The build host is a real Linux JVM, so detection must succeed and agree with its loader.
        String architecture = System.getProperty("os.arch").equals("amd64") ? "x86_64" : System.getProperty("os.arch");
        NativeLibc host = NativeLibc.detect(architecture);
        String hostMaps = Files.readString(Path.of("/proc/self/maps"));
        check(NativeLibc.fromMaps(hostMaps).equals(Optional.of(host)), "host detection disagrees with maps");
        System.out.println("Native libc detection fixture passed: host is " + host);
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
