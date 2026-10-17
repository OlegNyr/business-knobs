---
name: business-knobs
description: How to use the business-knobs contextual-parameters library (io.github.olegnyr:business-knobs-api / -impl / -spring-boot / -maven-plugin) in a service — declare parameters in knobs.yaml, generate stand files, declare path dimensions in knobs.yaml (their order is taken from the stand file), read values through @Knob getters, Parameters or Environment, open the request context, wire the DB replica and the records topic, use the emergency patch endpoint and the kill switch, and guard it all with tests. Use whenever a task mentions контекстные параметры, параметры стенда, knobs.yaml, @Knob, ParameterContext, knobs.dimensions, knobs.dc, /actuator/parameters, «читать на ходу без рестарта», or asks to move a setting from application.yaml / env to an admin console.
---

# business-knobs: контекстные параметры

Библиотека для настроек поведения, которые администратор правит **без выкатки**: таймауты,
пороги, веса, признаки, тексты. Параметр читается на каждом использовании, поэтому правка из
админки действует без рестарта, а значение зависит от *контекста*: ЦОД пода и измерений
обращения, которые задаёт сервис (арендатор, сегмент клиента, канал — что нужно).

Решения — `doc/adr/`. Формат файла стенда и записи топика — `doc/contract/stand-file.adoc`.
Инструкция по группам — `doc/guide/howto-parameter-groups.adoc`.

## 1. Модули и что от кого зависит

| Модуль | Пакет | Что внутри | Кому нужен |
|--------|-------|------------|------------|
| `business-knobs-api` | `io.github.olegnyr.knobs` | То, что видит код сервиса: `Parameters`, `ParameterContext`, `ContextPath`, `@Knob`, `@KnobKey`, `ParametersChanged`; SPI: `ParameterReplica`, `ParameterRecord`, `ParameterRecordApplier`, `ParameterJson` | Любой модуль сервиса, который читает параметры |
| `business-knobs-impl` | `io.github.olegnyr.knobs.impl` | Исполнение без Spring: снимок `ParameterStore`, выбор `ParameterLookup`, порядок измерений `ContextDimensions`, прокси групп, применение записей, заплатки, `Knobs.builder()` | Сборка без Spring; Spring-модуль |
| `business-knobs-spring-boot` | `io.github.olegnyr.knobs.boot` | Автоконфигурация, слой в `Environment`, `@EnableKnobs`, точка `/actuator/parameters`, метрики | Spring Boot-сервис |
| `business-knobs-maven-plugin` | `io.github.olegnyr.knobs.generator` | Плагин Maven, цель `generate`: из `knobs.yaml` делает `knobs-<стенд>.json` на сборке; `StandFileGenerator` — то же без Maven | Шаг сборки сервиса |

Пример сервиса без Spring — отдельный проект `business-knobs-example` (`ExampleApplication`): так
подключают библиотеку и плагин в своём сервисе.

Зависимость односторонняя: `spring-boot → impl → api`. В `api` запрещены импорты `impl` и
`boot` — это держит `ApiBoundaryTest`; в `impl` нет Spring — это держит `KnobsTest`.

Необязательные зависимости (`optional`), их приносит сервис, если хочет функцию:
`snakeyaml` — разбор заплатки; `micrometer-core` — метрики; `spring-boot-actuator` — точка
актуатора; `net.bytebuddy:byte-buddy` — группы на абстрактных классах.

## 2. Подключить к сервису

1. Зависимость сервиса: `io.github.olegnyr:business-knobs-spring-boot` (транзитивно `impl` и
   `api`). Модулю, который только реализует порт реплики, достаточно `business-knobs-api`.
2. Исходник параметров — `src/main/knobs/knobs.yaml` модуля приложения (см. §3).
3. Генератор на сборке — плагин `io.github.olegnyr:business-knobs-maven-plugin`, цель `generate`
   (фаза `generate-resources` по умолчанию). Читает `src/main/knobs/knobs.yaml`, пишет
   `target/generated-resources/knobs/knobs/knobs-<стенд>.json` и сам добавляет каталог
   в ресурсы — файлы стендов попадают в jar как `knobs/knobs-<стенд>.json` и **в
   репозиторий не коммитятся**. Параметры: `source`, `outputDirectory`, `version`
   (`${project.version}`), `skip` (`knobs.generate.skip`).
4. Профиль пода = стенд: `SPRING_PROFILES_ACTIVE=dev` → под берёт `knobs-dev.json`.
   Без подходящего файла слоя параметров нет; файлы у двух активных профилей — подъём падает
   («стенд неоднозначен»).
