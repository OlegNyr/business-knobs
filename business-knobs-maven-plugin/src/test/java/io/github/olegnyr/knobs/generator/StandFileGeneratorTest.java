package io.github.olegnyr.knobs.generator;

import io.github.olegnyr.knobs.impl.format.StandFileReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Генератор файлов стендов из исходного YAML разработчика. */
@DisplayName("Генератор файлов стендов")
class StandFileGeneratorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String HEAD = """
            service: orders
            stands: [dev, test, prod]
            dimensions:
              service: { kind: registry }
              dc: { kind: stand }
              segment: { kind: code, values: [MASS, VIP] }
            parameters:
            """;

    @Test
    @DisplayName("Пример исходного файла даёт ровно эталонные файлы dev, test, prod")
    void generate_shouldMatchReferenceFiles_whenSourceIsAnalysisExample() throws IOException {
        Map<String, JsonNode> files = generateExample();

        assertThat(files.keySet()).containsExactly("dev", "test", "prod");
        for (String stand : List.of("dev", "test", "prod")) {
            assertThat(files.get(stand)).as("файл %s", stand).isEqualTo(expected(stand));
        }
    }

    @Test
    @DisplayName("Тип выводится из значения")
    void generate_shouldInferTypes_whenTypeIsOmitted() {
        JsonNode prod = generate(HEAD + """
                  k:
                    b: { name: B, default: true }
                    i: { name: I, default: 2 }
                    d: { name: D, default: 2.5 }
                    t: { name: T, default: 10s }
                    a: { name: A, default: 2026-11-01 }
                    s: { name: S, default: текст }
                    l: { name: L, default: [jpg, png] }
                """).get("prod");

        assertThat(List.of("b", "i", "d", "t", "a", "s", "l")).map(name -> type(prod, "k." + name))
                .containsExactly("BOOLEAN", "INTEGER", "DOUBLE", "DURATION", "DATE", "STRING", "STRING[]");
    }

    static Stream<Arguments> brokenSources() {
        return Stream.of(
                Arguments.of("подстановка без типа", "  k: { name: K, default: \"${APP_CRM_URL}\" }\n", "k"),
                Arguments.of("пустой список без типа", "  k: { name: K, default: [] }\n", "k"),
                Arguments.of("значение правила не того типа",
                        "  k: { name: K, default: 2, rules: [ { when: [/dc2], value: 15s } ] }\n", "k"),
                Arguments.of("поле параметра рядом с сегментом ключа",
                        "  k:\n    name: K\n    child: { name: C, default: 1 }\n", "k"),
                Arguments.of("путь из одних звёздочек",
                        "  k: { name: K, default: 1, rules: [ { when: [\"/*\"], value: 2 } ] }\n", "k"),
                Arguments.of("последняя часть пути звёздочкой",
                        "  k: { name: K, default: 1, rules: [ { when: [\"/dc1/*\"], value: 2 } ] }\n", "k"),
                Arguments.of("путь длиннее измерений файла",
                        "  k: { name: K, default: 1, rules: [ { when: [/dc1/VIP/web], value: 2 } ] }\n", "k"),
                Arguments.of("пустое место в пути",
                        "  broken-key: { name: K, default: 1, rules: [ { when: [\"//VIP\"], value: 2 } ] }\n",
                        "broken-key"),
                Arguments.of("значение вне values измерения code в пути",
                        "  broken-key: { name: K, default: 1, rules: [ { when: [\"/*/VIPP\"], value: 2 } ] }\n",
                        "broken-key"),
                Arguments.of("именованная форма: незнакомое измерение",
                        "  broken-key: { name: K, default: 1, rules: [ { when: [{region: north}], value: 2 } ] }\n",
                        "broken-key"),
                Arguments.of("именованная форма: значение вне values",
                        "  broken-key: { name: K, default: 1, rules: [ { when: [{segment: GOLD}], value: 2 } ] }\n",
                        "broken-key"),
                Arguments.of("именованная форма: service",
                        "  broken-key: { name: K, default: 1, rules: [ { when: [{service: x}], value: 2 } ] }\n",
                        "broken-key"),
                Arguments.of("незнакомый тип", "  broken-key: { name: K, type: int, default: 1 }\n", "broken-key"),
                Arguments.of("целое вне 64 бит", "  broken-key: { name: K, default: 99999999999999999999 }\n",
                        "broken-key"),
                Arguments.of("подстановка в элементе списка",
                        "  broken-key: { name: K, type: integer, list: true, default: [\"${PORT}\", 8080] }\n",
                        "broken-key"),
                Arguments.of("allowed скаляром", "  broken-key: { name: K, default: VIP, allowed: VIP }\n",
                        "broken-key"),
                Arguments.of("min списком", "  broken-key: { name: K, default: 1, min: [1] }\n", "broken-key"),
                Arguments.of("пустое value правила",
                        "  broken-key: { name: K, default: 1, rules: [ { value: ~ } ] }\n", "broken-key"),
                Arguments.of("ключ не в kebab-case", "  readTimeout: { name: R, default: 1 }\n", "readTimeout"),
                Arguments.of("число с ведущим нулём без типа", "  broken-key: { name: K, default: 010 }\n",
                        "broken-key"),
                Arguments.of("on/off/yes/no без типа", "  broken-key: { name: K, default: no }\n", "broken-key"),
                Arguments.of("ключ повторяется",
                        "  broken-key: { name: K, default: 1 }\n  broken-key: { name: K, default: 2 }\n",
                        "broken-key"),
                Arguments.of("стенд, которого нет в stands",
                        "  k: { name: K, default: 1, rules: { qa: [ { value: 2 } ] } }\n", "k"),
                Arguments.of("нет name", "  k: { default: 1 }\n", "k"),
                Arguments.of("name не строкой", "  k: { name: [a], default: 1 }\n", "k"),
                Arguments.of("список у скалярного",
                        "  k: { name: K, default: 1, rules: [ { value: [1, 2] } ] }\n", "k"),
                Arguments.of("скаляр у списка", "  k: { name: K, default: [a], rules: [ { value: b } ] }\n", "k"),
                Arguments.of("min не того типа", "  k: { name: K, default: 10s, min: много }\n", "k"),
                Arguments.of("allowed не того типа", "  k: { name: K, default: 1, allowed: [a] }\n", "k"),
                Arguments.of("значение не задано", "  k: { name: K, default: ~ }\n", "k"),
                Arguments.of("длительность за пределами", "  k: { name: K, default: 99999999999999999999s }\n", "k"),
                Arguments.of("ключ библиотеки параметров",
                        "  knobs:\n    replica-enabled: { name: R, default: true }\n",
                        "knobs.replica-enabled"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenSources")
    @DisplayName("Генерация падает и называет ключ и причину")
    void generate_shouldFailWithKey_whenSourceIsBroken(String caseName, String parameters, String key) {
        Throwable thrown = catchThrowable(() -> generate(HEAD + parameters));

        assertThat(thrown).isInstanceOf(GeneratorException.class).hasMessageContaining(key);
    }

    @Test
    @DisplayName("Длительность, которую нельзя разобрать, — отказ с ключом и без цитаты значения")
    void generate_shouldNotQuoteValue_whenDurationIsBroken() {
        Throwable thrown = catchThrowable(() -> generate(HEAD + "  k: { name: K, type: duration, default: десять }\n"));

        assertThat(thrown).isInstanceOf(GeneratorException.class).hasMessageContaining("k")
                .hasMessageNotContaining("десять");
    }

    @Test
    @DisplayName("Параметр без default с правилами только для dev и test в файл prod не попадает")
    void generate_shouldOmitParameter_whenStandHasNoValue() throws IOException {
        Map<String, JsonNode> files = generateExample();

        assertThat(List.of(has(files.get("dev"), "app.crm.base-url"), has(files.get("test"), "app.crm.base-url"),
                has(files.get("prod"), "app.crm.base-url"))).containsExactly(true, true, false);
    }

    @Test
    @DisplayName("Правило стенда без пути перекрывает общие правила целиком")
    void generate_shouldOverrideAllRules_whenStandRuleHasNoPath() throws IOException {
        Map<String, JsonNode> files = generateExample();
        JsonNode dev = parameter(files.get("dev"), "app.support.wait-notice");
        JsonNode test = parameter(files.get("test"), "app.support.wait-notice");

        assertThat(List.of(dev.get("value").stringValue(), dev.get("rules").size(), test.get("rules").size()))
                .containsExactly("[dev] Оператор скоро подключится", 0, 2);
        assertThat(test.get("value").stringValue()).isEqualTo("Оператор скоро подключится");
        assertThat(test.get("rules")).extracting(rule -> rule.get("when").toString())
                .containsExactly("{\"segment\":\"VIP\"}", "{\"segment\":\"PRIVATE\"}");
    }

    @Test
    @DisplayName("«*/dc2» — без условия по сервису, /dc1 общего параметра — с условием своего сервиса")
    void generate_shouldScopeRulesByService_whenParameterIsShared() {
        JsonNode prod = generate(HEAD + """
                  k:
                    name: K
                    default: false
                    rules:
                      - when: ["*/dc2"]
                        value: true
                      - when: [/dc1]
                        value: true
                """).get("prod");

        assertThat(parameter(prod, "k").get("rules")).isEqualTo(MAPPER.readTree("""
                [{"when": {"dc": "dc2"}, "value": true},
                 {"when": {"service": "orders", "dc": "dc1"}, "value": true}]
                """));
    }

    @Test
    @DisplayName("Места в пути правила — измерения файла в объявленном порядке, service не в счёт")
    void generate_shouldMapPathToDeclaredDimensions() {
        JsonNode prod = generate("""
                service: orders
                stands: [prod]
                dimensions:
                  service: { kind: registry }
                  dc: { kind: stand }
                  tenant: { kind: stand }
                  channel: { kind: code, values: [web, mobile] }
                parameters:
                  k:
                    name: K
                    default: 1
                    rules:
                      - when: [/*/t2]
                        value: 2
                      - when: [/dc1/*/web]
                        value: 3
                """).get("prod");

        assertThat(parameter(prod, "k").get("rules")).isEqualTo(MAPPER.readTree("""
                [{"when": {"dc": "dc1", "channel": "web"}, "value": 3},
                 {"when": {"tenant": "t2"}, "value": 2}]
                """));
    }

    private static Map<String, JsonNode> generateExample() throws IOException {
        try (InputStream in = StandFileGeneratorTest.class.getResourceAsStream("/knobs.yaml")) {
            return StandFileGenerator.generate(in, "1.8.0");
        }
    }

    static Stream<Arguments> brokenHeads() {
        return Stream.of(
                Arguments.of("пустой файл", "", "пуст"),
                Arguments.of("нет service", "stands: [prod]\ndimensions: {dc: {kind: stand}}\nparameters: {}\n",
                        "service"),
                Arguments.of("измерение без kind",
                        "service: s\nstands: [prod]\ndimensions: {dc: {}}\nparameters: {}\n", "kind"),
                Arguments.of("незнакомый kind",
                        "service: s\nstands: [prod]\ndimensions: {dc: {kind: table}}\nparameters: {}\n", "kind"),
                Arguments.of("code без values",
                        "service: s\nstands: [prod]\ndimensions: {segment: {kind: code}}\nparameters: {}\n",
                        "values"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenHeads")
    @DisplayName("Негодная шапка исходного файла — отказ генерации с причиной")
    void generate_shouldFail_whenHeadIsBroken(String caseName, String yaml, String reason) {
        Throwable thrown = catchThrowable(() -> generate(yaml));

        assertThat(thrown).isInstanceOf(GeneratorException.class).hasMessageContaining(reason);
    }

    @Test
    @DisplayName("Именованная форма when — измерения по именам, вместе с коротким путём")
    void generate_shouldAcceptNamedWhen() {
        JsonNode prod = generate(HEAD + """
                  k:
                    name: K
                    default: 1
                    rules:
                      - when: [{segment: VIP}]
                        value: 2
                      - when: [{dc: dc2, segment: MASS}, /dc1]
                        value: 3
                """).get("prod");

        assertThat(parameter(prod, "k").get("rules")).isEqualTo(MAPPER.readTree("""
                [{"when": {"dc": "dc2", "segment": "MASS"}, "value": 3},
                 {"when": {"segment": "VIP"}, "value": 2},
                 {"when": {"dc": "dc1"}, "value": 3}]
                """));
    }

    @Test
    @DisplayName("YAML 1.2: NO и 1:30 — строки, а не признак и не число")
    void generate_shouldReadYaml12CoreSchema() {
        JsonNode prod = generate("""
                service: orders
                stands: [prod]
                dimensions:
                  country: { kind: code, values: [RU, NO, SE] }
                parameters:
                  k:
                    name: K
                    type: string
                    default: 1:30
                """).get("prod");

        assertThat(prod.get("dimensions").get(0).get("values")).isEqualTo(MAPPER.readTree("[\"RU\", \"NO\", \"SE\"]"));
        assertThat(parameter(prod, "k").get("value").stringValue()).isEqualTo("1:30");
    }

    @Test
    @DisplayName("Каждый файл, который выдал генератор, читается библиотекой")
    void generate_shouldProduceFilesReadableByLibrary() throws IOException {
        Map<String, JsonNode> files = new LinkedHashMap<>(generateExample());
        files.put("inferred", generate(HEAD + """
                  k:
                    b: { name: B, default: true }
                    i: { name: I, default: 2, min: 1, max: 10, allowed: [1, 2, 3] }
                    d: { name: D, default: 2.5 }
                    t: { name: T, default: 10s, min: 1s }
                    a: { name: A, default: 2026-11-01 }
                    s: { name: S, default: текст, pattern: "^т" }
                    l: { name: L, default: [jpg, png] }
                    p: { name: P, type: integer, default: "${PORT}" }
                    r: { name: R, default: 1, rules: [ { when: [/dc2, {segment: VIP}], value: 2 } ] }
                """).get("prod"));

        for (Map.Entry<String, JsonNode> file : files.entrySet()) {
            byte[] json = MAPPER.writeValueAsBytes(file.getValue());
            assertThat(catchThrowable(() -> StandFileReader.read(new ByteArrayInputStream(json))))
                    .as("файл %s", file.getKey()).isNull();
        }
    }

    private static Map<String, JsonNode> generate(String yaml) {
        return StandFileGenerator.generate(new ByteArrayInputStream(
                yaml.getBytes(StandardCharsets.UTF_8)), "1.0.0");
    }

    private static JsonNode expected(String stand) throws IOException {
        try (InputStream in = StandFileGeneratorTest.class.getResourceAsStream("/expected-" + stand + ".json")) {
            return MAPPER.readTree(in);
        }
    }

    private static String type(JsonNode file, String key) {
        JsonNode parameter = parameter(file, key);
        return parameter.get("type").stringValue() + (parameter.path("list").asBoolean(false) ? "[]" : "");
    }

    private static boolean has(JsonNode file, String key) {
        for (JsonNode parameter : file.get("parameters")) {
            if (parameter.get("key").stringValue().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode parameter(JsonNode file, String key) {
        for (JsonNode parameter : file.get("parameters")) {
            if (parameter.get("key").stringValue().equals(key)) {
                return parameter;
            }
        }
        throw new AssertionError("нет параметра " + key);
    }
}
