package io.github.olegnyr.knobs.impl.group;

import io.github.olegnyr.knobs.Knob;
import io.github.olegnyr.knobs.KnobKey;
import io.github.olegnyr.knobs.ParameterMissingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Группа параметров — интерфейс с геттерами, за которым стоит прокси: каждый
 * вызов идёт за значением заново, ключ складывается из префикса группы и хвоста метода.
 */
@DisplayName("Группа параметров — живой прокси над читателем")
class ParameterGroupsTest {

    private final RecordingReader reader = new RecordingReader();

    @Knob("app.routing")
    interface Routing {

        int hopLimit();

        Duration segmentTimeout();

        @KnobKey("segment.call-enabled")
        boolean segmentCallEnabled();

        Optional<Duration> scriptTimeout();

        List<String> premiumSegments();

        default int doubledHopLimit() {
            return hopLimit() * 2;
        }
    }

    @Knob("")
    interface Rootless {

        String key();
    }

    interface Unannotated {

        int value();
    }

    @Knob("app.bad")
    interface WithArgument {

        int value(String name);
    }

    @Knob("app.routing")
    abstract static class RoutingBase {

        public abstract int hopLimit();

        protected abstract Duration segmentTimeout();

        public int doubledHopLimit() {
            return hopLimit() * 2;
        }

        public Duration halfSegmentTimeout() {
            return segmentTimeout().dividedBy(2);
        }

        @Override
        public String toString() {
            return "RoutingBase";
        }
    }

    @Knob("app.routing")
    static final class FinalGroup {
    }

    @Knob("app.routing")
    abstract static class WithoutNoArgConstructor {

        WithoutNoArgConstructor(String name) {
        }

        public abstract int hopLimit();
    }

    @Test
    @DisplayName("Ключ — префикс группы и имя метода в kebab-case")
    void hopLimit_shouldReadPrefixedKebabKey() {
        reader.values.put("app.routing.hop-limit", 7L);

        int value = group(Routing.class).hopLimit();

        assertThat(value).isEqualTo(7);
        assertThat(reader.keys).containsExactly("app.routing.hop-limit");
    }

    @Test
    @DisplayName("Явный хвост ключа из @KnobKey сильнее имени метода")
    void segmentCallEnabled_shouldReadExplicitKeyTail() {
        reader.values.put("app.routing.segment.call-enabled", true);

        assertThat(group(Routing.class).segmentCallEnabled()).isTrue();
    }

    @Test
    @DisplayName("Пустой префикс — ключ равен хвосту")
    void key_shouldReadBareTail_whenPrefixIsEmpty() {
        reader.values.put("key", "v");

        assertThat(group(Rootless.class).key()).isEqualTo("v");
    }

    @Test
    @DisplayName("Прокси живой: два вызова — два чтения, второе видит новое значение")
    void hopLimit_shouldReadAgain_onEveryCall() {
        reader.values.put("app.routing.hop-limit", 1L);
        Routing routing = group(Routing.class);

        int first = routing.hopLimit();
        reader.values.put("app.routing.hop-limit", 2L);
        int second = routing.hopLimit();

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(reader.keys).hasSize(2);
    }

    @Test
    @DisplayName("Читателю передаётся тип метода: примитив — упакованный, Optional — внутренний, список — с generics")
    void read_shouldReceiveMethodType_unwrapped() {
        reader.values.put("app.routing.hop-limit", 1L);
        reader.values.put("app.routing.script-timeout", Duration.ZERO);
        reader.values.put("app.routing.premium-segments", List.of());
        Routing routing = group(Routing.class);

        routing.hopLimit();
        routing.scriptTimeout();
        routing.premiumSegments();

        assertThat(reader.types.get(0)).isEqualTo(Integer.class);
        assertThat(reader.types.get(1)).isEqualTo(Duration.class);
        assertThat(reader.types.get(2)).isInstanceOf(ParameterizedType.class)
                .extracting(type -> ((ParameterizedType) type).getActualTypeArguments()[0])
                .isEqualTo(String.class);
    }

    @Test
    @DisplayName("Значения нет, метод возвращает Optional — пусто")
    void scriptTimeout_shouldReturnEmpty_whenValueIsMissing() {
        assertThat(group(Routing.class).scriptTimeout()).isEmpty();
    }

    @Test
    @DisplayName("Значения нет, метод возвращает примитив — ParameterMissingException с ключом")
    void hopLimit_shouldThrowMissing_whenValueIsMissing() {
        Throwable thrown = catchThrowable(() -> group(Routing.class).hopLimit());

        assertThat(thrown).isInstanceOf(ParameterMissingException.class)
                .hasMessageContaining("app.routing.hop-limit");
    }