5. Настройки библиотеки `knobs.*` (`ParameterSettingsProperties`):
   - `knobs.dc` / `KNOBS_DC` — ЦОД пода;
   - `knobs.dimensions` / `KNOBS_DIMENSIONS` — обычно **не задаётся**: порядок измерений берётся
     из секции `dimensions` файла стенда (§5). Нужен только файлу без этой секции; расходящийся
     с файлом — отказ на старте;
   - `knobs.replica-enabled` — рубильник (§9).

   Сервис — `spring.application.name`: он должен совпадать с `service` файла, иначе правила по
   сервису не сработают (WARN на старте).
6. Реплика в базе и приёмник топика — код сервиса (§6, §7).

Подстановки `${ИМЯ}` в значениях разрешаются **только из переменных окружения процесса** при
чтении файла; неразрешённая — параметр без значения из файла и WARN с ключом и именем переменной.
Из админки текст с `${` не принимается.

## 3. `knobs.yaml`

Полная справка — `reference/knobs-yaml.md`, живой пример — `doc/guide/knobs-example.yaml`.
Коротко:

```yaml
service: orders
stands: [dev, test, prod]
dimensions:                      # порядок = порядок точности и мест короткого пути; новое — в конец
  service: { kind: registry }
  dc:      { kind: stand }
  tenant:  { kind: stand }
  segment: { kind: code, values: [MASS, PREMIUM, PRIVATE, VIP] }
parameters:
  app:
    routing:
      hop-limit:                 # ключ app.routing.hop-limit
        name: Потолок переходов  # обязательно
        description: …           # зачем и чем грозит
        default: 5               # тип выводится: integer
        min: 1
      segment-timeout:
        default: 10s             # duration
        rules:
          all:
            - when: [/dc2]       # места пути — dc/tenant/segment; * — любое
              value: 15s
            - when: [{segment: VIP}]   # именованная форма — порядку не важен
              value: 20s
          dev:                   # правило стенда: dev / test / prod
            - value: 60s
```

Правила, которые ломают сборку или поведение, если забыть:

- **Ключ — только kebab-case** (`hop-limit`), сегменты через точку. Имена ключей уезжают в теги
  метрик, поэтому форма закрыта. Префикс `knobs.` занят настройками библиотеки.
- **`default` обязателен** — это единственное место умолчания. В `application.yaml` и коде
  умолчание для ключа каталога не дублировать (группы его не читают; охрана — тест §11).
- **Тип** выводится из значения (`true`, `2`, `2.5`, `10s`, `2026-11-01`, текст, список).
  Пишите `type:` явно, когда значение — подстановка, пустой список или вывод неверен.
- **Структурный ключ параметром быть не может**: то, что читают `@ConditionalOnProperty`,
  `@Profile`, плейсхолдеры аннотаций и `${}` в `application.yaml`, читается один раз на старте.
  Такие ключи остаются в окружении.
- `min` / `max` / `allowed` / `pattern` — границы, которые админка не даст нарушить. Ставьте их
  равными инвариантам кода, не рекомендациям.
- `deleted: true` — пометка снятого ключа, живёт один релиз.

## 4. Прочитать значение

Три входа, один алгоритм выбора (`ParameterLookup`): действующее определение ключа — заплатка,
реплика, файл, в этом порядке; затем правило с самым точным подошедшим путём, иначе `default`.

### 4.1 Группа параметров — основной способ

Интерфейс с `@Knob("префикс")`, геттеры живые; подробно —
`doc/guide/howto-parameter-groups.adoc`. Имя — `<Фича>Knobs` в пакете фичи.

```java
@Knob("app.routing.segment")
public interface SegmentKnobs {
    Duration timeout();                             // app.routing.segment.timeout
    Optional<Boolean> callEnabled();                // значения может не быть
    @KnobKey("crm.retry-limit") int retries(); // хвост ключа, который именем метода не назвать
}

@AutoConfiguration
@EnableKnobs                              // без аргументов — пакет этого класса и вложенные
public class RoutingAutoConfiguration {}
```

- Значение читается **при каждом вызове**; результат в поле не кэшировать.
- Нет значения: `Optional` — пусто, иначе `ParameterMissingException(key)`. Окружение и
  `application.yaml` не проверяются.
- Тип не подходит — `ParameterTypeMismatchException` (ключ и типы, без значения).
- Ошибки объявления (аргументы у метода, нет аннотации, `final`-класс) — при подъёме контекста.
- Абстрактный класс вместо интерфейса — только при `byte-buddy` в рантайме сервиса.
- Без Spring: `Knobs.builder()…build().group(RoutingKnobs.class)`.
- В юнит-тестах группа — лямбда над полем теста, если метод один (`() -> maxWait`), иначе мок
  либо маленький класс-двойник.

### 4.2 `Parameters` и `Client` OpenFeature

