package io.github.olegnyr.knobs.impl.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Битый файл стенда отбраковывается при чтении: сообщение называет ключ и причину, значения
 * не цитирует.
 */
@DisplayName("Битый файл стенда отбраковывается с ключом и причиной")
class StandFileValidationTest {

    private static final String HEAD = "\"service\": \"orders\", \"version\": \"1.0.0\", \"stand\": \"dev\"";

    static Stream<Arguments> brokenFiles() {
        return Stream.of(
                Arguments.of("нет стенда",
                        "{\"service\": \"orders\", \"version\": \"1.0.0\", \"parameters\": []}", "stand"),
                Arguments.of("нет ключа",
                        parameters("{\"name\": \"X\", \"type\": \"STRING\", \"value\": \"секрет-1\"}"), "key"),
                Arguments.of("повтор ключа",
                        parameters(x("\"type\": \"STRING\", \"value\": \"секрет-1\"") + ","
                                + x("\"type\": \"STRING\", \"value\": \"секрет-1\"")), "app.x"),
                Arguments.of("нет типа",
                        parameters("{\"key\": \"app.x\", \"name\": \"X\", \"value\": \"секрет-1\"}"), "app.x"),
                Arguments.of("нет названия",
                        parameters("{\"key\": \"app.x\", \"type\": \"STRING\", \"value\": \"секрет-1\"}"), "app.x"),
                Arguments.of("значение-объект",
                        parameters(x("\"type\": \"STRING\", \"value\": {\"a\": \"секрет-1\"}")), "app.x"),
                Arguments.of("список у скалярного",
                        parameters(x("\"type\": \"STRING\", \"value\": [\"секрет-1\"]")), "app.x"),
                Arguments.of("null в списке",
                        parameters(x("\"type\": \"STRING\", \"list\": true, \"value\": [\"секрет-1\", null]")),
                        "app.x"),
                Arguments.of("не тот тип",
                        parameters(x("\"type\": \"DURATION\", \"value\": \"секрет-1\"")), "app.x"),
                Arguments.of("null-правило",
                        parameters(x("\"type\": \"STRING\", \"value\": \"a\", \"rules\": [null]")), "app.x"),
                Arguments.of("null в пути правила",
                        parameters(x("\"type\": \"STRING\", \"value\": \"a\","
                                + " \"rules\": [{\"when\": {\"dc\": null}, \"value\": \"b\"}]")), "app.x"),
                Arguments.of("значение правила не того типа",
                        parameters(x("\"type\": \"INTEGER\", \"value\": 1,"
                                + " \"rules\": [{\"when\": {\"dc\": \"dc2\"}, \"value\": \"секрет-1\"}]")),
                        "app.x"),
                Arguments.of("дробное у целого",
                        parameters(x("\"type\": \"INTEGER\", \"value\": 5.0")), "app.x"),
                Arguments.of("путь правила повторяется",
                        parameters(x("\"type\": \"STRING\", \"value\": \"a\", \"rules\": ["
                                + "{\"when\": {\"dc\": \"dc2\"}, \"value\": \"b\"},"
                                + "{\"when\": {\"dc\": \"dc2\"}, \"value\": \"c\"}]")), "app.x"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenFiles")
    @DisplayName("битый файл — ParameterFormatException с ключом, без значения")
    void read_shouldRejectWithKeyAndWithoutValue_whenFileIsBroken(String caseName, String json,
            String key) {
        Throwable thrown = catchThrowable(() -> StandFileReader.read(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));

        assertThat(thrown).isInstanceOf(ParameterFormatException.class)
                .hasMessageContaining(key)
                .hasMessageNotContaining("секрет-1");
    }

    private static String x(String fields) {
        return "{\"key\": \"app.x\", \"name\": \"X\", " + fields + "}";
    }

    private static String parameters(String items) {
        return "{" + HEAD + ", \"parameters\": [" + items + "]}";
    }
}
