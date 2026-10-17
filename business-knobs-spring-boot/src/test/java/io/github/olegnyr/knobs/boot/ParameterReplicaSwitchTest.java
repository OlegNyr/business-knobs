package io.github.olegnyr.knobs.boot;

import io.github.olegnyr.knobs.ParameterRecordApplier;
import io.github.olegnyr.knobs.impl.ParameterRecordApplierImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Рубильник реплики: под на файле и правках. */
@DisplayName("Рубильник реплики")
class ParameterReplicaSwitchTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String KEY = "app.sample.read-timeout";

    private static final Map<String, Object> SWITCHED_OFF = Map.of(ParameterRecordApplierImpl.REPLICA_ENABLED, "false");

    @Test
    @DisplayName("Рубильник включён — реплика не читается, действует файл")
    void loader_shouldSkipReplica_whenSwitchIsOn() {
        try (ConfigurableApplicationContext context = ParameterEndpointTest.run(SWITCHED_OFF)) {
            assertThat(context.getBean(InMemoryReplica.class).calls.get()).isZero();
            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT15S");
        }
    }

    @Test
    @DisplayName("Рубильник включён — запись топика не применяется")
    void apply_shouldSkip_whenSwitchIsOn() {
        try (ConfigurableApplicationContext context = ParameterEndpointTest.run(SWITCHED_OFF)) {
            ParameterRecordApplier.Outcome outcome = context.getBean(ParameterRecordApplier.class).apply(KEY,
                    MAPPER.readTree("{\"key\": \"%s\", \"type\": \"DURATION\", \"value\": \"PT30S\", \"version\": 8}"
                            .formatted(KEY)));

            assertThat(outcome).isEqualTo(ParameterRecordApplier.Outcome.SWITCHED_OFF);
            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT15S");
        }
    }
}
