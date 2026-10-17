package io.github.olegnyr.knobs.boot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRecordApplier;
import io.github.olegnyr.knobs.ParameterType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Аварийная заплатка на точке актуатора. */
@DisplayName("Аварийная заплатка")
class ParameterEndpointTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String KEY = "app.sample.read-timeout";

    private static final Principal OPERATOR = () -> "ops-fuz";

    private final ListAppender<ILoggingEvent> audit = new ListAppender<>();

    @BeforeEach
    void attachAudit() {
        audit.start();
        ((Logger) LoggerFactory.getLogger(ParameterEndpoint.AUDIT_LOGGER)).addAppender(audit);
    }

    @AfterEach
    void detachAudit() {
        ((Logger) LoggerFactory.getLogger(ParameterEndpoint.AUDIT_LOGGER)).detachAppender(audit);
    }

    @Test
    @DisplayName("Заплатка заменяет строку ключа целиком — 5s при любом ЦОД")
    void patch_shouldReplaceKeyRow_whenPatchIsValid() {
        try (ConfigurableApplicationContext context = run()) {
            WebEndpointResponse<Map<String, Object>> response = patch(context, KEY + ": {value: 5s}");

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT5S");
        }
    }

    @Test
    @DisplayName("Чтение точки показывает ключ, автора, причину и время")
    void patches_shouldListActivePatches_whenPatchIsSet() {
        try (ConfigurableApplicationContext context = run()) {
            patch(context, KEY + ": {value: 5s}");

            assertThat(endpoint(context).patches()).singleElement().satisfies(patch -> {
                assertThat(patch).containsEntry("key", KEY).containsEntry("author", "дежурный")
                        .containsEntry("reason", "CRM тормозит").containsKey("setAt");
            });
        }
    }

    @Test
    @DisplayName("Аудит — автор, субъект токена, причина, ключ; значения нет")
    void patch_shouldWriteAudit_withoutValue() {
        try (ConfigurableApplicationContext context = run()) {
            patch(context, KEY + ": {value: 17s}");

            assertThat(audit.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                    .satisfies(line -> assertThat(line).contains("дежурный", "ops-fuz", "CRM тормозит", KEY)
                            .doesNotContain("17s", "PT17S"));
        }
    }

    @Test
    @DisplayName("Перевод строки в причине не подделывает строку лога аудита")
    void patch_shouldStripLineBreaks_whenReasonHasThem() {
        try (ConfigurableApplicationContext context = run()) {
            endpoint(context).patch("дежурный", "проба\nINFO подделка", KEY + ": {value: 5s}", security());

            assertThat(audit.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                    .satisfies(line -> assertThat(line).doesNotContain("\n").contains("подделка"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        KEY + ": {value: \"${X}\"}",
        KEY + ": {value: abc}",
        "app.unknown.key: {value: 1}",
        KEY + ": {rules: [{when: /dc2, value: 7s}]}",
        KEY + ": {value: 5s, rules: [{when: {dc: '*', segment: VIP}, value: 7s}]}",
        KEY + ": {value: 5s, rules: [{value: 7s}]}",
        KEY + ": {rules: [{when: {dcc: dc2}, value: 7s}]}",
        KEY + ": {rules: [{when: {dc: [dc1, dc2]}, value: 7s}]}",
        KEY + ": {rules: [{when: {dc: dc2}, value: 7s}, {when: {dc: dc2}, value: 8s}]}",
        KEY + ": {}",
        KEY + ": {valeu: 5s}"
    })
    @DisplayName("Битая заплатка — 400 ProblemDetail с ключом, правка не применена")
    void patch_shouldReject_whenPatchIsBroken(String text) {
        try (ConfigurableApplicationContext context = run()) {
            WebEndpointResponse<Map<String, Object>> response = patch(context, text);

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(String.valueOf(response.getContentType())).isEqualTo("application/problem+json");
            assertThat(context.getEnvironment().getProperty(KEY)).as("действует строка реплики").isEqualTo("PT20S");
        }
    }

    @Test
    @DisplayName("Запись топика старше заплатки её не снимает, новее — снимает")
    void apply_shouldRemovePatch_onlyWhenRecordIsNewerThanPatch() {
        try (ConfigurableApplicationContext context = run()) {
            patch(context, KEY + ": {value: 5s}");
            long setAt = setAt(context);
            ParameterRecordApplier applier = context.getBean(ParameterRecordApplier.class);

            applier.apply(KEY, body("\"PT30S\"", setAt - 1));
            String older = context.getEnvironment().getProperty(KEY);
            applier.apply(KEY, body("\"PT40S\"", setAt + 1));

            assertThat(List.of(older, context.getEnvironment().getProperty(KEY))).containsExactly("PT5S", "PT40S");
        }
    }

    @Test
    @DisplayName("Отбракованная запись новее заплатки её не снимает")
    void apply_shouldKeepPatch_whenNewerRecordIsRejected() {
        try (ConfigurableApplicationContext context = run()) {
            patch(context, KEY + ": {value: 5s}");

            context.getBean(ParameterRecordApplier.class).apply(KEY, body("\"abc\"", setAt(context) + 1));

            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT5S");
        }
    }

    @Test
    @DisplayName("Запись удаления новее заплатки её снимает — действует файл")
    void apply_shouldRemovePatch_whenNewerRecordIsDeletion() {
        try (ConfigurableApplicationContext context = run()) {
            patch(context, KEY + ": {value: 5s}");

            context.getBean(ParameterRecordApplier.class).apply(KEY,
                    MAPPER.readTree("{\"deleted\": true, \"version\": %d}".formatted(setAt(context) + 1)));

            assertThat(context.getEnvironment().getProperty(KEY)).isEqualTo("PT15S");
        }
    }

    private static WebEndpointResponse<Map<String, Object>> patch(ConfigurableApplicationContext context,
            String text) {
        return endpoint(context).patch("дежурный", "CRM тормозит", text, security());
    }

    private static SecurityContext security() {
        return new SecurityContext() {
            @Override
            public Principal getPrincipal() {
                return OPERATOR;
            }

            @Override
            public boolean isUserInRole(String role) {
                return false;
            }
        };
    }

    private static long setAt(ConfigurableApplicationContext context) {
        return Instant.parse(String.valueOf(endpoint(context).patches().getFirst().get("setAt"))).toEpochMilli();
    }

    private static ParameterEndpoint endpoint(ConfigurableApplicationContext context) {
        return context.getBean(ParameterEndpoint.class);
    }

    private static JsonNode body(String value, long version) {
        return MAPPER.readTree("""
                {"key": "%s", "type": "DURATION", "value": %s, "rules": [], "version": %d}
                """.formatted(KEY, value, version));
    }

    static ConfigurableApplicationContext run(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("KNOBS_DC", "dc2", "KNOBS_DIMENSIONS", "dc,tenant,segment,channel",
                                "SAMPLE_HOST", "crm.dc2.example")));
        return new SpringApplicationBuilder(TestBeans.class)
                .environment(environment)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties("spring.application.name=orders")
                .properties(properties)
                .run();
    }

    private static ConfigurableApplicationContext run() {
        return run(Map.of());
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(ParameterAutoConfiguration.class)
    static class TestBeans {

        @Bean
        InMemoryReplica inMemoryReplica() {
            InMemoryReplica replica = new InMemoryReplica();
            replica.put(ParameterRecord.builder().key(KEY).type(ParameterType.DURATION)
                    .value(MAPPER.readTree("\"PT20S\"")).version(3L).build());
            return replica;
        }
    }
}
