# CLAUDE.md

Библиотека контекстных параметров `business-knobs` — Maven-модули `api`, `impl`, `spring-boot`,
плагин Maven `business-knobs-maven-plugin` (`knobs:generate`); Java 21, стек Spring Boot 4
(BOM `spring-boot-dependencies`), Jackson 3. Группа `io.github.olegnyr`, пакет `io.github.olegnyr.knobs`,
настройки `knobs.*`, метрики `knobs.parameters.*`. Готовится к выкладке в Maven Central (`RELEASING.md`).

Библиотека общего назначения: в коде, тестах и документах нет привязки к конкретной компании или
предметной области. Измерения пути и их порядок задаёт секция `dimensions` файла стенда (ADR-0004);
в тестах и примерах используются нейтральные `dc`, `tenant`, `segment`, `channel`, сервисы
`orders` / `billing`, ключи `app.*`.

## Сборка

```bash
./mvnw verify
./mvnw -P quality verify
./mvnw -P release -Dgpg.skip -DskipTests verify   # проверка артефактов для Central
./mvnw install -DskipTests && ./mvnw -f business-knobs-example/pom.xml verify   # пример
```

`business-knobs-example` — отдельный проект-потребитель, не модуль реактора: подключает библиотеку
и плагин по координатам из локального репозитория, поэтому собирается после `install` корня.

## Правила

- Стиль кода — `.claude/rules/codestyle.md`.
- Правила разработки — `.claude/rules/contribution.md`.
- Как пользоваться библиотекой — скил `.claude/skills/business-knobs/SKILL.md`.