    @Test
    @DisplayName("default-метод исполняется и видит прокси")
    void doubledHopLimit_shouldInvokeDefaultMethod() {
        reader.values.put("app.routing.hop-limit", 4L);

        assertThat(group(Routing.class).doubledHopLimit()).isEqualTo(8);
    }

    @Test
    @DisplayName("toString называет интерфейс и префикс; equals — по тождеству; читатель не вызывается")
    void objectMethods_shouldNotReadParameters() {
        Routing routing = group(Routing.class);

        assertThat(routing.toString()).contains("Routing", "app.routing");
        assertThat(routing).isEqualTo(routing).hasSameHashCodeAs(routing);
        assertThat(reader.keys).isEmpty();
    }

    @Test
    @DisplayName("Тип без @Knob — отказ при создании")
    void create_shouldReject_whenInterfaceIsNotAnnotated() {
        Throwable thrown = catchThrowable(() -> group(Unannotated.class));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unannotated");
    }

    @Test
    @DisplayName("Метод с параметрами — отказ при создании, а не при вызове")
    void create_shouldReject_whenMethodHasArguments() {
        Throwable thrown = catchThrowable(() -> group(WithArgument.class));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("value");
    }

    @Test
    @DisplayName("Абстрактный класс: абстрактные геттеры живые, в том числе protected; конкретные методы — как написаны")
    void abstractClass_shouldProxyAbstractGetters_andKeepConcreteMethods() {
        reader.values.put("app.routing.hop-limit", 3L);
        reader.values.put("app.routing.segment-timeout", Duration.ofSeconds(10));

        RoutingBase routing = group(RoutingBase.class);

        assertThat(routing.hopLimit()).isEqualTo(3);
        assertThat(routing.doubledHopLimit()).isEqualTo(6);
        assertThat(routing.halfSegmentTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(routing.toString()).isEqualTo("RoutingBase");
        assertThat(reader.keys).containsExactly("app.routing.hop-limit", "app.routing.hop-limit",
                "app.routing.segment-timeout");
    }

    @Test
    @DisplayName("Абстрактный класс без ByteBuddy на classpath — отказ при создании с подсказкой")
    void create_shouldFailWithHint_whenClassGroupAndByteBuddyIsAbsent() {
        Throwable thrown = catchThrowable(() -> ParameterGroups.create(RoutingBase.class, reader, false));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RoutingBase")
                .hasMessageContaining("net.bytebuddy:byte-buddy")
                .hasMessageContaining("интерфейс");
    }

    @Test
    @DisplayName("final-класс — отказ при создании: подкласс не построить")
    void create_shouldReject_whenClassIsFinal() {
        Throwable thrown = catchThrowable(() -> group(FinalGroup.class));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("final");
    }

    @Test
    @DisplayName("Класс без конструктора без аргументов — отказ при создании")
    void create_shouldReject_whenClassHasNoNoArgConstructor() {
        Throwable thrown = catchThrowable(() -> group(WithoutNoArgConstructor.class));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("конструктор");
    }

    @Test
    @DisplayName("Абстрактный класс: повторное создание группы не строит новый класс — Metaspace не растёт")
    void abstractClass_shouldReuseGeneratedSubclass() {
        assertThat(group(RoutingBase.class).getClass()).isSameAs(group(RoutingBase.class).getClass());
    }

    @Test
    @DisplayName("Унаследованный обобщённый геттер читается в типе аргумента интерфейса группы")
    void inheritedGenericGetter_shouldUseResolvedType() {
        reader.values.put("app.generic.value", 7L);

        IntHolder holder = group(IntHolder.class);
        int value = holder.value();

        assertThat(value).isEqualTo(7);
        assertThat(reader.types).containsExactly(Integer.class);
    }

    interface Holder<T> {

        T value();
    }

    @Knob("app.generic")
    interface IntHolder extends Holder<Integer> {
    }

    private <T> T group(Class<T> type) {
        return ParameterGroups.create(type, reader);
    }

    private static final class RecordingReader implements ParameterReader {

        final Map<String, Object> values = new HashMap<>();

        final List<String> keys = new ArrayList<>();

        final List<Type> types = new ArrayList<>();

        @Override
        public Optional<Object> read(String key, Type type) {
            keys.add(key);
            types.add(type);
            Object value = values.get(key);
            if (value instanceof Long number && type == Integer.class) {
                return Optional.of(number.intValue());
            }
            return Optional.ofNullable(value);
        }
    }
}
