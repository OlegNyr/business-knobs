package io.github.olegnyr.knobs.generator;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Цель {@code generate} плагина: файлы стендов на сборке сервиса и их место среди ресурсов.
 */
@DisplayName("Плагин Maven: цель generate")
class GenerateMojoTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("Пишет knobs/knobs-<стенд>.json на каждый стенд с версией сборки")
    void execute_shouldWriteStandFiles() throws Exception {
        GenerateMojo mojo = mojo(example());

        mojo.execute();

        assertThat(directory.resolve("out/knobs"))
                .isDirectoryContaining("glob:**/knobs-dev.json")
                .isDirectoryContaining("glob:**/knobs-test.json")
                .isDirectoryContaining("glob:**/knobs-prod.json");
        assertThat(Files.readString(directory.resolve("out/knobs/knobs-prod.json")))
                .contains("\"2.0.0\"");
    }

    @Test
    @DisplayName("Файл снятого стенда не остаётся в каталоге вывода и не уезжает в jar")
    void execute_shouldRemoveStaleStandFiles() throws Exception {
        Path stale = directory.resolve("out/knobs/knobs-qa.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "{}");
        GenerateMojo mojo = mojo(example());

        mojo.execute();

        assertThat(stale).doesNotExist();
        assertThat(directory.resolve("out/knobs/knobs-prod.json")).exists();
    }

    @Test
    @DisplayName("Каталог вывода становится ресурсом проекта: файлы попадают в jar без настройки <resources>")
    void execute_shouldRegisterOutputAsResource() throws Exception {
        GenerateMojo mojo = mojo(example());

        mojo.execute();

        assertThat(mojo.project().getResources()).extracting(Resource::getDirectory)
                .containsExactly(directory.resolve("out").toString());
    }

    @Test
    @DisplayName("Битый исходный файл валит сборку и называет ключ")
    void execute_shouldFail_whenSourceIsBroken() throws IOException {
        Path source = Files.writeString(directory.resolve("knobs.yaml"), """
                service: orders
                stands: [prod]
                dimensions:
                  dc: { kind: stand }
                parameters:
                  app:
                    k: { default: 1 }
                """);
        GenerateMojo mojo = mojo(source);

        Throwable thrown = catchThrowable(mojo::execute);

        assertThat(thrown).isInstanceOf(MojoFailureException.class).hasMessageContaining("app.k");
    }

    @Test
    @DisplayName("Нет исходного файла — отказ с путём, а не молча пустой слой")
    void execute_shouldFail_whenSourceIsMissing() {
        GenerateMojo mojo = mojo(directory.resolve("nowhere.yaml"));

        Throwable thrown = catchThrowable(mojo::execute);

        assertThat(thrown).isInstanceOf(MojoFailureException.class).hasMessageContaining("nowhere.yaml");
    }

    @Test
    @DisplayName("skip — ничего не пишет и ресурс не добавляет")
    void execute_shouldDoNothing_whenSkipped() throws Exception {
        GenerateMojo mojo = mojo(example());
        mojo.setSkip(true);

        mojo.execute();

        assertThat(directory.resolve("out")).doesNotExist();
        assertThat(mojo.project().getResources()).isEmpty();
    }

    @Test
    @DisplayName("version нельзя подменить свойством pom, остальные свойства — в knobs.generate.*")
    void descriptor_shouldKeepVersionOutOfUserProperties() throws IOException {
        String descriptor;
        try (InputStream in = GenerateMojoTest.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
            descriptor = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String generate = descriptor.substring(descriptor.indexOf("<goal>generate</goal>"));
        String configuration = generate.substring(generate.indexOf("<configuration>"),
                generate.indexOf("</configuration>"));

        assertThat(configuration).contains("<version implementation=\"java.lang.String\" "
                + "default-value=\"${project.version}\"/>");
        assertThat(configuration).doesNotContain("${knobs.version}", "${knobs.source}", "${knobs.outputDirectory}")
                .contains("${knobs.generate.source}", "${knobs.generate.outputDirectory}",
                        "${knobs.generate.skip}");
    }

    private GenerateMojo mojo(Path source) {
        GenerateMojo mojo = new GenerateMojo();
        mojo.setProject(new MavenProject());
        mojo.setSource(source.toFile());
        mojo.setOutputDirectory(directory.resolve("out").toFile());
        mojo.setVersion("2.0.0");
        return mojo;
    }

    private Path example() {
        Path source = directory.resolve("knobs.yaml");
        try (InputStream in = GenerateMojoTest.class.getResourceAsStream("/knobs.yaml")) {
            Files.copy(in, source);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return source;
    }
}
