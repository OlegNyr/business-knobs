# business-knobs

Контекстные параметры сервиса: значения, которые администратор правит без выкатки, а сервис
читает на ходу — при каждом обращении, с учётом контекста. Значение зависит от пути пода (сервис,
ЦОД) и пути обращения — измерений, которые выбирает сам сервис: арендатор, сегмент клиента,
канал, регион, тариф.

- Параметры объявляются в `knobs.yaml`; сборка генерирует по файлу на стенд.
- Правки приходят записями компактного топика и переживают рестарт через реплику в базе
  сервиса. Брокер и база — на стороне сервиса: библиотека даёт SPI.
- Читать — типизированными геттерами (`@Knob`), через `Parameters`, `Client`
  OpenFeature или `Environment` Spring.
- Аварийная правка на поде через актуатор, рубильник реплики, метрики Micrometer.
- Работает со Spring Boot 4 и без Spring.

## Модули

| Артефакт | Что внутри |
|---|---|
| `business-knobs-api` | API для кода сервиса: `Parameters`, `ParameterContext`, `@Knob`, SPI реплики и записи топика |
| `business-knobs-impl` | Исполнение без Spring: снимок, выбор значения, группы, применение записей, аварийная правка, `Knobs.builder()` |
| `business-knobs-spring-boot` | Автоконфигурация Spring Boot, слой в `Environment`, `@EnableKnobs`, точка актуатора |
| `business-knobs-maven-plugin` | Плагин Maven: `knobs:generate` делает файлы стендов из `knobs.yaml` на сборке сервиса — `business-knobs-maven-plugin/README.md` |
| `business-knobs-example` | Пример сервиса без Spring — отдельный проект-потребитель, в Central не выкладывается; `business-knobs-example/README.md` |

Группа — `io.github.olegnyr`, Java 21, Spring Boot 4, Jackson 3.

## Быстрый старт (Spring Boot)

```xml
<dependency>
    <groupId>io.github.olegnyr</groupId>
    <artifactId>business-knobs-spring-boot</artifactId>
    <version>0.1.0</version>
</dependency>
```

`src/main/knobs/knobs.yaml`:

```yaml
service: orders
stands: [dev, prod]
dimensions:
  service: { kind: registry }
  dc:      { kind: stand }
  segment: { kind: code, values: [MASS, VIP] }
parameters:
  app:
    routing:
      hop-limit:
        name: Потолок переходов
        default: 5
        rules:
          - when: [/*/VIP]       # места пути — dc/segment; * — любое
            value: 8
```

Генерация файлов стендов на сборке — плагин кладёт `knobs/knobs-<стенд>.json` в
ресурсы сам, настраивать `<resources>` не нужно:

```xml
<plugin>
    <groupId>io.github.olegnyr</groupId>
    <artifactId>business-knobs-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Параметры цели: `source` (`src/main/knobs/knobs.yaml`), `outputDirectory`
(`target/generated-resources/knobs`), `version` (`${project.version}`), `skip`
(`-Dknobs.generate.skip`).

Настройки пода (`application.yaml` либо переменные окружения):

```yaml
spring:
  application:
    name: orders            # = service файла
  profiles:
    active: prod            # = стенд: берётся knobs/knobs-prod.json
knobs:
  dc: dc2                   # KNOBS_DC — ЦОД пода; измерения и их порядок — из dimensions файла
```

Чтение:

```java
@Knob("app.routing")
public interface RoutingKnobs {
    int hopLimit();                         // app.routing.hop-limit, читается при каждом вызове
}

@AutoConfiguration
@EnableKnobs(basePackageClasses = RoutingKnobs.class)
public class RoutingAutoConfiguration {
}

// на входе обращения
try (ParameterContext.Scope scope = ParameterContext.open(
        ContextPath.builder().put("segment", "VIP").build())) {
    routing.hopLimit();                     // 8
}
```

Без Spring (полный пример — модуль `business-knobs-example`):

```java
try (Knobs knobs = Knobs.builder()
        .service("orders").stand("prod").dc("dc2")
        .build()) {
    RoutingKnobs routing = knobs.group(RoutingKnobs.class);
}
```

## Документация

- `doc/guide/howto-parameter-groups.adoc` — инструкция по группам параметров и измерениям.
- `doc/guide/knobs-example.yaml` — пример исходного файла с комментариями.
- `doc/contract/stand-file.adoc` — формат файла стенда и записи топика (контракт с админкой).
- `doc/adr/` — архитектурные решения.
- `.claude/skills/business-knobs/SKILL.md` — подробная памятка по подключению (и скил для Claude Code).

## Сборка

```bash
./mvnw verify            # сборка и тесты
./mvnw -P quality verify # Checkstyle, PMD, SpotBugs
./mvnw install -DskipTests && ./mvnw -f business-knobs-example/pom.xml verify   # пример
```

Пример — отдельный проект: он подключает библиотеку и плагин по координатам, поэтому синк
корневого проекта в IDE их не требует.

Выкладка в Maven Central — `RELEASING.md` (из GitHub Actions по тегу `vX.Y.Z`). Изменения —
`CHANGELOG.md`.

## Лицензия

Apache License 2.0 — `LICENSE`.
