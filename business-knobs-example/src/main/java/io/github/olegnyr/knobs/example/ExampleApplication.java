package io.github.olegnyr.knobs.example;

import io.github.olegnyr.knobs.ContextPath;
import io.github.olegnyr.knobs.ParameterContext;
import io.github.olegnyr.knobs.ParameterRecordApplier;
import io.github.olegnyr.knobs.ParameterTopicRecord;
import io.github.olegnyr.knobs.impl.Knobs;
import io.github.olegnyr.knobs.impl.ParameterPatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Сервис без Spring на business-knobs: магазин {@code shop} читает параметры оформления заказа.
 *
 * <p>Шаги по порядку:
 * <ol>
 *     <li>библиотека собирается кодом — файл своего стенда, ЦОД пода, измерения пути, реплика,
 *     слушатель смены;</li>
 *     <li>значения пода: файл стенда и правило по ЦОД;</li>
 *     <li>значения обращения: код на входе открывает путь обращения, и действуют правила по
 *     арендатору и сегменту;</li>
 *     <li>запись топика из админки меняет значение без рестарта — так сервис отдаёт библиотеке
 *     пачки своего потребителя;</li>
 *     <li>аварийная правка на поде;</li>
 *     <li>то же значение через стандартный {@code Client} OpenFeature.</li>
 * </ol>
 *
 * <p>Окружение: {@code KNOBS_STAND} — стенд ({@code dev} по умолчанию), {@code KNOBS_DC} — ЦОД пода,
 * {@code PAYMENT_URL} — подстановка в файле стенда.
 */
public final class ExampleApplication {

    /**
     * Имя сервиса — {@code service} в {@code src/main/knobs/knobs.yaml}.
     */
    private static final String SERVICE = "shop";

    /**
     * Ключ параметра, который меняют запись топика и OpenFeature в шагах 3 и 5.
     */
    private static final String MAX_ITEMS = "app.checkout.max-items";

    /**
     * Разбор тела записи топика: в настоящем сервисе тело приходит из потребителя брокера.
     */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ExampleApplication() {
    }

    /**
     * Запуск с окружением процесса.
     *
     * @param args не используются
     */
    public static void main(String[] args) {
        run(System.out, System::getenv);
    }