Бин `ParameterClient` (`Parameters`): `value(key)`, `duration(key)`, `date(key)`,
`byPrefix(prefix)`; `client()` — стандартный `Client` OpenFeature в изолированном экземпляре
API (домен `business-knobs`). Атрибуты контекста OpenFeature с именами измерений из
`knobs.dimensions` — строки — сильнее пути потока. Годится для динамических ключей и для кода,
который уже говорит на OpenFeature.

### 4.3 `Environment` Spring

Слой `knobsParameters` стоит в `Environment` перед переменными окружения: для ключа каталога
параметр главнее окружения стенда. `@ConfigurationProperties` связывается **один раз** на старте
значением пути пода — это снимок, не живое чтение. `environment.getProperty(ключ, тип)` на потоке
обращения отвечает по его пути. Использовать для структурной настройки Spring и `/actuator/env`;
для бизнес-кода — группы.

## 5. Измерения и контекст обращения

Измерения и их порядок задаёт секция `dimensions` в `knobs.yaml` — генератор переносит её в
файл стенда, библиотека берёт порядок оттуда (ADR-0004). Чем дальше в списке последняя
названная часть пути правила, тем правило точнее. Правило с измерением вне списка в выборе не
участвует; аварийная правка с таким измерением отбивается. `service` в список не входит — это
условие правила. `knobs.dimensions` / `Knobs.builder().dimensions(...)` — только для файла без
секции `dimensions`; расходящийся с файлом порядок — отказ на старте.

Путь пода (`service`, `dc`) библиотека знает сама. Путь обращения — остальные измерения —
**открывает приложение** на входе: HTTP-фильтр, перехватчик брокера, начало обработки события.

```java
ContextPath path = ContextPath.builder().put("tenant", "t2").put("segment", "VIP").build();
try (ParameterContext.Scope ignored = ParameterContext.open(path)) {
    … // все чтения здесь видят правила по арендатору t2 и сегменту VIP
}
```

- Контекст живёт на потоке. Другой поток видит только путь пода, **молча**.
- Между потоками путь едет вместе с трассой через Micrometer context-propagation
  (`ParameterContextAccessor`, ключ `knobs.parameters.context`), если снимок контекста снят там,
  где путь уже открыт (`ContextSnapshotFactory#captureAll`, `ContextPropagatingTaskDecorator`).
- Пустая часть пути = «неизвестно»: правило, которое её называет, не совпадает.
- ЦОД из контекста не берётся никогда — только из пути пода.

## 6. Реплика в базе

Под поднимается с файлом, затем читает реплику — копию слепков админки, чтобы правка пережила
рестарт при недоступном топике. Реплика — **не кэш**: её потеря оставила бы под на файле.

Сервис реализует порт `ParameterReplica` (`findAll`, `find`, `saveIfNewer` — условная запись
по версии) над своей таблицей, например `parameter_setting` (`parameter_key`, `value_type`,
`is_list`, `value` jsonb, `rules` jsonb, `version`, `deleted`, `updated_at`). Загрузчик
`ParameterReplicaStartup` читает её до старта слушателей; ошибка базы на подъёме — WARN и работа
на файле (`knobs.parameters.file.only = 1`), не падение.

## 7. Топик записей

Компактный топик (в примерах — `knobs.parameters.v1`): ключ записи — ключ параметра, тело —
`ParameterRecord` (`key`, `type`, `list`, `value`, `rules`, `version` = время правки в админке,
мс, `deleted`). Пишет только админка. **Каждый под читает его собственной группой потребителя**
(например, `<сервис>-knobs-<HOSTNAME>`) и при назначении партиций встаёт в начало: топик
compacted, догоняются только записи, не дописанные в базу.

Сервис держит слушатель и отдаёт пачки в
`ParameterRecordApplier#applyAll(List<ParameterTopicRecord>)`. Исходы считает библиотека
(`knobs.parameters.records{outcome}`): `APPLIED`, `DELETED`, `IGNORED` (версия не новее),
`REPAIRED` (та же версия дописана в базу после сбоя), `REJECTED_TYPE` (тип не как в каталоге),
`REJECTED_PLACEHOLDER` (`${` в значении), `REJECTED_UNREADABLE`, `FOREIGN` (ключ вне каталога),
`SWITCHED_OFF` (рубильник). Отбракованная запись ничего не меняет; значение в лог не попадает.

Порядок применения: пачка сворачивается по ключу до самой новой версии, записывается в базу
условно (`saveIfNewer`), снимок заменяется целиком, слушателям уходит `ParametersChanged{keys}`.

## 8. Аварийная правка на поде

`/actuator/parameters` (отдельная роль эксплуатации, management-порт):

