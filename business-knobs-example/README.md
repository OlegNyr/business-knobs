# business-knobs-example

Пример сервиса без Spring: магазин `shop` читает параметры оформления заказа через
`business-knobs`. Это отдельный проект, а не модуль реактора библиотеки: он подключает
библиотеку и плагин по координатам — так же, как настоящий сервис. В Maven Central не
выкладывается.

| Файл | Что показывает |
|---|---|
| `src/main/knobs/knobs.yaml` | Исходный файл параметров: измерения `dc → tenant → segment`, правила по стендам и пути, подстановка `${PAYMENT_URL}` |
| `pom.xml` | Плагин `business-knobs-maven-plugin`: на сборке делает `knobs/knobs-dev.json` и `-prod.json` в ресурсах |
| `CheckoutKnobs` | Группа параметров — интерфейс с живыми геттерами |
| `InMemoryReplica` | Реплика в памяти на месте таблицы в базе сервиса |
| `ExampleApplication` | Сборка `Knobs.builder()` с комментариями к каждой настройке и пять шагов: значения пода → путь обращения → запись топика → аварийная правка → OpenFeature |
| `ExampleApplicationTest` | Тот же сценарий с проверкой вывода |

## Сборка и запуск

Сначала библиотека и плагин ставятся в локальный репозиторий (от корня репозитория), затем
собирается пример:

```bash
./mvnw install -DskipTests
./mvnw -f business-knobs-example/pom.xml verify
```

В IntelliJ IDEA:

1. После `install` добавьте `business-knobs-example/pom.xml` как Maven-проект
   (Maven → «+» → Add Maven Projects).
2. Запускайте готовую конфигурацию **ExampleApplication** (лежит в `.run/`, IDEA подхватывает
   её сама). Она задаёт `KNOBS_STAND=prod`, `KNOBS_DC=dc2`, `PAYMENT_URL` и перед стартом
   вызывает цель Maven `process-resources` примера.

Зачем цель Maven: файлы стендов `knobs/knobs-<стенд>.json` делает плагин на сборке Maven, а
сборка IDEA плагины Maven не запускает. Если запустить `main` «зелёной стрелкой» без неё,
пример скажет, что файла стенда нет, и подскажет команду.

Из командной строки:

```bash
./mvnw -f business-knobs-example/pom.xml package dependency:build-classpath -DskipTests \
    -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=runtime
KNOBS_STAND=prod KNOBS_DC=dc2 PAYMENT_URL=https://pay.example.com \
    java -cp "business-knobs-example/target/classes:$(cat business-knobs-example/target/cp.txt)" \
    io.github.olegnyr.knobs.example.ExampleApplication
```

Вывод:

```
== Путь пода {service=shop, dc=dc2}
max-items = 20
payment-timeout = PT15S
greeting = Добро пожаловать!
express-delivery = false
payment-url = https://pay.example.com
== Обращение {segment=VIP, tenant=acme}
max-items = 100
payment-timeout = PT15S
greeting = Добро пожаловать в магазин ACME!
express-delivery = true
payment-url = https://pay.example.com
== Запись топика: app.checkout.max-items = 50
изменились ключи: [app.checkout.max-items]
исход: [APPLIED]
max-items = 50
...
== Аварийная правка: app.checkout.payment-timeout = 30s
изменились ключи: [app.checkout.payment-timeout]
поставлены: [app.checkout.payment-timeout]
...
payment-timeout = PT30S
...
== OpenFeature
max-items = 50
```

Без `KNOBS_STAND` берётся стенд `dev`: там `payment-timeout` = 1 минута по правилу стенда, а
экспресс-доставка выключена — её правило есть только на `prod`.
