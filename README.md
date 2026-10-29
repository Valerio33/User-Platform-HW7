# User Platform — Spring Cloud



| Паттерн | Модуль / реализация | Что проверять |
|---|---|---|
| API Gateway | api-gateway / Spring Cloud Gateway WebFlux | Все пользовательские запросы через localhost:8080 |
| Service Discovery | discovery-server / Eureka + Spring Cloud LoadBalancer | USER-SERVICE и API-GATEWAY в реестре; маршрут lb://user-service |
| Circuit Breaker | Resilience4j, фильтр CircuitBreaker на gateway | 503 при сбоях, OPEN после серии ошибок, восстановление |
| External Configuration | config-server / Spring Cloud Config, native backend | Настройки из внешнего config-repo, импорт по HTTP |

## Состав проекта

- `pom.xml` — родительский Maven-проект.
- `config-server` — сервер конфигурации, порт 8888.
- `discovery-server` — реестр Eureka, порт 8761.
- `user-service` — REST API, JPA, PostgreSQL, Swagger, HATEOAS и static UI, порт 8082.
- `api-gateway` — единая точка входа, порт 8080.
- `config-repo` — внешние YAML-файлы.

## Подготовка и сборка

Если базы ещё нет, выполните в psql:

```sql
CREATE DATABASE user_db;
```

Сборка и быстрые тесты:

```powershell
mvn clean package
```

Полная проверка, включая PostgreSQL/Testcontainers:

```powershell
mvn clean verify
```

Перед clean остановите работающие JAR через Ctrl+C.

## Запуск: четыре терминала

Все четыре терминала открываются в одной корневой папке `user-platform`.

### 1. Config Server

```powershell
java -jar .\config-server\target\config-server-1.0-SNAPSHOT.jar
```

Дождитесь Started ConfigServerApplication. Проверка:
http://localhost:8888/actuator/health — статус UP.
http://localhost:8888/user-service/default — конфигурация user-service.

### 2. Eureka

```powershell
java -jar .\discovery-server\target\discovery-server-1.0-SNAPSHOT.jar
```

Дождитесь Started DiscoveryServerApplication. Панель: http://localhost:8761.

### 3. User Service

В новом терминале запросите пароль PostgreSQL:

```powershell
$credential = Get-Credential -UserName postgres -Message 'Пароль PostgreSQL'
$env:DB_USERNAME = $credential.UserName
$env:DB_PASSWORD = $credential.GetNetworkCredential().Password
java -jar .\user-service\target\user-service-1.0-SNAPSHOT.jar
```

Дождитесь Started UserServiceApplication. Прямой адрес сервиса: http://localhost:8082.
Данные прежней базы user_db сохраняются. Пароль не записывается в репозиторий.

### 4. Gateway

```powershell
java -jar .\api-gateway\target\api-gateway-1.0-SNAPSHOT.jar
```

В панели Eureka должны появиться USER-SERVICE и API-GATEWAY.

Пользовательские адреса:
- Веб-интерфейс: http://localhost:8080/
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- REST: http://localhost:8080/api/users

## Проверка задания вручную

### Gateway, Swagger, HATEOAS

1. Откройте веб-интерфейс на 8080: список, поиск, счётчики, создание/изменение/удаление.
2. Откройте Swagger на 8080. POST /api/users → Try it out:

```json
{"name":"Cloud Test","email":"cloud.test@example.com","age":25}
```

3. Ожидается 201. Запомните ID. В Location и _links.self.href должен быть адрес на 8080.
4. Откройте self.href: вернётся созданный пользователь. users.href ведёт к HAL-коллекции.
5. PUT по этому ID должен вернуть 200, DELETE — 204; повторный GET после удаления — 404.
6. GET списка возвращает `_embedded.users`; пустая коллекция содержит users: [].

### Service Discovery без фиксированного порта backend

1. В этом же терминале (пароль уже задан) запустите сервис на другом порту:

```powershell
$env:USER_SERVICE_PORT = '8083'
java -jar .\user-service\target\user-service-1.0-SNAPSHOT.jar
```

3. Дождитесь записи нового экземпляра в Eureka и обновления кешей.
4. Веб-интерфейс на 8080 должен снова работать без изменения конфигурации gateway.
5. Для возврата: остановите user-service, выполните Remove-Item Env:USER_SERVICE_PORT,
   запустите JAR снова. Первый успешный запрос после открытой цепи может потребовать ожидания.

### Circuit Breaker

1. Остановите только user-service через Ctrl+C. Остальные три процесса оставьте работающими.
2. Выполните в отдельном PowerShell:

```powershell
1..8 | ForEach-Object {
    try {
        Invoke-RestMethod -Uri 'http://localhost:8080/api/users'
    } catch {
        Write-Host ('HTTP ' + [int]$_.Exception.Response.StatusCode)
    }
}
```

3. Ожидается 503 с ProblemDetail. После минимум 5 вызовов и доли ошибок >=50% в окне
   из последних 10 вызовов цепь открывается (OPEN). Переход состояния и отсутствие
   вызовов backend при OPEN проверяются автоматическим GatewayTest.
4. Запустите user-service снова. Дождитесь регистрации и 10 секунд ожидания цепи.
5. Повторите GET несколько раз: два успешных пробных запроса закрывают цепь, возвращается 200.

Тайм-аут соединения 1с, ответа backend 3с, общий TimeLimiter 4с.
HTTP 500/502/503/504 и ошибки соединения учитываются как сбой; 400/404/409 — нет.
Повторы операций автоматически не выполняются. Тайм-аут не гарантирует откат операции в БД.

### External Configuration

1. Откройте http://localhost:8888/api-gateway/default: виден маршрут lb://user-service.
2. Откройте http://localhost:8082/actuator/info: configuration = external-config-repo.
3. В config-repo/application.yml поменяйте info.platform.configuration на external-config-demo.
4. Повторите запрос к Config Server: значение изменится без пересборки JAR.
5. Перезапустите user-service. В /actuator/info появится новое значение.
6. Верните исходное значение и перезапустите сервис.

Автоматический refresh/Bus не настроен: клиенты перечитывают конфигурацию при старте.
Импорт Config Server обязательный (без optional): если он недоступен, запуск клиента
завершается ошибкой, вместо незаметного перехода на случайные локальные настройки.
В работающем клиенте ранее загруженная конфигурация сохраняется при остановке Config Server.

## Настройки окружения

| Переменная | По умолчанию | Где задавать |
|---|---|---|
| DB_PASSWORD | обязательна | user-service |
| DB_USERNAME | postgres | user-service |
| DB_URL | jdbc:postgresql://localhost:5432/user_db | user-service |
| USER_SERVICE_PORT | 8082 | user-service |
| GATEWAY_PORT | 8080 | api-gateway |
| DISCOVERY_PORT | 8761 | discovery-server |
| CONFIG_PORT | 8888 | config-server |
| CONFIG_SERVER_URL | http://localhost:8888 | три клиента |
| EUREKA_URL | http://localhost:8761/eureka/ | клиенты Eureka |
| CONFIG_REPO_LOCATION | file:./config-repo/ | config-server |
| SERVICE_HOST | localhost | адрес сервиса, доступный gateway |