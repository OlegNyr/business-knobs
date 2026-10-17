## [0.1.0]

Первая публикация в Maven Central (группа `io.github.olegnyr`).

- **Модули:** `business-knobs-api`, `business-knobs-impl` (без Spring, `Knobs.builder()`),
  `business-knobs-spring-boot` (автоконфигурация Spring Boot 4), `business-knobs-maven-plugin`
  (цель `knobs:generate`).
- **Группы параметров:** `@Knob`, `@KnobKey`, `@EnableKnobs` — интерфейс с живыми геттерами,
  которые читают действующее значение при каждом вызове.
- **Измерения пути и их порядок — из секции `dimensions` файла стенда.** `knobs.dimensions` /
  `Knobs.builder().dimensions(...)` — только для файла без неё; расходящийся порядок — отказ на
  старте. Генератор: короткий путь `/dc2/*/VIP` по объявленным измерениям и именованная форма
  `when: [{segment: VIP}]`; значения `kind: code` сверяются с `values`.
- **Генератор строже:** YAML 1.2 core, дубли ключей, kebab-case ключей, тип и `kind` проверяются;
  файл, который библиотека не прочтёт, не генерируется.
- **Файлы:** исходник `src/main/knobs/knobs.yaml`, файлы стендов в jar —
  `knobs/knobs-<стенд>.json`; плагин сам добавляет их в ресурсы.
- **Группы без Spring приводят значения как Spring:** `List<Integer>`, `Set<String>`, `String` над
  числом; несовместимое — `ParameterTypeMismatchException`. Работают `default`-методы непубличных
  групп и геттеры, унаследованные от обобщённого предка.
- **Подстановки из окружения** понимают `10s`, `TRUE`, `on`/`off`, как свойства Spring.
- **Аварийная правка** отбивает `*` в `when` и правило без `when`.
- **Метрики без Spring:** `Knobs.builder().observer(store -> new MicrometerParameterObserver(registry, store))`.
- **Spring:** `knobs.dc`/`knobs.dimensions` из позднего источника (`@PropertySource`) — отказ старта
  вместо молча неверного снимка; пересекающиеся `@EnableKnobs` и свой `ParameterObserver` больше
  не роняют контекст; аудит правок — логгер `knobs.audit`.
- **Пример без Spring** — отдельный проект `business-knobs-example` (в Central не выкладывается).
