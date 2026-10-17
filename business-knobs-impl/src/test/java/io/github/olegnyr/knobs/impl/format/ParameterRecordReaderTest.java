package io.github.olegnyr.knobs.impl.format;

import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRule;
import io.github.olegnyr.knobs.ParameterType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Разбор записи топика {@code knobs.parameters.v1} по контракту. */
@DisplayName("Запись топика читается по контракту")
class ParameterRecordReaderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("Слепок с ключом, версией, значением и правилами; описательные поля пропущены")
    void read_shouldParseSnapshot_whenBodyFollowsContract() {
        ParameterRecord record = ParameterRecordReader.read(MAPPER.readTree("""
                {"key": "app.crm.read-timeout", "name": "Таймаут чтения CRM", "type": "DURATION",
                 "min": "PT0.1S", "max": "PT1M", "value": "PT10S",
                 "rules": [{"when": {"dc": "dc2"}, "value": "PT15S"}], "version": 1760400000000}
                """));

        assertThat(record).isEqualTo(ParameterRecord.builder()
                .key("app.crm.read-timeout")
                .type(ParameterType.DURATION)
                .value(MAPPER.readTree("\"PT10S\""))
                .rule(ParameterRule.builder().whenPart("dc", "dc2").value(MAPPER.readTree("\"PT15S\"")).build())
                .version(1_760_400_000_000L)
                .build());
    }

    @Test
    @DisplayName("Null-правило в записи топика — запись не разбирается")
    void read_shouldReject_whenRulesContainNull() {
        Throwable thrown = catchThrowable(() -> ParameterRecordReader.read(MAPPER.readTree("""
                {"key": "app.x", "type": "STRING", "value": "a", "rules": [null], "version": 1}
                """)));

        assertThat(thrown).isInstanceOf(ParameterFormatException.class).hasMessageContaining("app.x");
    }

    @Test
    @DisplayName("Путь правила повторяется — запись не читается: выбор зависел бы от порядка правил")
    void read_shouldReject_whenRulePathRepeats() {
        Throwable thrown = catchThrowable(() -> ParameterRecordReader.read(MAPPER.readTree("""
                {"key": "app.x", "type": "INTEGER", "value": 1, "version": 1,
                 "rules": [{"when": {"dc": "dc2"}, "value": 2}, {"when": {"dc": "dc2"}, "value": 3}]}
                """)));

        assertThat(thrown).isInstanceOf(ParameterFormatException.class).hasMessageContaining("app.x");
    }
}