- `GET` — действующие заплатки;
- `POST` с `author`, `reason`, `patch` (YAML в формате параметра:
  `ключ: {value: 5s, rules: [{when: {dc: dc2}, value: 7s}]}`) — ставит заплатку **в память
  этого пода**; заменяет строку ключа целиком (правила админки на время правки не действуют).
  Ключ вне каталога, значение не по типу или вне границ, `${`, измерение вне
  измерений файла стенда, `*` в `when` («любое» — просто не называть измерение), правило без
  `when` (значение для всех путей пишется в `value`) — отказ с ключом и причиной.

Снимается рестартом либо записью топика с версией позже момента установки (применённой или
удалением; отбракованная запись заплатку не снимает). Метрика `knobs.parameters.patches`.
Аудит — логгер `knobs.audit`.

## 9. Рубильник

`knobs.replica-enabled=false` (ключ окружения, не параметр): под не читает реплику и не
применяет записи топика — работает на файле. Для аварии «админка раздаёт битое».

## 10. Смена значения у потребителя

Геттеру группы ничего делать не надо. Если по смене нужно действие (пересоздать пул,
перепланировать таймер) — слушать событие контекста Spring `ParametersChanged` (`keys`), без
Spring — `ParameterListener` через `Knobs.Builder#listener`. Отказ одного слушателя не
мешает остальным.

## 11. Наблюдаемость и охранные тесты

Метрики (без Spring — `Knobs.builder().observer(store -> new MicrometerParameterObserver(registry, store))`;
со Spring — сами, если есть `MeterRegistry`, а свой `ParameterObserver` сервиса их заменяет):
`knobs.parameters.pod{service,dc}`, `knobs.parameters.file.only`,
`knobs.parameters.patches`, `knobs.parameters.records{outcome}`,
`knobs.parameters.consumer.failures`. Лог: INFO на старте (файл, сервис, ЦОД, число
параметров), WARN на отбраковку с ключом и причиной, TRACE — каждый шаг выбора значения
(`io.github.olegnyr.knobs`): путь, источник, правила. **Значения параметров в лог не пишутся
никогда**, только ключи, имена переменных, коды причин.

Тесты, которые стоит держать в сервисе:

- файлы стендов собрались, версия равна версии сборки;
- ключ каталога не читается структурно (`@ConditionalOnProperty`, плейсхолдеры);
- ключ каталога ↔ метод группы через настоящий прокси библиотеки, тип в тип, ключа нет в
  `application.yaml`, тестовый стенд несёт весь каталог;
- интеграционный «запись топика меняет поведение без рестарта».

Как дать значения тестам со Spring-контекстом — у группы нет запасного значения:

- интеграционные кейсы приложения идут на тестовом стенде, например `default` — файле
  `src/test/resources/knobs/knobs-default.json` со всем каталогом;
- срез (`@WebMvcTest`, `@DataJpaTest` и т. п.) автоконфигураций не поднимает: тестовая
  конфигурация подключает библиотеку явно — `@ImportAutoConfiguration(ParameterAutoConfiguration.class)`
  + `@EnableKnobs`;
- свойство окружения в тесте (`app.x.y=…`) на группу не действует — значение меняют в файле
  стенда либо записью в снимок (`ParameterStore#replace`).

## 12. Границы

- ✅ Всегда: ключ в kebab-case с `default`; чтение через группу; контекст открыт на входе;
  измерения правил объявлены в `dimensions` `knobs.yaml`; границы каталога равны инвариантам кода;
  тесты §11.
- ⚠️ Спросить: заводить параметр без значения; менять тип существующего ключа (ключ физически не
  удаляется — только `deleted`); вставлять измерение в середину `dimensions` или менять их порядок.
- 🚫 Никогда: секреты параметрами; умолчание в `application.yaml` для ключа каталога; кэш
  значения геттера в поле; структурный ключ в каталоге; значение параметра в логе или в тексте
  исключения; читать `ParameterStore` напрямую из бизнес-кода.

## 13. Как выполнять задачу «перевести настройку на параметр»

1. Найти, кто читает ключ (`grep` по ключу и по `@ConfigurationProperties`-классу). Если его
   читает `@ConditionalOnProperty` или плейсхолдер — это структурный ключ, переводить нельзя.
2. Объявить ключ в `knobs.yaml` с `name`, `description`, `default` (= нынешнему
   умолчанию), границами из инвариантов.
3. Если нужны правила по новому измерению — добавить его **в конец** секции `dimensions`
   `knobs.yaml` (вставка в середину сдвинет смысл коротких путей) и открывать его значение в пути
   обращения на входе.
4. Добавить метод в существующую группу префикса или завести группу + `@EnableKnobs`.
5. Заменить чтение в коде на вызов геттера; снять ключ из `application.yaml` и поле из класса
   свойств.
6. Добавить ключ в тестовые файлы стендов.
7. Тесты: red-first — юнит с группой-двойником; охранные тесты §11 зелёные.