    /**
     * Проходит все шаги примера и печатает то, что видит код сервиса.
     *
     * @param out         куда печатать
     * @param environment имя переменной окружения → значение либо {@code null}
     */
    static void run(PrintStream out, Function<String, String> environment) {
        // Стенд выбирает файл стенда: в jar лежат knobs/knobs-dev.json и
        // knobs/knobs-prod.json, их на сборке сделал плагин business-knobs-maven-plugin.
        // В Spring-сервисе стенд — активный профиль; здесь — переменная окружения.
        String stand = Objects.requireNonNullElse(environment.apply("KNOBS_STAND"), "dev");

        // Knobs — вся библиотека без Spring, собранная одним сборщиком. Закрывается вместе с
        // сервисом: внутри изолированный экземпляр OpenFeature.
        try (Knobs knobs = Knobs.builder()
                // Сервис пода. Должен совпадать с service исходного knobs.yaml: правила
                // «только для своего сервиса» сравниваются с ним, иначе библиотека предупредит в логе.
                .service(SERVICE)
                // Стенды-кандидаты: файл берётся у того, для которого он есть в classpath.
                .stand(stand)
                // ЦОД пода — значение измерения dc. Берётся из настроек пода, а не из обращения:
                // все чтения на этом поде видят правило /dc2. Не задан — правила по ЦОД не подходят.
                .dc(environment.apply("KNOBS_DC"))
                // Измерения пути (dc → tenant → segment) не задаём: порядок точности берётся из
                // секции dimensions файла стенда — той же, по которой генератор разбирал пути
                // правил. .dimensions(...) нужен, только если файл измерений не объявляет; заданный
                // и расходящийся с файлом — отказ build().
                // Откуда разрешать подстановки ${ИМЯ} файла стенда: здесь ${PAYMENT_URL}.
                // По умолчанию — переменные окружения процесса; тест подменяет их картой.
                .environment(environment)
                // Реплика — копия записей админки в базе сервиса: правка из админки переживает
                // рестарт, даже если топик недоступен. Без реплики под работает на файле и записях.
                .replica(new InMemoryReplica())
                // Слушатель смены: зовётся, когда у ключей сменилось действующее значение или
                // правила. Геттерам группы он не нужен — они и так читают на ходу; слушатель для
                // тех, кому по смене надо действовать: пересоздать пул, перепланировать таймер.
                .listener(event -> out.println("изменились ключи: " + event.getKeys()))
                // build() читает файл стенда, затем реплику поверх него — до того, как сервис
                // начнёт читать топик.
                .build()) {

            // Нет файла своего стенда в classpath — библиотека поднимается с пустым снимком, и
            // первый же геттер группы бросит ParameterMissingException. В примере так бывает,
            // когда его запускают из IDE без сборки Maven: файлы стендов делает плагин.
            if (knobs.store().parameters().parameters().isEmpty()) {
                out.println("Нет файла стенда knobs/knobs-" + stand + ".json в classpath. Его делает плагин "
                        + "business-knobs-maven-plugin на сборке Maven:");
                out.println("  ./mvnw -f business-knobs-example/pom.xml process-resources");
                out.println("В IDEA — конфигурация запуска ExampleApplication из .run/: она вызывает эту цель "
                        + "перед стартом. Стенды примера — dev и prod.");
                return;
            }

            // Группа параметров — прокси над интерфейсом. Значений не хранит: каждый геттер
            // читает действующее значение в момент вызова.
            CheckoutKnobs checkout = knobs.group(CheckoutKnobs.class);

            // Шаг 1. Путь пода — service и dc. Без открытого обращения действуют файл стенда и
            // правила по ЦОД: payment-timeout = 15s по правилу /dc2.
            out.println("== Путь пода " + knobs.store().podPath());
            print(out, checkout);

            // Шаг 2. Путь обращения. Его открывает код сервиса на входе — HTTP-фильтр,
            // перехватчик брокера — значениями измерений, которые известны об обращении.
            // Пока область открыта, чтения на этом потоке видят правила по арендатору и сегменту.
            ContextPath request = ContextPath.builder()
                    .put("tenant", "acme")
                    .put("segment", "VIP").build();

            out.println("== Обращение " + request);

            try (ParameterContext.Scope ignored = ParameterContext.open(request)) {
                print(out, checkout);
            }

            // Шаг 3. Запись топика из админки. Настоящий сервис держит потребителя компактного
            // топика и отдаёт библиотеке его пачки; ключ записи — ключ параметра, тело — слепок
            // параметра с версией. Запись заменяет строку ключа целиком: правило для VIP из
            // файла больше не действует, пока админка не пришлёт его в записи.
            out.println("== Запись топика: " + MAX_ITEMS + " = 50");
            JsonNode body = JSON.readTree("""
                    {
                      "type": "INTEGER",
                      "value": 50,
                      "version": 1
                    }
                    """);
            List<ParameterRecordApplier.Outcome> outcomes =
                    knobs.records().applyAll(List.of(new ParameterTopicRecord(MAX_ITEMS, body)));
            out.println("исход: " + outcomes);
            print(out, checkout);

            // Шаг 4. Аварийная правка — в память этого пода, мимо админки: на случай, когда
            // ждать админку нельзя. Автор, субъект и причина уходят в лог аудита
            // knobs.audit. Снимается рестартом или более новой записью топика.
            // В Spring-сервисе то же делает точка актуатора /actuator/parameters.
            out.println("== Аварийная правка: app.checkout.payment-timeout = 30s");
            ParameterPatcher.Result patch = knobs.patches()
                    .apply("ops", "example", "медленный платёжный шлюз",
                    "app.checkout.payment-timeout: {value: 30s}");
            out.println("поставлены: " + patch.keys());
            if (!patch.errors().isEmpty()) {
                out.println("ошибки: " + patch.errors());
            }
            print(out, checkout);

            // Шаг 5. Те же значения через стандартный Client OpenFeature — для кода, который
            // уже говорит на OpenFeature, и для динамических ключей. Атрибуты контекста
            // OpenFeature с именами измерений работают как путь обращения.
            out.println("== OpenFeature");
            out.println("max-items = " + knobs.parameters().client().getIntegerValue(MAX_ITEMS, 0));
        }
    }

    private static void print(PrintStream out, CheckoutKnobs checkout) {
        out.println("max-items = " + checkout.maxItems());
        out.println("payment-timeout = " + checkout.paymentTimeout());
        out.println("greeting = " + checkout.greeting());
        out.println("express-delivery = " + checkout.expressDelivery());
        out.println("payment-url = " + checkout.paymentUrl().orElse("не задан"));
    }
}
