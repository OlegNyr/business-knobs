package io.github.olegnyr.knobs.impl;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterContext;
import io.github.olegnyr.knobs.ParameterRecord;
import io.github.olegnyr.knobs.ParameterRecordApplier;
import io.github.olegnyr.knobs.ParameterReplica;
import io.github.olegnyr.knobs.ParameterTopicRecord;
import io.github.olegnyr.knobs.ParameterType;
import io.github.olegnyr.knobs.ParametersChanged;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Библиотека параметров работает без Spring — файл стенда, реплика,
 * запись топика, событие смены и аварийная правка собираются сборщиком {@link Knobs}.
 */
@DisplayName("Библиотека параметров без Spring")
class KnobsTest {

    private static final String TIMEOUT = "app.sample.read-timeout";

    private static final String NOTICE = "app.sample.wait-notice";

    private static final ContextPath VIP = ContextPath.builder().put("segment", "VIP").build();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("Метрики без Spring: наблюдатель Micrometer видит снимок, который собрал build()")
    void observer_shouldWatchBuiltStore_whenCreatedByFactory() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (Knobs settings = Knobs.builder().service("orders").stand("test").dc("dc2")
                .observer(store -> new MicrometerParameterObserver(registry, store)).build()) {
            settings.patches().apply("дежурный", "svc-ops", "инцидент", TIMEOUT + ": {value: 5s}");
            assertThat(registry.get("knobs.parameters.patches").gauge().value()).isEqualTo(1.0);
            settings.records().apply(TIMEOUT, JSON.readTree("""
                    {"type": "DURATION", "value": "PT30S", "version": 9999999999999}
                    """));

            assertThat(registry.get("knobs.parameters.patches").gauge().value())
                    .as("правку сняла запись новее — заплаток не осталось").isZero();
            assertThat(registry.get("knobs.parameters.records").tag("outcome", "applied").counter().count())
                    .isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("byPrefix — по сегментам ключа: «app.sam» не префикс «app.sample»")
    void byPrefix_shouldMatchWholeSegments() {
        try (Knobs settings = Knobs.builder().service("orders").stand("test").dc("dc2").build()) {
            assertThat(settings.parameters().byPrefix("app.sam")).isEmpty();
            assertThat(settings.parameters().byPrefix("app.sample")).containsKey(TIMEOUT);
            assertThat(settings.parameters().byPrefix("app.sample.")).containsKey(TIMEOUT);
        }
    }

    @Test
    @DisplayName("Запись топика без ключа — исход «не читается», а не NPE, и при пустом снимке")
    void records_shouldRejectNullKey() {
        try (Knobs settings = Knobs.builder().service("orders").stand("nowhere").build()) {
            JsonNode body = JSON.readTree("{\"type\": \"INTEGER\", \"value\": 1, \"version\": 1}");
            List<ParameterRecordApplier.Outcome> outcomes = settings.records()
                    .applyAll(List.of(new ParameterTopicRecord(null, body)));

            assertThat(outcomes).containsExactly(ParameterRecordApplier.Outcome.REJECTED_UNREADABLE);
        }
    }

    @Test
    @DisplayName("Сборщик, изменённый после build(), не меняет уже собранную библиотеку")
    void build_shouldNotFollowBuilderChanges() {
        Knobs.Builder builder = Knobs.builder().service("orders").stand("test").dc("dc2");
        try (Knobs settings = builder.build()) {
            builder.replicaEnabled(false);

            assertThat(settings.records().apply(TIMEOUT, JSON.readTree("""
                    {"type": "DURATION", "value": "PT30S", "version": 9999999999999}
                    """))).isEqualTo(ParameterRecordApplier.Outcome.APPLIED);
        }
    }

    @Test
    @DisplayName("Логгер аудита правок — вне пакета библиотеки: приглушённая библиотека не глушит аудит")
    void auditLogger_shouldNotInheritLibraryLevel() {
        assertThat(ParameterPatcher.AUDIT_LOGGER).isEqualTo("knobs.audit")
                .doesNotStartWith("io.github.olegnyr.knobs");
    }

    @Test
    @DisplayName("Измерения не заданы — порядок берётся из секции dimensions файла стенда")
    void build_shouldTakeDimensionsFromStandFile_whenNotConfigured() {
        try (Knobs settings = Knobs.builder().service("orders").stand("test").dc("dc2").build();
             ParameterContext.Scope scope = ParameterContext.open(VIP)) {
            assertThat(settings.store().parameters().dimensions().order())
                    .containsExactly("dc", "tenant", "segment", "channel");
            assertThat(settings.parameters().value(NOTICE)).contains("vip");
        }
    }

    @Test
    @DisplayName("Измерения заданы и совпадают с файлом — действуют")
    void build_shouldAcceptConfiguredDimensions_whenTheyMatchStandFile() {
        try (Knobs settings = Knobs.builder().service("orders").stand("test").dc("dc2")
                .dimensions("dc", "tenant", "segment", "channel").build()) {
            assertThat(settings.store().parameters().dimensions().order())
                    .containsExactly("dc", "tenant", "segment", "channel");
        }
    }

    @Test
    @DisplayName("Измерения заданы и расходятся с файлом — сборка падает и называет оба списка")
    void build_shouldFail_whenConfiguredDimensionsDifferFromStandFile() {
        Throwable thrown = catchThrowable(() -> Knobs.builder().service("orders").stand("test")
                .dimensions("dc", "segment").build());

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[dc, segment]")
                .hasMessageContaining("[dc, tenant, segment, channel]");
    }

    @Test
    @DisplayName("Файла стенда нет — действуют заданные измерения, без них только dc")
    void build_shouldUseConfiguredOrDefaultDimensions_whenStandFileIsMissing() {
        try (Knobs configured = Knobs.builder().service("orders").stand("nowhere")
                .dimensions("dc", "segment").build();
             Knobs plain = Knobs.builder().service("orders").stand("nowhere").build()) {
            assertThat(configured.store().parameters().dimensions().order()).containsExactly("dc", "segment");
            assertThat(plain.store().parameters().dimensions().order()).containsExactly("dc");
        }
    }

    @Test
    @DisplayName("Файл своего стенда и ЦОД пода — значение по правилу ЦОД, без Spring-контекста")
    void parameters_shouldReadStandFile_withoutSpring() {
        try (Knobs settings = Knobs.builder().service("orders").stand("test")
                .dimensions(TestDimensions.ALL).dc("dc2").build()) {
            assertThat(settings.parameters().duration(TIMEOUT)).contains(Duration.ofSeconds(15));
        }
    }

    @Test
    @DisplayName("Реплика перекрывает файл на сборке, запись топика применяется и будит слушателя")
    void records_shouldApplyTopicRecord_andNotifyListener() {
        Replica replica = new Replica();
        replica.rows.put(TIMEOUT, record("PT20S", 1));
        List<ParametersChanged> events = new ArrayList<>();
        try (Knobs settings = Knobs.builder().service("orders").stand("test").dimensions(TestDimensions.ALL).dc("dc1")
                .replica(replica).listener(events::add).build()) {
            Optional<Duration> fromReplica = settings.parameters().duration(TIMEOUT);

            List<ParameterRecordApplier.Outcome> outcomes = settings.records().applyAll(List.of(
                    new ParameterTopicRecord(TIMEOUT, JSON.valueToTree(record("PT30S", 2)))));

            assertThat(List.of(fromReplica, outcomes, settings.parameters().duration(TIMEOUT)))
                    .containsExactly(Optional.of(Duration.ofSeconds(20)),
                            List.of(ParameterRecordApplier.Outcome.APPLIED),
                            Optional.of(Duration.ofSeconds(30)));
            assertThat(events).last().extracting(ParametersChanged::getKeys).asInstanceOf(
                    org.assertj.core.api.InstanceOfAssertFactories.collection(String.class)).containsExactly(TIMEOUT);
        }
    }

    @Test
    @DisplayName("Отказ одного слушателя не мешает следующему")
    void listeners_shouldBeIsolated_whenOneFails() {
        List<ParametersChanged> events = new ArrayList<>();
        try (Knobs settings = Knobs.builder().service("orders").stand("test").dimensions(TestDimensions.ALL).dc("dc1")
                .listener(event -> {
                    throw new IllegalStateException("потребитель упал");
                })
                .listener(events::add).build()) {
            settings.records().applyAll(List.of(
                    new ParameterTopicRecord(TIMEOUT, JSON.valueToTree(record("PT30S", 2)))));

            assertThat(events).hasSize(1);
        }
    }

    @Test
    @DisplayName("Аварийная правка ставится без актуатора и перекрывает значение")
    void patches_shouldApplyPatch_withoutActuator() {
        try (Knobs settings = Knobs.builder().service("orders").stand("test")
                .dimensions(TestDimensions.ALL).dc("dc2").build()) {
            ParameterPatcher.Result result = settings.patches().apply("дежурный", "svc-ops", "инцидент",
                    TIMEOUT + ": {value: 5s}");

            assertThat(List.of(result.errors(), result.keys(), settings.parameters().duration(TIMEOUT)))
                    .containsExactly(Map.of(), List.of(TIMEOUT), Optional.of(Duration.ofSeconds(5)));
        }
    }

    @Test
    @DisplayName("Файла стенда нет — снимок пустой: значения нет, запись топика — чужой ключ, правка отбита")
    void build_shouldWorkOnEmptySnapshot_whenStandFileMissing() {
        try (Knobs settings = Knobs.builder().service("orders").stand("nowhere")
                .dimensions(TestDimensions.ALL).dc("dc2").build()) {
            assertThat(List.of(settings.parameters().value(TIMEOUT), settings.records().applyAll(List.of(
                    new ParameterTopicRecord(TIMEOUT, JSON.valueToTree(record("PT30S", 2))))),
                    settings.patches().apply("дежурный", "svc-ops", "инцидент", TIMEOUT + ": {value: 5s}").errors(),
                    settings.store().podPath().get(ContextPath.DC)))
                    .containsExactly(Optional.empty(), List.of(ParameterRecordApplier.Outcome.FOREIGN),
                            Map.of(TIMEOUT, "ключа нет в каталоге сервиса"), Optional.of("dc2"));
        }
    }

    @Test
    @DisplayName("Исполнение библиотеки не импортирует Spring — ни в одном пакете модуля")
    void sources_shouldNotImportSpring_inImplModule() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("import org.springframework.")) {
                    offenders.add(root.relativize(file).toString());
                }
            }
        }

        assertThat(offenders).isEmpty();
    }

    private static ParameterRecord record(String value, long version) {
        return ParameterRecord.builder()
                .key(TIMEOUT)
                .type(ParameterType.DURATION)
                .value(JsonNodeFactory.instance.stringNode(value))
                .version(version)
                .build();
    }

    /** Реплика в памяти: без базы и без Spring. */
    private static final class Replica implements ParameterReplica {

        private final Map<String, ParameterRecord> rows = new LinkedHashMap<>();

        @Override
        public List<ParameterRecord> findAll() {
            return new ArrayList<>(rows.values());
        }

        @Override
        public boolean saveIfNewer(ParameterRecord record) {
            ParameterRecord stored = rows.get(record.getKey());
            if (stored != null && stored.getVersion() >= record.getVersion()) {
                return false;
            }
            rows.put(record.getKey(), record);
            return true;
        }

        @Override
        public Optional<ParameterRecord> find(String key) {
            return Optional.ofNullable(rows.get(key));
        }
    }
}
