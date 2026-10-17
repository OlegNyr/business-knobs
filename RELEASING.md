# Выкладка в Maven Central

Выкладка идёт из GitHub Actions по тегу — workflow `.github/workflows/publish.yaml`, та же схема и
те же секреты, что у `adoct`. Плагин `central-publishing-maven-plugin` из профиля `release`
загружает выкладку на Central Portal (`central.sonatype.com`) и в CI публикует её сразу.
Выложенную версию из Central **удалить нельзя**.

Модули в Central: `business-knobs` (родительский pom), `business-knobs-api`, `business-knobs-impl`,
`business-knobs-spring-boot`, `business-knobs-maven-plugin`. Пример `business-knobs-example` —
отдельный проект вне реактора и не выкладывается.

## Один раз

1. **Пространство имён.** На <https://central.sonatype.com> под учётной записью GitHub `OlegNyr`
   пространство `io.github.olegnyr` уже подтверждено — через него выкладывается `adoct`.
2. **Секреты репозитория** `OlegNyr/business-knobs` (Settings → Secrets and variables → Actions) —
   те же значения, что у `adoct`:

   | Секрет | Что |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` | user token из central.sonatype.com |
   | `SIGNING_KEY` | armored GPG-приватный ключ (весь блок целиком) |
   | `SIGNING_PASSWORD` | passphrase ключа |

## Каждый релиз

Версия в `pom.xml` всегда `X.Y.Z-SNAPSHOT`; релизная версия ставится только в рабочей копии CI.

1. Дописать раздел `## [X.Y.Z]` в `CHANGELOG.md`.
2. Убедиться, что версия в pom — `X.Y.Z-SNAPSHOT`, и всё зелёное: `./mvnw -P quality verify`.
3. Поставить тег и отправить:

   ```bash
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```

4. CI проверит, что тег совпадает с версией pom без `-SNAPSHOT`, поставит версию `X.Y.Z`,
   соберёт, подпишет, выложит с автопубликацией (дождётся статуса *Published*) и создаст
   GitHub Release. Через 10–30 минут артефакты видны в поиске Central.
5. Поднять версию на следующую: `./mvnw versions:set -DnewVersion=X.Y.(Z+1)-SNAPSHOT
   -DgenerateBackupPoms=false -DprocessAllModules=true`, закоммитить.

## Вручную (без CI)

Нужны `~/.m2/settings.xml` с сервером `central` (user token) и ключ GPG в агенте:

```bash
./mvnw versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false -DprocessAllModules=true
MAVEN_GPG_PASSPHRASE=... ./mvnw -P release clean deploy
```

Без `-Dcentral.autoPublish=true` выкладка остаётся на портале в статусе *Validated* — её
проверяют и публикуют кнопкой **Publish**. Версию в рабочей копии после этого вернуть на
`-SNAPSHOT`.

## Проверка без загрузки

```bash
./mvnw -P quality,release -Dgpg.skip clean verify
```

Собирает основные jar, `-sources.jar` и `-javadoc.jar` модулей — то, что требует Central. Ту же
проверку делает workflow `build.yaml` на каждый push.
