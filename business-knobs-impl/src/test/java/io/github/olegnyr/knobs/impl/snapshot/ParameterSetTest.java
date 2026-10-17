package io.github.olegnyr.knobs.impl.snapshot;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.impl.TestDimensions;
import io.github.olegnyr.knobs.impl.model.ParameterDefinition;
import io.github.olegnyr.knobs.impl.model.StandFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Слои снимка параметров: реплика поверх файла по ключу целиком. */
@DisplayName("Реплика перекрывает файл по ключу целиком")
class ParameterSetTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final StandFile FILE = StandFile.builder()
            .service("orders")
            .parameter(definition("app.sample.timeout", ParameterType.DURATION, "\"PT10S\"")
                    .rule(ParameterRule.builder().whenPart("dc", "dc2").value(json("\"PT15S\"")).build())
                    .build())
            .parameter(definition("app.sample.token", ParameterType.STRING, "\"${MISSING}\"").build())
            .build();

    private static final ParameterSet FROM_FILE = ParameterSet.fromStandFile(FILE, TestDimensions.ALL, name -> null,
            (key, names) -> {
            });

    @Test
    @DisplayName("Строка реплики заменяет значение и правила файла целиком")
    void withReplica_shouldReplaceValueAndRules_whenRecordIsPresent() {
        ParameterSet set = FROM_FILE.withReplica(List.of(record("app.sample.timeout", ParameterType.DURATION,
                "\"PT20S\"").build()));

        assertThat(set.find("app.sample.timeout")).get()
                .satisfies(parameter -> assertThat(parameter.getRules()).isEmpty())
                .extracting(ParameterDefinition::getValue)
                .isEqualTo(json("\"PT20S\""));
    }

    @Test
    @DisplayName("Удалённая в админке строка возвращает параметр к значению файла")
    void withReplica_shouldFallBackToFile_whenRecordIsDeleted() {
        ParameterSet set = FROM_FILE.withReplica(List.of(record("app.sample.timeout", ParameterType.DURATION,
                "\"PT20S\"").deleted(true).build()));

        assertThat(set.find("app.sample.timeout")).get().extracting(ParameterDefinition::getValue)
                .isEqualTo(json("\"PT10S\""));
    }

    @Test
    @DisplayName("Тип строки реплики расходится с каталогом — действует файл")
    void withReplica_shouldFallBackToFile_whenRecordTypeDiffers() {
        ParameterSet set = FROM_FILE.withReplica(List.of(record("app.sample.timeout", ParameterType.STRING,
                "\"долго\"").build()));

        assertThat(set.find("app.sample.timeout")).get().extracting(ParameterDefinition::getValue)
                .isEqualTo(json("\"PT10S\""));
    }

    @Test
    @DisplayName("Ключ вне каталога сервиса в снимок не попадает")
    void withReplica_shouldIgnoreRecord_whenKeyIsNotInCatalog() {
        ParameterSet set = FROM_FILE.withReplica(List.of(record("app.other.key", ParameterType.STRING,
                "\"x\"").build()));

        assertThat(set.find("app.other.key")).isEmpty();
    }

    @Test
    @DisplayName("Подстановка в файле не разрешилась — значение из реплики всё равно действует")
    void withReplica_shouldUseRecord_whenFileValueIsUnresolved() {
        ParameterSet set = FROM_FILE.withReplica(List.of(record("app.sample.token", ParameterType.STRING,
                "\"TOKEN\"").build()));

        assertThat(set.find("app.sample.token")).get().extracting(ParameterDefinition::getValue)
                .isEqualTo(json("\"TOKEN\""));
    }

    @Test
    @DisplayName("Версия ключа — версия строки реплики; без строки версии нет")
    void version_shouldReturnRecordVersion_whenRecordIsPresent() {
        ParameterSet set = FROM_FILE.withReplica(List.of(record("app.sample.timeout", ParameterType.DURATION,
                "\"PT20S\"").version(7L).build()));

        assertThat(List.of(set.version("app.sample.timeout"), set.version("app.sample.token")))
                .containsExactly(7L, 0L);
    }

    @Test
    @DisplayName("Подстановка у целого приводится к числу из окружения")
    void fromStandFile_shouldCoercePlaceholder_whenTypeIsInteger() {
        ParameterSet set = ParameterSet.fromStandFile(numeric("${RETRIES}"), TestDimensions.ALL,
                Map.of("RETRIES", "5")::get,
                (key, names) -> {
                });

        assertThat(set.find("app.sample.retries")).get()
                .extracting(parameter -> parameter.getType().read(parameter.getValue())).isEqualTo(5L);
    }

    @Test
    @DisplayName("Подстановка у целого не читается числом — значения из файла нет")
    void fromStandFile_shouldSkipParameter_whenPlaceholderIsNotNumber() {
        ParameterSet set = ParameterSet.fromStandFile(numeric("${RETRIES}"), TestDimensions.ALL,
                Map.of("RETRIES", "много")::get,
                (key, names) -> {
                });

        assertThat(set.find("app.sample.retries")).isEmpty();
    }

    @Test
    @DisplayName("Снимок кладёт правила по точности — из файла и из реплики: /dc2/VIP, /*/VIP, /dc2")
    void snapshot_shouldRankRules_whenSourceListsThemInAnyOrder() {
        ParameterRule dc = ParameterRule.builder().whenPart("dc", "dc2").value(json("\"dc2\"")).build();
        ParameterRule segment = ParameterRule.builder().whenPart("segment", "VIP").value(json("\"vip\"")).build();
        ParameterRule both = ParameterRule.builder().whenPart("dc", "dc2").whenPart("segment", "VIP")
                .value(json("\"dc2-vip\"")).build();
        StandFile file = StandFile.builder().service("orders")
                .parameter(definition("app.sample.notice", ParameterType.STRING, "\"обычный\"")
                        .rule(dc).rule(segment).rule(both).build())
                .build();
        ParameterSet fromFile = ParameterSet.fromStandFile(file, TestDimensions.ALL, name -> null,
                (key, names) -> {
                });
        ParameterSet fromReplica = fromFile.withReplica(List.of(record("app.sample.notice", ParameterType.STRING,
                "\"обычный\"").rule(segment).rule(dc).rule(both).build()));

        assertThat(List.of(whens(fromFile), whens(fromReplica))).containsOnly(List.of(
                Map.of("dc", "dc2", "segment", "VIP"), Map.of("segment", "VIP"), Map.of("dc", "dc2")));
    }

    @Test
    @DisplayName("Правило без значения в снимок не попадает: подстановка в файле не разрешилась, в записи значения нет")
    void snapshot_shouldDropRule_whenRuleHasNoValue() {
        ParameterRule dc2 = ParameterRule.builder().whenPart("dc", "dc2").value(json("\"dc2\"")).build();
        StandFile file = StandFile.builder().service("orders")
                .parameter(definition("app.sample.notice", ParameterType.STRING, "\"обычный\"")
                        .rule(ParameterRule.builder().whenPart("dc", "dc1").value(json("\"${DC1_NOTICE}\"")).build())
                        .rule(dc2)
                        .build())
                .build();
        ParameterSet fromFile = ParameterSet.fromStandFile(file, TestDimensions.ALL, name -> null,
                (key, names) -> {
                });
        ParameterSet fromReplica = fromFile.withReplica(List.of(record("app.sample.notice", ParameterType.STRING,
                "\"обычный\"").rule(ParameterRule.builder().whenPart("dc", "dc1").build()).rule(dc2).build()));

        assertThat(List.of(whens(fromFile), whens(fromReplica))).containsOnly(List.of(Map.of("dc", "dc2")));
    }

    @Test
    @DisplayName("Правило, которое не подошло бы ни к одному пути, в снимок не попадает: незнакомое измерение, "
            + "часть без значения")
    void snapshot_shouldDropRule_whenPathCanNeverMatch() {
        ParameterSet set = FROM_FILE.withReplica(List.of(record("app.sample.timeout", ParameterType.DURATION,
                "\"PT20S\"")
                .rule(ParameterRule.builder().whenPart("region", "north").value(json("\"PT25S\"")).build())
                .rule(ParameterRule.builder().whenPart("dc", null).value(json("\"PT26S\"")).build())
                .rule(ParameterRule.builder().whenPart("dc", "dc2").value(json("\"PT30S\"")).build())
                .build()));

        assertThat(set.find("app.sample.timeout").orElseThrow().getRules()).extracting(ParameterRule::getWhen)
                .containsExactly(Map.of("dc", "dc2"));
    }

    private static List<Map<String, String>> whens(ParameterSet set) {
        return set.find("app.sample.notice").orElseThrow().getRules().stream().map(ParameterRule::getWhen).toList();
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
        "DURATION, 10s, PT10S",
        "DURATION, PT5S, PT5S",
        "BOOLEAN, TRUE, true",
        "BOOLEAN, off, false",
        "INTEGER, ' 42 ', 42"
    })
    @DisplayName("Подстановка из окружения приводится к типу так же, как свойство Spring")
    void fromStandFile_shouldCoercePlaceholder_likeSpring(ParameterType type, String variable, String expected) {
        ParameterSet set = ParameterSet.fromStandFile(typed(type, "${VALUE}"), TestDimensions.ALL,
                Map.of("VALUE", variable)::get, (key, names) -> {
                });

        assertThat(set.find("app.sample.value")).get()
                .extracting(parameter -> String.valueOf(type.read(parameter.getValue()))).isEqualTo(expected);
    }

    private static StandFile typed(ParameterType type, String value) {
        return StandFile.builder().service("orders").version("1").stand("dev")
                .parameter(ParameterDefinition.builder().key("app.sample.value").name("Значение")
                        .type(type).value(json("\"" + value + "\"")).build())
                .build();
    }

    private static StandFile numeric(String value) {
        return StandFile.builder().service("orders").version("1").stand("dev")
                .parameter(ParameterDefinition.builder().key("app.sample.retries").name("Повторы")
                        .type(ParameterType.INTEGER).value(json("\"" + value + "\"")).build())
                .build();
    }

    private static ParameterDefinition.ParameterDefinitionBuilder definition(String key, ParameterType type,
            String value) {
        return ParameterDefinition.builder().key(key).name(key).type(type).value(json(value));
    }

    private static ParameterRecord.ParameterRecordBuilder record(String key, ParameterType type, String value) {
        return ParameterRecord.builder().key(key).type(type).value(json(value)).version(5L);
    }

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
