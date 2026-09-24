// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.jonoffcpu.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Member;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that relocated implementation types do not leak through the public Java API. It runs against the shaded
 * JAR alone, so it names the classes it inspects instead of linking against them.
 */
@Tag("packaged-jar")
class CorrelatorPublicApiTest {
    private static final String SHADED = "io.github.jonoffcpu.jonoffcpu.correlator.internal.shaded";

    @ParameterizedTest
    @ValueSource(
            strings = {
                "io.github.jonoffcpu.jonoffcpu.offline.OffCpuCorrelator",
                "io.github.jonoffcpu.jonoffcpu.offline.SignalJfrExporter"
            })
    void publicApiHidesRelocatedTypes(String name) throws Exception {
        List<String> leaked = new ArrayList<>();
        collectLeaks(Class.forName(name), leaked);
        assertThat(leaked)
                .as("Relocated implementation types leaked through the public API")
                .isEmpty();
    }

    @Test
    void implementationClassesStayPackagePrivate() throws Exception {
        for (String name : List.of(
                "io.github.jonoffcpu.jonoffcpu.offline.OfflineCorrelator",
                "io.github.jonoffcpu.jonoffcpu.offline.CompatibilityJfrWriter")) {
            assertThat(Modifier.isPublic(Class.forName(name).getModifiers()))
                    .as("%s must remain an implementation detail", name)
                    .isFalse();
        }
    }

    private static void collectLeaks(Class<?> type, List<String> leaked) {
        Stream.concat(Arrays.stream(type.getDeclaredMethods()), Arrays.stream(type.getDeclaredConstructors()))
                .filter(member -> Modifier.isPublic(member.getModifiers()))
                .map(Member::toString)
                .filter(signature -> signature.contains(SHADED))
                .forEach(leaked::add);
        for (Class<?> nested : type.getDeclaredClasses()) {
            if (Modifier.isPublic(nested.getModifiers())) {
                collectLeaks(nested, leaked);
            }
        }
    }
}
