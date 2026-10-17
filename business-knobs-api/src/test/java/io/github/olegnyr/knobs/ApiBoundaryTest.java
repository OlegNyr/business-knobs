package io.github.olegnyr.knobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Граница API библиотеки параметров: код сервиса, видящий только
 * API, не получает ни исполнения, ни Spring. Разрешено — стандартная библиотека, Jackson (значения
 * записи — JSON), Lombok, OpenFeature ({@code Parameters#client()}) и context-propagation
 * (необязательный accessor пути обращения).
 */
@DisplayName("API библиотеки параметров не тянет исполнение и Spring")
class ApiBoundaryTest {

    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?([\\w.]+);", Pattern.MULTILINE);

    private static final List<String> ALLOWED = List.of("java.", "tools.jackson.", "lombok.", "dev.openfeature.sdk.",
            "io.micrometer.context.", "io.github.olegnyr.knobs.");

    private static final List<String> FORBIDDEN = List.of("io.github.olegnyr.knobs.impl.",
            "io.github.olegnyr.knobs.boot.");

    @Test
    @DisplayName("Импорты API — только стандартная библиотека, Jackson, Lombok, OpenFeature и context-propagation")
    void sources_shouldImportOnlyAllowedPackages() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher imports = IMPORT.matcher(Files.readString(file));
                while (imports.find()) {
                    String imported = imports.group(1);
                    if (ALLOWED.stream().noneMatch(imported::startsWith)
                            || FORBIDDEN.stream().anyMatch(imported::startsWith)) {
                        offenders.add(root.relativize(file) + ": " + imported);
                    }
                }
            }
        }

        assertThat(offenders).isEmpty();
    }
}
