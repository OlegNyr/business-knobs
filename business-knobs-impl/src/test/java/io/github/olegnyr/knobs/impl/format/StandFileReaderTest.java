package io.github.olegnyr.knobs.impl.format;

import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.impl.model.DimensionDeclaration;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import io.github.olegnyr.knobs.impl.model.StandFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Чтение сгенерированного файла стенда по контракту.
 *
 * <p>Образец — {@code stand/knobs-prod.json}: файл, который генератор делает из примера
 * исходного {@code knobs.yaml}.
 */
@DisplayName("Файл стенда читается по контракту")
class StandFileReaderTest {

    @Test
    @DisplayName("Прочитаны стенд, параметры и измерения в порядке файла")
    void read_shouldParseStructure_whenFileFollowsContract() throws IOException {
        StandFile file = readFixture();

        assertThat(file.getStand()).isEqualTo("prod");
        assertThat(file.getParameters()).hasSize(9);
        assertThat(file.getDimensions()).extracting(DimensionDeclaration::getKey)
                .containsExactly("service", "dc", "tenant", "segment", "channel");
    }

    @Test
    @DisplayName("Длительность — Duration, дата — LocalDate, список — список")
    void read_shouldConvertValues_whenTypeIsDurationDateOrList() throws IOException {
        StandFile file = readFixture();
        ParameterDefinition timeout = parameter(file, "app.crm.read-timeout");
        ParameterDefinition startDate = parameter(file, "app.survey.start-date");
        ParameterDefinition extensions = parameter(file, "app.media.allowed-extensions");

        assertThat(List.of(
                timeout.getType().read(timeout.getValue()),
                timeout.getType().read(timeout.getRules().getFirst().getValue()),
                startDate.getType().read(startDate.getValue()),
                extensions.getType().read(extensions.getValue())))
                .containsExactly(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(15),
                        LocalDate.of(2026, 11, 1),
                        List.of("jpg", "png", "pdf"));
    }

    @Test
    @DisplayName("Удалённый параметр читается с пустыми коллекциями, а не null")
    void read_shouldGiveEmptyCollections_whenParameterIsDeleted() throws IOException {
        ParameterDefinition deleted = parameter(readFixture(), "app.routing.project-enabled");

        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getRules()).isEmpty();
        assertThat(deleted.getAllowed()).isEmpty();
    }

    @Test
    @DisplayName("Незнакомое поле пропускается, null у признака читается как его отсутствие")
    void read_shouldSkipUnknownField_whenContractGrew() {
        StandFile file = read("""
                {"service": "orders", "version": "1.9.0", "stand": "dev", "owner": "team-x",
                 "parameters": [{"key": "app.x", "name": "X", "type": "INTEGER", "value": 1,
                                 "list": null, "deleted": null, "hint": "новое поле"}]}
                """);

        assertThat(file.getParameters()).singleElement()
                .satisfies(parameter -> assertThat(parameter.isList() || parameter.isDeleted()).isFalse());
    }

    @Test
    @DisplayName("Поля админки pattern и format в файле есть, под их не читает — параметр читается")
    void read_shouldIgnoreAdminOnlyConstraints_whenFileCarriesPatternAndFormat() {
        StandFile file = read("""
                {"service": "orders", "version": "1.9.0", "stand": "dev",
                 "parameters": [{"key": "app.crm.base-url", "name": "Адрес", "type": "STRING",
                                 "pattern": "^https://", "format": "url", "value": "https://crm"}]}
                """);
        ParameterDefinition url = file.getParameters().getFirst();

        assertThat(url.getType().read(url.getValue())).isEqualTo("https://crm");
    }

    @Test
    @DisplayName("Коллекции прочитанного файла неизменяемы")
    void read_shouldGiveImmutableCollections_whenCallerTriesToModify() throws IOException {
        StandFile file = readFixture();
        List<ParameterRule> rules = file.getParameters().getFirst().getRules();

        Throwable thrown = catchThrowable(() -> rules.add(ParameterRule.builder().build()));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Целое читается как long: значение больше int не теряется")
    void read_shouldReadLong_whenIntegerExceedsInt() {
        StandFile file = read("""
                {"service": "orders", "version": "1.9.0", "stand": "dev",
                 "parameters": [{"key": "app.media.max-size", "name": "Размер", "type": "INTEGER",
                                 "value": 5368709120}]}
                """);
        ParameterDefinition size = file.getParameters().getFirst();

        assertThat(size.getType().read(size.getValue())).isEqualTo(5_368_709_120L);
    }

    private static StandFile readFixture() throws IOException {
        try (InputStream in = StandFileReaderTest.class.getResourceAsStream("/stand/knobs-prod.json")) {
            return StandFileReader.read(in);
        }
    }

    private static StandFile read(String json) {
        return StandFileReader.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static ParameterDefinition parameter(StandFile file, String key) {
        return file.getParameters().stream()
                .filter(parameter -> parameter.getKey().equals(key))
                .findFirst()
                .orElseThrow();
    }
}
