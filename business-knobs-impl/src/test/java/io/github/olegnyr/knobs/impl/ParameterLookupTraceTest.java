package io.github.olegnyr.knobs.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Трассировка чтения параметра: на уровне TRACE видно, как выбрано значение, — путь
 * выбора, источник, разбор каждого правила и победившее правило, — а самих значений в логе нет.
 * Оракул двусторонний: объяснение есть и значений нет.
 */
@DisplayName("Трассировка чтения параметра: путь, источник, правила — без значений")
class ParameterLookupTraceTest {

    private final Logger library = (Logger) LoggerFactory.getLogger("io.github.olegnyr.knobs");

    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    private Level previous;

    @BeforeEach
    void setUp() {
        previous = library.getLevel();
        library.setLevel(Level.TRACE);
        log.start();
        library.addAppender(log);
    }

    @AfterEach
    void tearDown() {
        library.detachAppender(log);
        library.setLevel(previous);
    }

    @Test
    @DisplayName("Видны путь выбора, источник, совпавшая часть и правило ЦОД; значений PT10S и PT15S нет")
    void lookup_shouldTracePathSourceAndRule_withoutValues() {
        Optional<Object> value = read("app.sample.read-timeout");

        assertThat(value).isPresent();
        assertThat(trace())
                .contains("app.sample.read-timeout", "service=orders", "dc=dc2", "segment=VIP", "FILE", "{dc=dc2}",
                        "dc совпала — dc2")
                .doesNotContain("PT10S", "PT15S");
    }

    @Test
    @DisplayName("Видны порядок правил по точности, почему не подошло первое и что на подошедшем проверка "
            + "закончилась; значений правил нет")
    void lookup_shouldTraceRankAndFirstMatch_withoutValues() {
        Optional<Object> value = read("app.sample.wait-notice");

        assertThat(value).isPresent();
        assertThat(trace())
                .contains("правила по точности — [{channel=chat}, {segment=VIP}, {tenant=tenant-2}]",
                        "правило {channel=chat} не подходит — channel: в правиле "
                                + "chat, в пути не задано",
                        "действует правило {segment=VIP} — первое подошедшее по точности")
                .doesNotContain("правило {tenant=tenant-2} не подходит", "арендатор", "обычный");
    }

    @Test
    @DisplayName("При сборке видно, какое правило в выборе не участвует и почему; при чтении оно уже не проверяется; "
            + "значений нет")
    void build_shouldTraceDroppedRule_withoutValues() {
        Optional<Object> value = read("app.sample.mirror-url");

        assertThat(value).isPresent();
        assertThat(trace())
                .contains("Ключ app.sample.mirror-url: правило {dc=dc1} в выборе не участвует — без значения")
                .doesNotContain("правило {dc=dc1} не подходит", "правило {dc=dc1} без значения", "https://");
    }

    private Optional<Object> read(String key) {
        try (Knobs settings = Knobs.builder().service("orders").stand("test")
                .dimensions(TestDimensions.ALL).dc("dc2").build();
                ParameterContext.Scope ignored = ParameterContext.open(ContextPath.builder()
                        .put("segment", "VIP").build())) {
            return settings.parameters().value(key);
        }
    }

    private String trace() {
        return log.list.stream()
                .filter(event -> event.getLevel() == Level.TRACE)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
