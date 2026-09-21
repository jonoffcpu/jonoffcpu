// SPDX-License-Identifier: MIT
package io.github.lhotari.jonoffcpu.packaging;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.stream.Stream;

/** Verifies that relocated implementation types do not leak through the public Java API. */
public final class CorrelatorPublicApiTest {
    private static final String SHADED = "io.github.lhotari.jonoffcpu.correlator.internal.shaded";

    private CorrelatorPublicApiTest() {}

    public static void main(String[] args) throws Exception {
        Class<?> correlator = Class.forName("io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator");
        Class<?> exporter = Class.forName("io.github.lhotari.jonoffcpu.jfr.SignalJfrExporter");
        assertPublicApi(correlator);
        assertPublicApi(exporter);
        assertPackagePrivate("io.github.lhotari.jonoffcpu.offline.OfflineCorrelator");
        assertPackagePrivate("io.github.lhotari.jonoffcpu.offline.CompatibilityJfrWriter");
        System.out.println("Correlator public API fixture passed");
    }

    private static void assertPublicApi(Class<?> type) {
        Stream.concat(Arrays.stream(type.getDeclaredMethods()), Arrays.stream(type.getDeclaredConstructors()))
                .filter(member -> Modifier.isPublic(member.getModifiers()))
                .map(Member::toString)
                .filter(signature -> signature.contains(SHADED))
                .findFirst()
                .ifPresent(signature -> {
                    throw new AssertionError("Relocated implementation type leaked through public API: " + signature);
                });
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (Modifier.isPublic(nested.getModifiers())) {
                assertPublicApi(nested);
            }
        }
    }

    private static void assertPackagePrivate(String name) throws ClassNotFoundException {
        if (Modifier.isPublic(Class.forName(name).getModifiers())) {
            throw new AssertionError(name + " must remain an implementation detail");
        }
    }
}
