# Guia de Formação — De Spring/AWS ao Stack Quarkus/GCP/Mongo/Grafana/Camunda
### *Edição 10x: PBL, Pareto, e histórias de quem já se queimou em produção*

> **Para quem**: dev com boa vivência em Spring Boot + AWS entrando num time de régua alta.
> **Stack alvo**: Quarkus 3.x (Java 21) · GCP (Pub/Sub) · MongoDB · PostgreSQL · gRPC · Tsuru · Grafana/Prometheus/Loki/OTel · Camunda 7.
> **Meta**: em 4–5 semanas, operar com autonomia — ler código, abrir PR que passa em review, debuggar incidente às 3h da manhã.
> **Método**: **PBL** (você constrói um `Order Processing Service` ao longo do guia) + **Pareto** (cada módulo começa com "80/20: o que 20% de esforço te dá 80% do valor").
> **Como usar**: leia em ordem na semana 1. Depois, use como referência cirúrgica — Quick Reference, Troubleshooting e Appendix A são para consulta diária.

---

## 🎯 Como este guia funciona

Cada módulo segue o mesmo **loop mental**:

1. **🧠 Modelo Mental** — o que você precisa ter *na cabeça* antes de ver código.
2. **📦 80/20 Pareto** — o mínimo que resolve 80% dos casos.
3. **💻 Código de produção** — compilável, com explicação do *porquê*, não apenas do *o quê*.
4. **😱 Erros clássicos / 🔥 War stories** — o que já quebrou em produção para você não repetir.
5. **🚫 Do's and Don'ts** — opinativo, com justificativa.
6. **🏗️ PBL — Entregável** — o que você precisa ter feito antes de seguir.
7. **📚 Aprofundamento** — links oficiais para quando você precisar ir fundo.

Os módulos também têm **níveis de dificuldade** para você navegar:
- 🟢 **Fundamento** — o que você precisa na primeira semana
- 🟡 **Intermediário** — o que te deixa autônomo
- 🔴 **Avançado** — o que te deixa sênior naquele tópico

---

## Quick Reference — Cole na parede

> Esse bloco resolve 80% das consultas do teu primeiro mês. Memorize os comandos do Tsuru e o mapeamento Spring→Quarkus — o resto volta aqui.

### Quarkus CLI — comandos do dia a dia

```bash
quarkus create app com.example:order-service --java=21 --extension='rest,rest-jackson'
quarkus dev                             # Live reload + Dev Services (sobe Postgres/Mongo automaticamente)
quarkus build                           # Build JVM (fat jar)
quarkus build --native --no-tests -Dquarkus.native.container-build=true
quarkus ext add <nome>                  # Ex: quarkus-mongodb-panache
quarkus ext ls --installable            # Lista tudo que dá para adicionar
./mvnw quarkus:dev -Ddebug=5005         # Dev mode + debug remoto
```

### Tsuru — cheat sheet operacional

```bash
# Deploy & rollback
tsuru app-deploy -a order-service .
tsuru app-deploy-rollback -a order-service     # PRIMEIRO isto em incidente, depois investigue

# Debug (NESTA ORDEM)
tsuru app-log -a order-service -f              # 1º: logs em tempo real
tsuru app-info -a order-service                # 2º: status das units
tsuru app-shell -a order-service               # 3º: shell no container (jcmd, curl, etc.)

# Operação
tsuru env-set -a order-service KEY=value
tsuru env-get -a order-service
tsuru unit-add -a order-service 2              # +2 units (só depois de entender o gargalo!)
tsuru app-restart -a order-service
tsuru service-bind <service-instance> -a order-service
```

### Spring → Quarkus — mapeamento rápido

| Spring | Quarkus | Pegadinha |
|---|---|---|
| `@Autowired` / constructor | `@Inject` (CDI) | Bean **precisa** de escopo declarado |
| `@Component`/`@Service` | `@ApplicationScoped` | **Sem escopo = bean invisível** |
| `@Value("${x}")` | `@ConfigProperty(name="x")` | Falha **no startup** se ausente (não em runtime) |
| `@ConfigurationProperties` | `@ConfigMapping` (**interface!**) | Não é classe. Tem autocomplete melhor. |
| `@Transactional` (Spring) | `@Transactional` (Jakarta) | **Não funciona em `@Singleton`** (sem proxy) |
| `@RestController` | `@Path` na classe | |
| `@GetMapping` | `@GET` + `@Path` | |
| `@RequestBody` | Nada (auto-detect) | |
| `@PathVariable` / `@RequestParam` | `@PathParam` / `@QueryParam` | |
| `@ControllerAdvice` | `ExceptionMapper<T>` + `@Provider` | |
| `@FeignClient` | `@RegisterRestClient` | |
| `@SpringBootTest` | `@QuarkusTest` | |
| `@MockBean` | `@InjectMock` | |
| `ResponseEntity<T>` | `Response` (JAX-RS) | |
| `application.yml` | `application.properties` | Profiles como prefixo: `%dev.`, `%test.`, `%prod.` |
| Spring Data JPA `Repository` | `PanacheRepository<T>` OU `PanacheEntity` (Active Record) | Escolha um padrão no time |
| Spring Data Mongo | `PanacheMongoRepository<T>` | Também tem Active Record |
| `WebClient` / `RestTemplate` | `@RegisterRestClient` OU `Vertx WebClient` | Default: REST Client |
| `@Scheduled` | `@Scheduled` (SmallRye) | `every`/`cron`/`delayed` |
| `@EnableCaching` + `@Cacheable` | `@CacheResult` (quarkus-cache) | |
| `@Retryable` / Resilience4j | `@Retry` / `@CircuitBreaker` (MicroProfile FT) | |

### Decisões rápidas (quando estou em dúvida…)

```
Chamar serviço externo REST?   → @RegisterRestClient + @Retry/@CircuitBreaker/@Timeout/@Fallback
Comunicação interna síncrona?  → gRPC (contrato forte, HTTP/2, streaming)
Fire-and-forget / fanout?      → Pub/Sub (com idempotência no consumer!)
Endpoint faz JDBC ou Mongo?    → @RunOnVirtualThread (não bloqueia event loop)
Chamadas paralelas a N svcs?   → Uni.combine().all().unis(...)
Escopo de bean?                → @ApplicationScoped (90% dos casos)
Active Record vs Repository?   → O que o time já usa. Greenfield: Repository (testabilidade)
Native vs JVM (Tsuru)?         → JVM (long-running, throughput > cold start)
Native vale a pena?            → Só se cold start dominar (Cloud Run/Lambda) ou RAM for restrição
Postgres ou Mongo?             → Relacional + transações? Postgres. Documento aninhado/evoluindo? Mongo.
```

### PromQL — alertas essenciais

```promql
# Error rate > 5%
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count[5m])) > 0.05

# Latência p99 > 500ms
histogram_quantile(0.99, rate(http_server_requests_seconds_bucket[5m])) > 0.5

# Pool de conexões > 80% saturado
agroal_active_count / agroal_max_size > 0.8

# Circuit breaker aberto
ft_circuitbreaker_state_current{state="open"} > 0

# JVM Heap > 85%
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85
```

### LogQL — investigação

```logql
{service="order-service"} |= "ERROR"                            # Todos os erros
{service="order-service"} | json | traceId="abc123"             # Trace específico (correlação!)
rate({service="order-service"} |= "ERROR" [1m])                 # Taxa de erros/min
{service="order-service"} | json | level="ERROR" | line_format "{{.message}}"  # Só a mensagem
sum by (status) (rate({service="order-service"} | json | __error__="" [5m]))    # Agregação
```

---

# 📍 FASE 1 — FUNDAÇÃO: Quarkus, REST, Persistência (Semana 1)

---

## Módulo 1 — Quarkus: Modelo Mental + Spring Boot vs Spring Native vs Quarkus

> **O Problema**: você vai pensar em Quarkus como "Spring com outro nome". Vai quebrar a cara. A diferença fundamental não é a sintaxe — é **quando** as coisas acontecem.

### 🎯 80/20 do módulo

Três coisas resolvem 80% dos problemas iniciais:
1. **Todo bean precisa de escopo declarado** (90% dos casos: `@ApplicationScoped`).
2. **Configuração é avaliada no startup** — falta de config = app não sobe (no Spring, só explode quando você usa).
3. **`application.properties` usa prefixo `%profile.`** em vez de perfis em arquivos separados.

Internalizou isso? Você já consegue ler 80% do código Quarkus do time.

### 🧠 Build-time vs Runtime Processing — a diferença que muda tudo

No **Spring Boot**, tudo acontece em **runtime**: a JVM sobe, escaneia classpath, resolve anotações, cria proxies, processa `@Configuration`, gera beans. Isso leva 2–5s de startup e consome 200–400MB de RAM — porque o container carrega metadados, a infra de reflection da JVM está ativa, e tudo é dinâmico.

O **Quarkus** inverte: ele faz o máximo de trabalho **em tempo de build** (via Maven/Gradle). Escaneamento, resolução de DI, geração de proxies, compilação de Hibernate queries — tudo isso vira **bytecode estático** no jar final. Em runtime, sobe só o mínimo.

**Consequências práticas que você vai sentir:**

| Aspecto | Spring Boot | Quarkus (JVM) | Quarkus (Native) |
|---|---|---|---|
| Startup | 2–5s | 0.5–1.5s | 0.02–0.05s |
| RSS idle | 200–400MB | 100–200MB | 30–60MB |
| Build time | 10–30s | 15–40s | 3–10min ⚠️ |
| Live reload | Spring DevTools (reinicia) | `quarkus dev` (hot swap) | ❌ Use modo JVM no dev |
| Modelo DI | Spring IoC Container | ArC (CDI, build-time) | ArC (CDI, build-time) |
| Reflection | Livre | Livre | **Precisa declarar** (`@RegisterForReflection`) |
| Configuração condicional | `@ConditionalOnProperty` (runtime) | `@IfBuildProfile` (build-time — bean nem existe) | Idem |

### 🧠 Spring Boot vs Spring Native (AOT) vs Quarkus

Spring lançou **Spring Native** (baseado em GraalVM AOT) para competir com Quarkus. Os dois chegam a um binário nativo, mas por caminhos diferentes:

| Dimensão | Spring Boot | Spring Native (AOT) | Quarkus (JVM) | Quarkus Native |
|---|---|---|---|---|
| Filosofia | Runtime-centric | Runtime convertido em build-time (AOT) | Build-time-first desde a v1 | Build-time-first desde a v1 |
| Maturidade nativa | N/A | Moderada (GA no Boot 3) — alguns starters quebram | N/A | Alta (core do projeto) |
| Extensões/integrações | Enorme ecossistema | Precisa de *hints* AOT para libs externas | Ecossistema menor mas curado | Extensão já traz hints prontos |
| Dev experience nativa | N/A | `mvn spring-boot:build-image` | N/A | `quarkus build --native` + Dev Services |
| Custo cognitivo na migração | Baixo (estás em casa) | Médio (AOT tem pegadinhas) | Médio (CDI ≠ Spring IoC) | Médio (as mesmas do Quarkus JVM + reflection) |
| Quando escolher | Monólito tradicional, throughput estável, equipe grande | Legado Spring + precisa de native | Microsserviço novo, Kubernetes/Cloud Run, perf-sensitive | Serverless, CLI, RAM constrangida |

**Opinião honesta**: se você está começando *greenfield* num time que já escolheu Quarkus, é a escolha certa para a combinação "JVM + native opcional". Se estivesse começando do Spring, o caminho "Boot → Boot+Native" tem mais atrito porque você paga duas vezes: aprende o modelo runtime do Spring e depois aprende as peculiaridades do AOT.

### 💻 Projeto mínimo (copia, cola, roda)

```bash
quarkus create app com.example:order-service \
  --extension='rest-jackson,rest-client-reactive,hibernate-orm-panache,jdbc-postgresql,mongodb-panache,smallrye-health,smallrye-fault-tolerance,flyway,opentelemetry,micrometer-registry-prometheus,io.quarkiverse.loggingjson:quarkus-logging-json,oidc,cache,scheduler,io.quarkiverse.googlecloudservices:quarkus-google-cloud-pubsub' \
  --java=21

cd order-service
quarkus dev    # Dev Services sobe Postgres + Mongo + Pub/Sub emulator. Zero config local.
```

### 💻 CDI: o container do Quarkus (ArC)

```java
@ApplicationScoped  // ← SEM ISSO, O BEAN NÃO EXISTE. PERÍODO.
public class OrderService {

    private final OrderRepository repository;
    private final OrderConfig config;

    // Constructor injection — padrão preferido (mais testável)
    @Inject
    public OrderService(OrderRepository repository, OrderConfig config) {
        this.repository = repository;
        this.config = config;
    }
}
```

> 😱 **Erro clássico #1 do ex-Springista**: cria a classe, injeta, roda → `UnsatisfiedResolutionException: No bean found for type X`. Causa: esqueceu `@ApplicationScoped`. No Spring, `@Component` é implícito via `@ComponentScan`. No Quarkus, você declara ou o bean é invisível para o ArC.

### 💻 Escopos CDI — a tabela que importa

| Escopo | Proxied? | Interceptors funcionam? | Quando usar |
|---|---|---|---|
| `@ApplicationScoped` | ✅ | ✅ (`@Transactional`, `@Retry`, `@WithSpan`) | **90% dos casos** — services, repositories, clients |
| `@Singleton` | ❌ | ❌ | Raro. Só se você precisa zero overhead e **não usa** interceptors |
| `@RequestScoped` | ✅ | ✅ | Contexto por-request: MDC, tenant, user context |
| `@Dependent` | ❌ | ❌ | Producers (`@Produces`), objetos com ciclo curto |

> 🔥 **War story real**: dev vindo do Spring marca `OrderService` como `@Singleton` por hábito. Adiciona `@Transactional` em `create()`. Em produção, às vezes o pedido é persistido, às vezes não, *sem erro nos logs*. Causa: `@Singleton` **não é proxied** — o interceptor `@Transactional` nunca é invocado. `@ApplicationScoped` resolveu em 5 minutos, depois de 8 horas de investigação.

### 💻 Configuração: `@ConfigMapping` (sempre) e profiles

```properties
# application.properties — um arquivo, profiles como prefixo
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/orders

%dev.quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/orders_dev
%test.quarkus.datasource.devservices.enabled=true
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://${DB_HOST}:5432/orders

app.order.max-items=50
app.order.default-currency=BRL
app.order.retry.max-attempts=3
```

```java
@ConfigMapping(prefix = "app.order")
public interface OrderConfig {

    @WithName("max-items")
    int maxItems();

    @WithDefault("BRL")
    String defaultCurrency();

    RetryConfig retry();

    interface RetryConfig {
        @WithName("max-attempts")
        @WithDefault("3")
        int maxAttempts();
    }
}
```

**Por que `@ConfigMapping` e não `@ConfigProperty` para tudo**:
- É **interface** → Quarkus gera implementação no build. Zero reflection em runtime.
- **Valida no startup** — config faltando = app não sobe (e você descobre no deploy, não às 3h da manhã).
- Agrupa logicamente. 15 `@ConfigProperty` no meio de uma classe = caos.
- IDE dá autocomplete nativo.

### 💻 Lifecycle + Graceful Shutdown (o passo que salva pedidos)

```java
@ApplicationScoped
public class AppLifecycle {
    private static final Logger LOG = Logger.getLogger(AppLifecycle.class);

    void onStart(@Observes StartupEvent event) {
        LOG.info("Order Service starting up");
    }

    void onStop(@Observes ShutdownEvent event) {
        LOG.info("Order Service shutting down — flushing in-flight work");
    }
}
```

```properties
quarkus.shutdown.timeout=30s
# Quando o Tsuru/K8s manda SIGTERM:
# 1. Quarkus para de aceitar NOVOS requests
# 2. Espera até 30s para os em andamento terminarem (ack de Pub/Sub, responses HTTP, commits)
# 3. Executa @Observes ShutdownEvent
# 4. Processo encerra
# Sem isso: requests em andamento morrem com connection reset = erro pro cliente em TODO deploy
```

### 🚫 Do's and Don'ts

✅ **DO**: declarar escopo em todo bean (`@ApplicationScoped` é o padrão seguro).
❌ **DON'T**: usar `@Singleton` quando há interceptors (`@Transactional`, `@Retry`, `@CacheResult`).
→ **POR QUÊ**: `@Singleton` não é proxied. Interceptors dependem de proxy. Falha silenciosa.

✅ **DO**: usar `@ConfigMapping` para config estruturada.
❌ **DON'T**: espalhar `@ConfigProperty` em 10 lugares.
→ **POR QUÊ**: `@ConfigMapping` valida no startup, agrupa, tem autocomplete. `@ConfigProperty` avulso é deprecable em refatoração.

✅ **DO**: configurar `quarkus.shutdown.timeout=30s` desde o primeiro deploy.
❌ **DON'T**: fazer deploy sem graceful shutdown.
→ **POR QUÊ**: todo deploy = SIGTERM. Sem graceful, requests em voo morrem = erros 502 intermitentes visíveis ao cliente.

✅ **DO**: usar **Dev Services** (testcontainers automáticos).
❌ **DON'T**: instalar Postgres/Mongo local para dev.
→ **POR QUÊ**: `quarkus dev` já sobe tudo via Testcontainers. Config é zero. Onboard do próximo dev é instantâneo.

### 🎚️ Níveis
- 🟢 Criar app, rodar `quarkus dev`, injetar beans, configurar properties
- 🟡 `@ConfigMapping` com validação e grupos aninhados, custom qualifiers, interceptors customizados
- 🔴 Build-time processing, extensões custom, entender ArC e Quarkus recording

### 🏗️ PBL Fase 1.1 — Setup

1. Crie o projeto com as extensões acima.
2. Declare `OrderConfig` com `@ConfigMapping`.
3. Crie `AppLifecycle` com logs de start/stop.
4. Configure `quarkus.shutdown.timeout=30s`.
5. Rode `quarkus dev` — confirme que Dev Services sobe Postgres e `/q/health` retorna `UP`.
6. **Teste de compreensão**: remova o `@ApplicationScoped` de `OrderService`. Leia a stack trace. Entenda.

### 📚 Aprofundamento
- Quarkus CDI: https://quarkus.io/guides/cdi-reference
- `@ConfigMapping`: https://quarkus.io/guides/config-mappings
- Lifecycle: https://quarkus.io/guides/lifecycle

---

## Módulo 2 — REST + REST Client + Exception Handling

> **O Problema**: o serviço precisa receber pedidos (server) E chamar o inventário (client). Errou num dos lados e o contrato se desfaz.

### 🎯 80/20

1. Todo endpoint com JDBC/Mongo → `@RunOnVirtualThread`. Sem exceção.
2. DTOs são `record`. Sempre. Nunca retorne entidade JPA.
3. `ExceptionMapper` é seu `@ControllerAdvice`. Um por exceção de negócio + um catch-all.

### 💻 REST Server com JAX-RS

```java
@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderResource {

    @Inject OrderService orderService;

    @GET
    @RunOnVirtualThread  // JDBC bloqueia — não rode na event loop do Vert.x
    public List<OrderResponse> list(
            @QueryParam("status") @DefaultValue("ALL") String status,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return orderService.findByStatus(status, page, size);
    }

    @GET
    @Path("/{id}")
    @RunOnVirtualThread
    public OrderResponse get(@PathParam("id") Long id) {
        return orderService.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @POST
    @RunOnVirtualThread
    public Response create(@Valid CreateOrderRequest request) {
        OrderResponse created = orderService.create(request);
        return Response.created(URI.create("/orders/" + created.id()))
                .entity(created)
                .build();
    }
}
```

> 💡 **Insight**: `@RunOnVirtualThread` (Java 21+) te dá o melhor dos dois mundos: código síncrono legível + sem bloquear event loop. A alternativa é `@Blocking` (worker pool), que funciona mas tem pool fixo. Virtual threads escalam para milhares.

### 💻 DTOs com `record`

```java
public record CreateOrderRequest(
        @NotBlank(message = "Customer ID is required") String customerId,
        @NotEmpty(message = "At least one item required")
        @Size(max = 50, message = "Max 50 items")
        List<OrderItemRequest> items
) {}

public record OrderItemRequest(
        @NotBlank String productId,
        @Positive int quantity,
        @Positive BigDecimal unitPrice
) {}

public record OrderResponse(
        Long id, String customerId, String status,
        BigDecimal totalAmount, LocalDateTime createdAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse from(Order entity) {
        return new OrderResponse(
                entity.id, entity.customerId, entity.status.name(),
                entity.totalAmount, entity.createdAt,
                entity.items.stream().map(OrderItemResponse::from).toList()
        );
    }
}

public record ErrorResponse(String code, String message, int status) {}
```

> 🔥 **War story**: endpoint retornava `List<Order>` diretamente (entidade JPA). Tudo funcionou em dev e staging. Em produção, primeiro request em horário de pico: `LazyInitializationException` — porque a sessão Hibernate fechou antes do Jackson serializar `order.items`. Moral: **nunca** retorne entidade. DTO sempre.

### 💻 Exception Handling

```java
@Provider
public class OrderNotFoundExceptionMapper implements ExceptionMapper<OrderNotFoundException> {
    @Override
    public Response toResponse(OrderNotFoundException e) {
        return Response.status(404)
                .entity(new ErrorResponse("ORDER_NOT_FOUND",
                        "Order " + e.getOrderId() + " not found", 404))
                .build();
    }
}

@Provider
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {
    @Override
    public Response toResponse(ConstraintViolationException e) {
        List<String> violations = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .toList();
        return Response.status(400)
                .entity(new ErrorResponse("VALIDATION_FAILED",
                        String.join(", ", violations), 400))
                .build();
    }
}

// Catch-all — NUNCA deixe stack trace escapar ao cliente
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {
    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Override
    public Response toResponse(Exception e) {
        String errorId = UUID.randomUUID().toString();
        LOG.errorf(e, "Unhandled exception [errorId=%s]", errorId);
        return Response.status(500)
                .entity(new ErrorResponse("INTERNAL_ERROR",
                        "Internal error — please quote id " + errorId, 500))
                .build();
    }
}
```

> 💡 **Padrão "correlation id no erro"**: o cliente vê `"Internal error — quote id abc-123"`, você consulta o log pelo id, encontra a stack trace exata. Sem vazar detalhes internos.

### 💻 REST Client — chamando o serviço de inventário

```java
@RegisterRestClient(configKey = "inventory-api")
@Path("/api/v1/inventory")
public interface InventoryRestClient {

    @GET @Path("/{productId}/stock")
    StockResponse checkStock(@PathParam("productId") String productId,
                              @QueryParam("quantity") int quantity);

    @POST @Path("/{productId}/reserve")
    ReservationResponse reserveStock(@PathParam("productId") String productId,
                                      ReserveRequest request);
}

public record StockResponse(boolean available, int currentStock, String status) {}
public record ReserveRequest(int quantity, String orderId) {}
public record ReservationResponse(String reservationId, boolean confirmed) {}
```

```properties
quarkus.rest-client.inventory-api.url=http://inventory-service:8080
quarkus.rest-client.inventory-api.scope=jakarta.inject.Singleton
quarkus.rest-client.inventory-api.connect-timeout=2000
quarkus.rest-client.inventory-api.read-timeout=3000

%dev.quarkus.rest-client.inventory-api.url=http://localhost:8081
```

```java
@ApplicationScoped
public class InventoryClient {

    @Inject
    @RestClient
    InventoryRestClient inventoryApi;

    public boolean checkStock(String productId, int quantity) {
        StockResponse response = inventoryApi.checkStock(productId, quantity);
        return response.available();
    }
}
```

**Do Feign ao REST Client**:

| Spring (Feign) | Quarkus (MicroProfile REST Client) |
|---|---|
| `@FeignClient(name="inventory", url="${...}")` | `@RegisterRestClient(configKey="inventory-api")` |
| `@EnableFeignClients` | Automático |
| Interface + `@GetMapping` etc. | Interface + anotações JAX-RS |
| `feign.client.config.inventory.*` | `quarkus.rest-client.inventory-api.*` |

### 🚫 Do's and Don'ts

✅ **DO**: `@RunOnVirtualThread` em todo endpoint bloqueante.
❌ **DON'T**: rodar JDBC/Mongo síncrono sem `@RunOnVirtualThread` ou `@Blocking`.
→ **POR QUÊ**: event loop do Vert.x tem **poucas threads** (≈ CPUs × 2). Bloquear uma = latência generalizada + BlockedThreadChecker reclamando. Em carga = pod morre.

✅ **DO**: DTOs são `record`. Campos com Bean Validation (`@NotBlank`, `@Positive`, `@Size`).
❌ **DON'T**: retornar entidade JPA/Mongo no endpoint.
→ **POR QUÊ**: acoplamento banco↔API, problemas de lazy loading, vaza campos internos.

✅ **DO**: um `ExceptionMapper` por exceção de negócio + um catch-all com correlation id.
❌ **DON'T**: lançar `RuntimeException` crua no endpoint.
→ **POR QUÊ**: sem mapper, cliente recebe 500 com stack trace. É security incident + DX terrível.

### 🎚️ Níveis
- 🟢 CRUD REST com validação + exception mappers + REST Client simples
- 🟡 Paginação consistente (headers `X-Total-Count`), versionamento de API (`/v1/orders`), client com auth propagado
- 🔴 HATEOAS, streaming de arquivos grandes, REST híbrido (Uni/Multi) quando compensar

### 🏗️ PBL Fase 1.2

1. `OrderResource` com `GET /orders`, `GET /orders/{id}`, `POST /orders`, todos com `@RunOnVirtualThread`.
2. DTOs `record` com Bean Validation.
3. `ExceptionMapper` para `OrderNotFoundException`, `ConstraintViolationException`, catch-all.
4. `InventoryRestClient` interface + `InventoryClient` wrapper.
5. Teste com `curl`: 201 (válido), 400 (validação falha), 404 (not found). **Nenhum stack trace no response body.**

### 📚 Aprofundamento
- Quarkus REST: https://quarkus.io/guides/rest
- REST Client: https://quarkus.io/guides/rest-client
- Bean Validation: https://quarkus.io/guides/validation

---

## Módulo 3 — Persistência: PostgreSQL (Panache) **e** MongoDB (Panache)

> **O Problema**: o time usa Postgres para o domínio transacional (pedidos, pagamentos) e MongoDB para dados de catálogo, perfil e eventos (estrutura flexível, aninhada). Se você só conhece JPA, metade do banco vai te pegar.

### 🎯 80/20

1. **Postgres**: Panache Repository + Flyway + `@RunOnVirtualThread` nos endpoints + pool sizing explícito.
2. **Mongo**: Panache Mongo + desenhe o documento em volta da **consulta mais frequente**, não do formulário. Crie índice para toda query recorrente. Use `@BsonProperty` se nome Java ≠ nome no Mongo.
3. **N+1 e queries lentas** matam tanto Postgres quanto Mongo — use `fetch join` no Postgres e `aggregation pipeline` no Mongo (não loop no código).

### 🧠 Quando Postgres, quando Mongo?

| Você tem… | Postgres | Mongo |
|---|---|---|
| Transações ACID multi-tabela | ✅ | ⚠️ tem transações, mas é mais caro |
| Relações fortes e joins | ✅ | ❌ evite joins (`$lookup` é caro) |
| Esquema que muda pouco | ✅ | OK |
| Dados aninhados (documento com subdocumentos) | ⚠️ JSONB | ✅ nativo |
| Schema flexível, evolução frequente | ⚠️ | ✅ |
| Queries agregadas complexas | ✅ SQL | ✅ aggregation pipeline |
| Full-text search | ✅ tsvector | ✅ text index |
| Busca geoespacial | ✅ PostGIS | ✅ 2dsphere |
| Escala de escrita massiva | Vertical | Horizontal (sharding) |

**Regra da casa**: domínio transacional com relações → Postgres. Dados que são "o documento é a unidade de consulta" (catálogo, perfil, evento, configuração por tenant) → Mongo.

### 💻 PostgreSQL — configuração + pool sizing

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=quarkus
quarkus.datasource.password=quarkus
#quarkus.datasource.jdbc.url=jdbc:postgresql://${DB_HOST:localhost}:5432/orders

# Connection pool (Agroal)
quarkus.datasource.jdbc.min-size=5
quarkus.datasource.jdbc.max-size=20
quarkus.datasource.jdbc.idle-removal-interval=PT5M
quarkus.datasource.jdbc.max-lifetime=PT30M
quarkus.datasource.jdbc.acquisition-timeout=PT5S

quarkus.hibernate-orm.database.generation=none   # SEMPRE none — Flyway cuida
%dev.quarkus.hibernate-orm.log.sql=true          # Dev: veja queries
%test.quarkus.hibernate-orm.log.sql=true
```

> 🔥 **War story do pool**: time herdou pool de 20 conexões com default do Spring. Em promoção, pico de 200 requests concorrentes, cada query demorando 500ms. Pool esgotado em 200ms. `AgroalConnectionPoolExhaustedException` em cascata. Fix de produção: `max-size=80`. Fix real: otimizar as queries lentas. **Regra**: `max_pool ≥ (requests_concorrentes × avg_query_time_s)`.

### 💻 Entidade + Repository (Postgres)

```java
@Entity
@Table(name = "orders")
public class Order extends PanacheEntityBase {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "customer_id", nullable = false)
    public String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public OrderStatus status;

    @Column(name = "total_amount", precision = 12, scale = 2)
    public BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<OrderItem> items = new ArrayList<>();
}

public enum OrderStatus {
    PENDING, CONFIRMED, PROCESSING, SHIPPED, DELIVERED, CANCELLED, FAILED
}
```

```java
@ApplicationScoped
public class OrderRepository implements PanacheRepository<Order> {

    public Optional<Order> findByIdWithItems(Long id) {
        return find("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = ?1", id)
                .firstResultOptional();
    }

    public List<Order> findByStatusPaged(OrderStatus status, int page, int size) {
        return find("status", status)
                .page(page, size)
                .list();
    }
}
```

> 💡 **Active Record vs Repository**: Panache oferece os dois. Active Record (`Order.find(...)`) é menos código, mas acopla entidade a operações de persistência (teste fica mais chato, DI some). Para time novo e greenfield, **Repository** é a escolha mais segura. Se o time já usa Active Record, siga o padrão. Consistência > preferência pessoal.

### 💻 Flyway migrations

```sql
-- V001__create_orders.sql
CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(255) NOT NULL,
    status          VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    total_amount    NUMERIC(12,2),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);

-- V002__create_order_items.sql
CREATE TABLE order_items (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id  VARCHAR(255) NOT NULL,
    quantity    INT NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(12,2) NOT NULL
);
CREATE INDEX idx_items_order ON order_items(order_id);

-- V003__create_processed_messages.sql  (para idempotência de Pub/Sub)
CREATE TABLE processed_messages (
    id           BIGSERIAL PRIMARY KEY,
    message_id   VARCHAR(255) NOT NULL UNIQUE,
    processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- V004__create_outbox_events.sql  (transactional outbox)
CREATE TABLE outbox_events (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_id  VARCHAR(255) NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       TEXT NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    published     BOOLEAN NOT NULL DEFAULT FALSE,
    published_at  TIMESTAMP
);
CREATE INDEX idx_outbox_pending ON outbox_events(published) WHERE published = FALSE;
```

**Regra inflexível do Flyway**: nomenclatura `V<versão>__<descrição>.sql` com **dois underscores**. **Nunca altere uma migration já aplicada** — Flyway valida checksum. Crie `V00X__fix_algo.sql` para corrigir.

### 💻 N+1: detectar e corrigir

```java
// ❌ N+1 silencioso: 1 query para orders + N queries para items
var orders = orderRepository.listAll();
return orders.stream().map(OrderResponse::from).toList(); // from() toca order.items

// ✅ Fetch join: 1 query
var orders = orderRepository.find(
    "SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items").list();
```

**Detectar**: `quarkus.hibernate-orm.log.sql=true` no dev. Vê o mesmo SELECT se repetindo 50 vezes? É N+1.

**Em produção, diagnosticar query suspeita**:
```sql
EXPLAIN ANALYZE SELECT o.* FROM orders o
  LEFT JOIN order_items i ON i.order_id = o.id
  WHERE o.status = 'PENDING';
-- Procure: Seq Scan em tabela grande = falta índice
-- Procure: Nested Loop + muitas linhas = JOIN ruim
-- Procure: rows estimados ≠ rows reais = estatísticas desatualizadas (ANALYZE)
```

### 💻 Transações (Postgres)

```java
@ApplicationScoped
public class OrderService {

    @Inject OrderRepository orderRepository;

    @Transactional  // jakarta.transaction.Transactional — funciona com @ApplicationScoped
    public OrderResponse create(CreateOrderRequest request) {
        Order order = new Order();
        order.customerId = request.customerId();
        order.status = OrderStatus.PENDING;
        order.totalAmount = calculateTotal(request.items());
        order.createdAt = LocalDateTime.now();
        order.updatedAt = LocalDateTime.now();
        order.items = request.items().stream()
                .map(item -> toOrderItem(item, order))
                .toList();

        orderRepository.persist(order);
        return OrderResponse.from(order);
    }
}
```

### 💻 MongoDB com Panache — o que o time usa para catálogo e eventos

```properties
# Setup do Mongo
quarkus.mongodb.connection-string=mongodb://${MONGO_HOST:localhost}:27017
quarkus.mongodb.database=catalog

%dev.quarkus.mongodb.devservices.enabled=true   # Sobe Mongo via Testcontainers automaticamente
```

```java
@MongoEntity(collection = "products")
public class Product extends PanacheMongoEntity {
    // `id` (ObjectId) herdado de PanacheMongoEntity

    @BsonProperty("sku")
    public String sku;

    public String name;
    public String description;

    @BsonProperty("price")
    public BigDecimal price;

    @BsonProperty("category")
    public String category;

    @BsonProperty("attributes")
    public Map<String, Object> attributes;  // flexibilidade: cor, tamanho, voltagem, etc.

    @BsonProperty("tags")
    public List<String> tags;

    @BsonProperty("created_at")
    public Instant createdAt;
}
```

```java
@ApplicationScoped
public class ProductRepository implements PanacheMongoRepository<Product> {

    public Optional<Product> findBySku(String sku) {
        return find("sku", sku).firstResultOptional();
    }

    public List<Product> findByCategory(String category, int page, int size) {
        return find("category", category)
                .page(page, size)
                .list();
    }

    /**
     * Aggregation pipeline: top N categorias por número de produtos.
     * Equivalente a: SELECT category, COUNT(*) FROM products GROUP BY category
     */
    public List<Document> topCategoriesByCount(int limit) {
        return mongoCollection().aggregate(List.of(
                Aggregates.group("$category", Accumulators.sum("count", 1)),
                Aggregates.sort(Sorts.descending("count")),
                Aggregates.limit(limit)
        )).into(new ArrayList<>());
    }
}
```

### 🧠 Modelagem Mongo: a regra que muda tudo

No Postgres, você normaliza: tabela `orders`, tabela `order_items`, FK. No Mongo, você **modela em volta da consulta**.

```json
// ✅ Bom para "dado um produto, mostre tudo sobre ele": embedding
{
  "sku": "SKU-123",
  "name": "Tênis X",
  "price": 299.90,
  "attributes": { "color": "black", "size": "42" },
  "variants": [
    { "sku": "SKU-123-BLK-42", "stock": 5 },
    { "sku": "SKU-123-BLK-43", "stock": 2 }
  ]
}

// ❌ Ruim: normalizar como se fosse SQL
// products, variants em coleções separadas, referencing
// → toda busca de produto vira 2 queries + $lookup caro
```

**Duas regras:**
1. **Embed quando o "filho" só existe no contexto do pai** (variantes de produto, itens de pedido imutáveis).
2. **Reference quando o "filho" tem vida própria** (cliente com múltiplos pedidos, avaliação reutilizada em múltiplos produtos).

### 💻 Índices em Mongo — fazem a diferença

```javascript
// Criar via shell / script de migration de índice
db.products.createIndex({ sku: 1 }, { unique: true })
db.products.createIndex({ category: 1, price: -1 })  // composto: filter + sort
db.products.createIndex({ "attributes.color": 1 })   // nested field
db.products.createIndex({ name: "text", description: "text" })  // full-text
```

**Regra**: toda query que aparece no seu log **mais de 1× por minuto** precisa de índice. `explain("executionStats")` mostra `COLLSCAN` = scan full da collection = dor.

```java
// No código — diagnóstico de query lenta
Document explain = mongoCollection()
        .find(Filters.eq("category", "shoes"))
        .explain();
// Procure: "queryPlanner.winningPlan.stage" — se for "COLLSCAN", crie índice
```

### 💻 Transações em Mongo (quando preciso)

```java
@Inject MongoClient mongoClient;

public void transferStock(String fromSku, String toSku, int qty) {
    try (ClientSession session = mongoClient.startSession()) {
        session.startTransaction();
        try {
            mongoCollection().updateOne(session,
                Filters.eq("sku", fromSku),
                Updates.inc("stock", -qty));
            mongoCollection().updateOne(session,
                Filters.eq("sku", toSku),
                Updates.inc("stock", qty));
            session.commitTransaction();
        } catch (Exception e) {
            session.abortTransaction();
            throw e;
        }
    }
}
```

> ⚠️ Transações no Mongo **requerem replica set** (não standalone). Em dev com Dev Services, o Mongo já sobe como replica set de um nó. Em prod, todo cluster é replica set.

### 🚫 Do's and Don'ts

✅ **DO**: pool sizing explícito no Postgres.
❌ **DON'T**: confiar no default de 20.
→ **POR QUÊ**: query lenta + concorrência = pool esgotado. 502 em cascata.

✅ **DO**: `hibernate-orm.database.generation=none` + Flyway.
❌ **DON'T**: `update` ou `create-drop` fora de protótipo.
→ **POR QUÊ**: Hibernate infere schema errado em casos não-triviais. Flyway é verdade versionada.

✅ **DO**: fetch join ou `@EntityGraph` para resolver N+1.
❌ **DON'T**: `FetchType.EAGER` como "solução" para lazy exceptions.
→ **POR QUÊ**: EAGER carrega **sempre**. Entidade com 5 relações EAGER = 5 JOINs em toda query.

✅ **DO**: no Mongo, modele em volta da consulta. Índice para toda query recorrente.
❌ **DON'T**: normalizar Mongo como se fosse SQL.
→ **POR QUÊ**: `$lookup` é caro e estraga a razão de você ter ido pro Mongo.

### 🎚️ Níveis
- 🟢 CRUD Postgres + Flyway + CRUD Mongo + índices básicos
- 🟡 Queries customizadas, projections, aggregation pipelines, transaction management, pool tuning
- 🔴 Multi-tenancy, bulk operations, change streams no Mongo, audit log, partitioning no Postgres

### 🏗️ PBL Fase 1.3 — Persistência

**Postgres**:
1. Entidades `Order`, `OrderItem`, enum `OrderStatus`.
2. `OrderRepository` com `PanacheRepository`.
3. Migrations V001–V004.
4. `OrderService` com `@Transactional`.
5. Testes `@QuarkusTest` + RestAssured: 201, 400, 404, paginação.
6. **Prove ausência de N+1**: crie 5 orders com 3 items, liste, conte queries no log — máximo 2.

**Mongo**:
7. Entidade `Product` (+ `ProductRepository`).
8. Endpoint `GET /products?category=X&page=0&size=20` com paginação.
9. Crie índice composto em `category + price` via script.
10. Aggregation: `GET /products/categories/top?limit=5` retornando top 5 categorias.

### 📚 Aprofundamento
- Hibernate Panache: https://quarkus.io/guides/hibernate-orm-panache
- Mongo Panache: https://quarkus.io/guides/mongodb-panache
- Flyway: https://quarkus.io/guides/flyway
- Aggregation framework: https://www.mongodb.com/docs/manual/aggregation/

---


# 📍 FASE 2 — COMUNICAÇÃO: Pub/Sub, gRPC, Programação Reativa (Semana 2)

---

## Módulo 4 — GCP Pub/Sub: da autenticação ao Transactional Outbox

> **O Problema**: Pedido criado → precisa notificar inventário, notificação, fulfillment. Chamada HTTP síncrona para cada um = latência somada + frágil (cai um, o pedido não é criado). Mensageria desacopla. Mas mensageria mal feita cria um novo problema: duplicatas, mensagens perdidas, DLQ esquecida.

### 🎯 80/20

1. **At-least-once é o padrão real**, não at-most-once. **Toda consumer** precisa de idempotência.
2. **Autentique com Application Default Credentials (ADC)** no GKE/Cloud Run e service account key só em dev.
3. **Transactional Outbox** resolve "publiquei e o banco deu rollback" + "commit no banco e o publish caiu".
4. **DLQ sem alerta** = mensagens apodrecendo em silêncio. Configure alerta no primeiro dia.

### 🧠 O vocabulário que você precisa

```
Publisher ──publish──> [Topic] ──fanout──> [Subscription A] ──pull──> Consumer A
                          │                [Subscription B] ──push──> HTTP endpoint
                          └──> [Dead-letter topic] (se > N tentativas)
```

- **Topic**: canal nomeado para publicar. Zero estado. Quem decide estado é a subscription.
- **Subscription**: fila com estado. Cada sub recebe **todas** as msgs do topic (fanout). `pull` (app consome ativamente) ou `push` (Pub/Sub faz POST no seu endpoint HTTPS).
- **Ack deadline**: tempo que o consumer tem para confirmar. Se estourar, msg é reenviada.
- **Dead-letter topic**: após N tentativas, msg vai para outro topic (que você monitora).

### 🧠 AWS → GCP (tabela que tira a dúvida de 80%)

| AWS | GCP Pub/Sub | Pegadinha |
|---|---|---|
| SQS (fila) | Subscription em modo pull | Sem FIFO nativo. Use ordering keys para ordem |
| SNS (topic) | Topic | No GCP, topic + sub é sempre 1-para-N (não precisa "assinar") |
| SQS DLQ (redrive) | Dead-letter topic + sub separados | Você cria **os dois** — DLQ é um topic novo, não só config |
| SQS FIFO (MessageGroupId) | Ordering key | Limita throughput por key — use só quando precisa |
| Fan-out (SNS → N×SQS) | Topic → N×Subscriptions | Muito mais simples no GCP |
| Push HTTP (SNS) | Push subscription | Precisa de endpoint HTTPS público validado |
| SQS DelaySeconds | ❌ sem delay nativo | Workaround: Cloud Scheduler ou fallback para msg com `publishTime` futura |
| SQS Visibility Timeout | Ack deadline (10–600s) | Se não faz ack → reenvia |
| Purge queue | Seek to timestamp | Pula mensagens até T (não deleta — move o cursor) |
| Batch receive (10 max) | Pull até 1000/request | GCP mais agressivo em batch |
| Exactly-once | At-least-once (padrão) ou exactly-once opt-in | ⚠️ "Exactly-once" do GCP é dentro da subscription em janela de tempo — ainda assim, implemente idempotência |

### 💻 Autenticação — ADC é o caminho

```bash
# Em dev local
gcloud auth application-default login
# Cria ~/.config/gcloud/application_default_credentials.json
# Todo SDK do GCP procura nesse caminho automaticamente

# Em CI (GitHub Actions, etc.)
# Use Workload Identity Federation — zero chave
# Ou, fallback: GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa-key.json

# Em produção no GKE/Cloud Run
# Use Workload Identity — o pod herda a identidade da service account do node
# NUNCA versione chave de SA no repo
```

### 💻 Emulador local (zero custo, zero auth)

```bash
# Rode o emulador (uma vez)
gcloud components install pubsub-emulator
gcloud beta emulators pubsub start --host-port=localhost:8085

# Em outro terminal, exporte:
export $(gcloud beta emulators pubsub env-init)
# Isso seta PUBSUB_EMULATOR_HOST=localhost:8085
# Os SDKs detectam e pulam auth
```

Ou via `quarkus dev` — a extensão `quarkus-google-cloud-pubsub` sobe o emulador como Dev Service automaticamente.

```properties
%dev.quarkus.google.cloud.pubsub.devservice.enabled=true
```

### 💻 Publisher

```java
@ApplicationScoped
public class OrderEventPublisher {

    private static final Logger LOG = Logger.getLogger(OrderEventPublisher.class);

    @Inject ObjectMapper objectMapper;

    @ConfigProperty(name = "gcp.project-id")
    String projectId;

    private Publisher publisher;

    @PostConstruct
    void init() throws IOException {
        publisher = Publisher.newBuilder(TopicName.of(projectId, "order-created"))
                // Batch: acumula msgs para reduzir round-trips
                .setBatchingSettings(BatchingSettings.newBuilder()
                        .setElementCountThreshold(100L)
                        .setRequestByteThreshold(1024L * 1024L)
                        .setDelayThreshold(Duration.ofMillis(100))
                        .build())
                .build();
    }

    public void publish(OrderCreatedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            PubsubMessage message = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFromUtf8(payload))
                    .putAttributes("eventType", "ORDER_CREATED")
                    .putAttributes("orderId", String.valueOf(event.orderId()))
                    .putAttributes("traceId", MDC.get("traceId"))  // propaga trace!
                    .build();

            ApiFuture<String> future = publisher.publish(message);
            // Async callback — não bloqueie a thread
            ApiFutures.addCallback(future, new ApiFutureCallback<>() {
                @Override public void onSuccess(String messageId) {
                    LOG.infof("Published order-created [orderId=%d, msgId=%s]",
                              event.orderId(), messageId);
                }
                @Override public void onFailure(Throwable t) {
                    LOG.errorf(t, "Failed to publish event for order %d", event.orderId());
                }
            }, MoreExecutors.directExecutor());

        } catch (Exception e) {
            throw new EventPublishException("Failed to publish", e);
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        if (publisher != null) {
            publisher.shutdown();
            publisher.awaitTermination(30, TimeUnit.SECONDS);
        }
    }
}

public record OrderCreatedEvent(
    long orderId, String customerId, BigDecimal totalAmount, LocalDateTime createdAt
) {
    public static OrderCreatedEvent from(Order order) {
        return new OrderCreatedEvent(order.id, order.customerId, order.totalAmount, order.createdAt);
    }
}
```

### 💻 Consumer com idempotência (o padrão obrigatório)

```java
@ApplicationScoped
public class OrderCreatedConsumer {

    private static final Logger LOG = Logger.getLogger(OrderCreatedConsumer.class);

    @Inject ObjectMapper objectMapper;
    @Inject OrderProcessingService processingService;
    @Inject IdempotencyService idempotencyService;

    @ConfigProperty(name = "gcp.project-id") String projectId;

    private Subscriber subscriber;

    void onStart(@Observes StartupEvent event) {
        var subName = ProjectSubscriptionName.of(projectId, "order-created-sub");

        MessageReceiver receiver = (message, consumer) -> {
            String messageId = message.getMessageId();
            String traceId = message.getAttributesOrDefault("traceId", "");
            MDC.put("traceId", traceId);
            try {
                if (idempotencyService.alreadyProcessed(messageId)) {
                    LOG.infof("Duplicate message [id=%s] — ack and skip", messageId);
                    consumer.ack();
                    return;
                }

                OrderCreatedEvent event = objectMapper.readValue(
                        message.getData().toStringUtf8(), OrderCreatedEvent.class);

                processingService.process(event);
                idempotencyService.markProcessed(messageId);
                consumer.ack();

            } catch (BusinessException e) {
                // Erro de negócio — não adianta reprocessar. Ack e log.
                LOG.warnf(e, "Business rule rejected message %s — ack anyway", messageId);
                consumer.ack();
            } catch (Exception e) {
                // Erro transiente — nack, Pub/Sub reentrega (e depois de N tentativas, DLQ)
                LOG.errorf(e, "Transient failure processing %s", messageId);
                consumer.nack();
            } finally {
                MDC.clear();
            }
        };

        // Flow control — quantas msgs em paralelo
        FlowControlSettings flow = FlowControlSettings.newBuilder()
                .setMaxOutstandingElementCount(100L)
                .setMaxOutstandingRequestBytes(10L * 1024L * 1024L)
                .build();

        subscriber = Subscriber.newBuilder(subName, receiver)
                .setFlowControlSettings(flow)
                .setParallelPullCount(2)  // 2 streaming pulls em paralelo
                .build();
        subscriber.startAsync().awaitRunning();
        LOG.info("Subscriber started");
    }

    void onStop(@Observes ShutdownEvent event) {
        if (subscriber != null) {
            subscriber.stopAsync().awaitTerminated();
        }
    }
}
```

### 💻 IdempotencyService (a defesa contra duplicatas)

```java
@ApplicationScoped
public class IdempotencyService {

    @Inject EntityManager em;

    @Transactional(TxType.REQUIRES_NEW)
    public boolean alreadyProcessed(String messageId) {
        Long count = em.createQuery(
                "SELECT COUNT(p) FROM ProcessedMessage p WHERE p.messageId = :id", Long.class)
                .setParameter("id", messageId)
                .getSingleResult();
        return count > 0;
    }

    @Transactional(TxType.REQUIRES_NEW)
    public void markProcessed(String messageId) {
        try {
            ProcessedMessage pm = new ProcessedMessage();
            pm.messageId = messageId;
            pm.processedAt = LocalDateTime.now();
            em.persist(pm);
        } catch (ConstraintViolationException e) {
            // Outra thread processou no meio — OK, ignore
        }
    }
}
```

> 💡 **Por que UNIQUE constraint + INSERT em vez de SELECT-then-INSERT?** Race condition: duas threads veem "não processado", ambas processam. Com `UNIQUE(message_id)`, a segunda INSERT falha → você sabe que já foi. Defesa em profundidade.

### 💻 Transactional Outbox — a peça que salva consistência

**O problema**: você quer salvar o pedido **e** publicar o evento. Se salvar OK e o publish falhar (rede, Pub/Sub fora), o evento é perdido. Se publicar primeiro e o commit do banco falhar, publicou um evento de algo que não existe.

**A solução**: na **mesma transação** do banco, salve o evento numa tabela `outbox_events`. Um poller separado publica e marca como enviado.

```java
// No OrderService — o evento entra na mesma transação do pedido
@Transactional
public OrderResponse create(CreateOrderRequest request) {
    Order order = buildAndPersist(request);

    OutboxEvent outbox = new OutboxEvent();
    outbox.aggregateId = String.valueOf(order.id);
    outbox.eventType = "ORDER_CREATED";
    outbox.payload = objectMapper.writeValueAsString(OrderCreatedEvent.from(order));
    outbox.createdAt = LocalDateTime.now();
    outbox.published = false;
    outboxRepo.persist(outbox);

    return OrderResponse.from(order);
}
```

```java
@ApplicationScoped
public class OutboxPoller {

    @Inject OutboxEventRepository outboxRepo;
    @Inject OrderEventPublisher publisher;

    @Scheduled(every = "5s", concurrentExecution = SKIP)  // Skip se já rodando
    @Transactional
    void pollAndPublish() {
        List<OutboxEvent> pending = outboxRepo.findPending(100);
        for (OutboxEvent event : pending) {
            try {
                publisher.publishRaw(event.eventType, event.payload);
                event.published = true;
                event.publishedAt = LocalDateTime.now();
            } catch (Exception e) {
                LOG.warnf("Outbox event %d failed, retry next cycle", event.id);
                // Não marca como publicado — próxima iteração tenta de novo
            }
        }
    }
}
```

> 🔥 **War story**: time não usava outbox. Pedido criado, banco comita, código chama `publisher.publish(...)` — mas o Pub/Sub estava com latência alta, o SIGTERM chegou, a app encerrou antes do publish. Cliente viu pedido criado; inventário nunca soube. 12 pedidos órfãos descobertos 2 dias depois, via reclamação do cliente. Outbox resolve.

### 💻 Dead-letter topic + alerta

```bash
# Setup (faça via Terraform no projeto real)
gcloud pubsub topics create order-created-dlq
gcloud pubsub subscriptions create order-created-dlq-sub --topic=order-created-dlq

# Aplica DLQ na subscription principal
gcloud pubsub subscriptions update order-created-sub \
  --dead-letter-topic=order-created-dlq \
  --max-delivery-attempts=5
```

```promql
# Alerta: mensagens chegando na DLQ nos últimos 5min
sum(rate(pubsub_subscription_num_undelivered_messages{subscription_id="order-created-dlq-sub"}[5m])) > 0
```

### 🚫 Do's and Don'ts

✅ **DO**: sempre idempotência no consumer (tabela com UNIQUE + ack tardio).
❌ **DON'T**: assumir exactly-once.
→ **POR QUÊ**: duplicatas **acontecem** por design. Sem idempotência, pagamento duplicado.

✅ **DO**: ack deadline 2–3× o tempo médio de processamento.
❌ **DON'T**: deadline de 10s para processamento de 8s.
→ **POR QUÊ**: qualquer lentidão = msg reenviada = processamento duplicado + DLQ prematura.

✅ **DO**: message attributes para metadata + server-side filter.
❌ **DON'T**: colocar tudo no body e filtrar no consumer.
→ **POR QUÊ**: consumidor paga para receber e descartar. Filter no server é grátis.

✅ **DO**: Transactional Outbox para eventos críticos (pedido, pagamento).
❌ **DON'T**: `publish()` dentro de `@Transactional` se o evento não pode ser perdido.
→ **POR QUÊ**: publish tem latência variável, pode cair entre commit e ack. Outbox garante at-least-once end-to-end.

✅ **DO**: DLQ com alerta desde dia 1.
❌ **DON'T**: criar DLQ e nunca olhar.
→ **POR QUÊ**: DLQ silenciosa = bugs acumulando por semanas, descobertos por cliente.

### 🎚️ Níveis
- 🟢 Publicar, consumir, idempotência básica, configurar topic/sub
- 🟡 Transactional outbox, DLQ + alerta, ordering keys quando precisa, filtering por atributo
- 🔴 Schema Registry, exactly-once subscription, push subscription com OIDC auth, load test de throughput

### 🏗️ PBL Fase 2.1 — Pub/Sub + Outbox

1. `OrderEventPublisher` + `OrderCreatedConsumer` com idempotência.
2. Tabela `outbox_events` + entidade + `OutboxPoller` com `@Scheduled`.
3. **Teste de idempotência**: publish da **mesma mensagem 3×** → consumer processa apenas 1×.
4. **Teste de outbox**: crie order → commit → kill a app antes do poller rodar → reinicie → confirme que evento é publicado no restart.
5. Configure DLQ e envie mensagem que causa erro transiente indefinido — confirme que após 5 tentativas vai para DLQ.

### 📚 Aprofundamento
- Pub/Sub concepts: https://cloud.google.com/pubsub/docs/overview
- Quarkus + Pub/Sub: https://docs.quarkiverse.io/quarkus-google-cloud-services/main/pubsub.html
- Outbox pattern: https://microservices.io/patterns/data/transactional-outbox.html
- Pub/Sub emulator: https://cloud.google.com/pubsub/docs/emulator

---

## Módulo 5 — gRPC: contrato forte para comunicação interna

> **O Problema**: serviço de tracking consulta status a cada segundo. REST tem overhead de texto, de headers, de negociação. gRPC: binário, HTTP/2, contrato forte.

### 🎯 80/20

1. Contrato primeiro. `.proto` é a fonte da verdade.
2. Sempre **deadline** no client. Sem deadline = chamada pendurada.
3. `StreamObserver` para unary é chato mas funciona. Para streaming, Mutiny tem `Uni`/`Multi` nativos.

### 💻 Definição `.proto`

```proto
// src/main/proto/order_service.proto
syntax = "proto3";
option java_package = "com.example.order.grpc";
option java_multiple_files = true;

service OrderGrpcService {
  rpc GetOrderStatus(GetOrderStatusRequest) returns (OrderStatusResponse);
  rpc StreamOrderEvents(StreamOrderEventsRequest) returns (stream OrderEvent);
}

message GetOrderStatusRequest { int64 order_id = 1; }
message OrderStatusResponse {
  int64 order_id = 1;
  string status = 2;
  string updated_at = 3;
}

message StreamOrderEventsRequest { string customer_id = 1; }
message OrderEvent {
  int64 order_id = 1;
  string event_type = 2;
  string timestamp = 3;
}
```

### 💻 Server

```java
@GrpcService
public class OrderGrpcServiceImpl extends OrderGrpcServiceGrpc.OrderGrpcServiceImplBase {

    @Inject OrderService orderService;

    @Override
    public void getOrderStatus(GetOrderStatusRequest request,
                                StreamObserver<OrderStatusResponse> observer) {
        orderService.findById(request.getOrderId())
                .ifPresentOrElse(
                    order -> {
                        observer.onNext(OrderStatusResponse.newBuilder()
                                .setOrderId(order.id)
                                .setStatus(order.status.name())
                                .setUpdatedAt(order.updatedAt.toString())
                                .build());
                        observer.onCompleted();
                    },
                    () -> observer.onError(io.grpc.Status.NOT_FOUND
                            .withDescription("Order " + request.getOrderId() + " not found")
                            .asRuntimeException())
                );
    }
}
```

### 💻 Client — SEMPRE com deadline

```java
@ApplicationScoped
public class InventoryGrpcClient {

    @GrpcClient("inventory")
    InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    public boolean checkStock(String productId, int quantity) {
        try {
            CheckStockResponse resp = stub
                    .withDeadlineAfter(2, TimeUnit.SECONDS)   // ← OBRIGATÓRIO
                    .checkStock(CheckStockRequest.newBuilder()
                            .setProductId(productId)
                            .setQuantity(quantity)
                            .build());
            return resp.getAvailable();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new ServiceUnavailableException("Inventory timeout");
            }
            throw e;
        }
    }
}
```

```properties
quarkus.grpc.server.port=9000
quarkus.grpc.clients.inventory.host=${INVENTORY_GRPC_HOST:localhost}
quarkus.grpc.clients.inventory.port=9000
```

> 🔥 **War story**: serviço sem deadline fez chamada gRPC para um downstream congelado. A thread ficou presa. A próxima chamada também. Em 30 minutos, todo o worker pool saturou. O pod ficou "up" (health check liveness respondia), mas 0 req/s. Moral: **todo client gRPC precisa de `withDeadlineAfter`**.

### 🚫 Do's and Don'ts

✅ **DO**: deadline em todo client gRPC (2s–5s para chamadas entre serviços internos).
❌ **DON'T**: deixar sem deadline.

✅ **DO**: para streaming, use Mutiny (`Multi`) — composição é muito melhor.
❌ **DON'T**: misturar `StreamObserver` com threading manual — vira spaghetti.

### 🎚️ Níveis
- 🟢 Server unary + client com deadline
- 🟡 Interceptors (logging, auth), streaming server/client, deadline propagation
- 🔴 Bidirectional streaming, custom load balancing, gRPC-Web/gRPC-Gateway para browsers

### 🏗️ PBL Fase 2.2 — gRPC

1. `.proto` com unary + streaming.
2. `OrderGrpcServiceImpl`.
3. Client com deadline 2s.
4. Teste `@QuarkusTest` que sobe server gRPC in-process e testa o client.

### 📚 Aprofundamento
- Quarkus gRPC: https://quarkus.io/guides/grpc-getting-started
- gRPC best practices: https://grpc.io/docs/guides/

---

## Módulo 6 — Programação Reativa: Mutiny, Virtual Threads, e a decisão sábia

> **O Problema**: Quarkus pode rodar código imperativo (`@RunOnVirtualThread`), reativo (Mutiny), ou bloqueante em worker pool (`@Blocking`). Escolher errado = ou performance ruim, ou código ilegível. Cada caso tem um vencedor.

### 🎯 80/20

1. **CRUD típico com JDBC/Mongo** → `@RunOnVirtualThread`. Código síncrono, performance decente, zero curva.
2. **Composição de chamadas paralelas a múltiplos serviços** → `Uni.combine().all().unis(...)`.
3. **Pipeline de dados contínuo (streaming, event-driven)** → `Multi` com backpressure.
4. **Nunca** `.await().indefinitely()` em código que roda na event loop. É o `.block()` do Mutiny — deadlock garantido.

### 🧠 O modelo de threads do Quarkus (o diagrama que salva carreira)

```
┌─────────────────────────────────────────────────────────────┐
│                    Quarkus (Vert.x engine)                  │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Event Loop Threads  (pouquíssimas: ~2× núcleos CPU)        │
│  ┌──────┬──────┬──────┬──────┐                              │
│  │ EL-0 │ EL-1 │ EL-2 │ EL-3 │  ← requests não bloqueantes  │
│  └──────┴──────┴──────┴──────┘                              │
│      ▲                                                      │
│      │ HTTP chega aqui                                      │
│                                                             │
│  ── bloqueou uma? → HTTP inteiro do pod fica lento ──       │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Worker Pool   (20 threads por padrão)                      │
│  ┌──────┬──────┬──┬──┐                                      │
│  │ W-0  │ W-1  │..│..│   ← @Blocking endpoints              │
│  └──────┴──────┴──┴──┘                                      │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Virtual Threads  (milhões possíveis, JVM-managed)          │
│  ┌┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┬┐                            │
│  ├┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┴┤   ← @RunOnVirtualThread    │
│  └─────────────────────────────┘                            │
└─────────────────────────────────────────────────────────────┘
```

**A regra**: tudo que faz I/O bloqueante (JDBC, Mongo síncrono, HTTP sync) **não pode** rodar na event loop. Opções: `@Blocking`, `@RunOnVirtualThread`, ou código reativo.

### 🧠 Decision tree

```
O endpoint/método faz I/O bloqueante (JDBC, sync HTTP, filesystem)?
│
├── Sim ──► Você tem Java 21+ e o time usa Virtual Threads?
│          │
│          ├── Sim ──► @RunOnVirtualThread  ✅ (padrão moderno)
│          └── Não ──► @Blocking  (worker pool — funciona, pool é fixo)
│
└── Não (puro async / non-blocking HTTP / composição de services)
           │
           ├── Valor único ──► Uni<T>
           ├── Stream de valores ──► Multi<T>
           └── Mix ──► Uni<T> no topo, compose com Multi se precisar
```

### 💻 `@RunOnVirtualThread` — o caminho moderno

```java
@GET
@RunOnVirtualThread
public List<OrderResponse> list() {
    // JDBC bloqueante? Sem problema. Virtual thread desbloqueia quando espera I/O.
    return orderRepository.listAll().stream().map(OrderResponse::from).toList();
}
```

**Como funciona por baixo**: quando o código bloqueia em I/O, a JVM desanexa a virtual thread da plataforma thread (um *carrier*). O carrier volta a rodar outras virtual threads. Quando o I/O termina, a virtual thread pega o próximo carrier livre. Resultado: você escreve código síncrono, mas escala como reativo em cenários I/O-bound.

> ⚠️ **Pitfall**: `synchronized` pin (prende) a virtual thread ao carrier. Prefira `ReentrantLock`. Em Quarkus 3.x isso está melhorando mas vale a atenção em hot paths.

### 💻 Mutiny `Uni<T>` — uma operação assíncrona

```java
public Uni<EnrichedOrder> enrich(Order order) {
    // Duas chamadas em paralelo — 300ms ao invés de 500ms sequencial
    Uni<CustomerInfo> customer = customerClient.getCustomerAsync(order.customerId);
    Uni<PricingInfo> pricing = pricingClient.getPricingAsync(order.id);

    return Uni.combine().all()
            .unis(customer, pricing)
            .asTuple()
            .onItem().transform(t -> new EnrichedOrder(order, t.getItem1(), t.getItem2()))
            .onFailure().recoverWithItem(e -> {
                LOG.warn("Enrichment failed, returning partial", e);
                return new EnrichedOrder(order, null, null);
            })
            .ifNoItem().after(Duration.ofSeconds(3)).fail();  // timeout
}
```

### 💻 Mutiny vs Reactor — tradução de operadores

| Operação | Reactor (Spring WebFlux) | Mutiny (Quarkus) |
|---|---|---|
| Valor único | `Mono<T>` | `Uni<T>` |
| Stream de valores | `Flux<T>` | `Multi<T>` |
| Transformar | `.map()` | `.onItem().transform()` |
| Encadear async | `.flatMap()` | `.onItem().transformToUni()` |
| Encadear stream | `.flatMapMany()` | `.onItem().transformToMulti()` |
| Tratar erro com valor | `.onErrorResume()` | `.onFailure().recoverWithItem()` |
| Tratar erro com Uni | `.onErrorResume(f -> ...)` | `.onFailure().recoverWithUni(...)` |
| Timeout | `.timeout(Duration)` | `.ifNoItem().after(Duration).fail()` |
| Retry | `.retry(n)` | `.onFailure().retry().atMost(n)` |
| Combinar N paralelos | `Mono.zip(a, b)` | `Uni.combine().all().unis(a, b)` |
| Converter p/ bloqueante | `.block()` | `.await().indefinitely()` |
| ⚠️ **Nunca em produção** | `.block()` na event loop | `.await().indefinitely()` na event loop |

> 💡 **Nomes diferentes, mesmo jogo**: `.onItem().transform(f)` do Mutiny é literalmente `.map(f)` do Reactor. Mutiny é mais verboso porque é "grupo de métodos por intenção" (`.onItem()`, `.onFailure()`, `.ifNoItem()`). Ajuda na leitura depois de 2 semanas.

### 💻 `Multi<T>` — streaming com backpressure

```java
@GET
@Path("/events")
@Produces(MediaType.SERVER_SENT_EVENTS)
public Multi<OrderEvent> streamEvents() {
    return orderEventBus.subscribe()  // emite OrderEvent indefinidamente
            // Backpressure: consumidor devagar não afoga o produtor
            .onOverflow().dropPreviousItems()  // ou .buffer(1000), .keep(10), etc.
            .onFailure().retry().withBackOff(Duration.ofSeconds(1)).atMost(3);
}
```

**Estratégias de backpressure em `Multi`**:

| Estratégia | Comportamento | Quando usar |
|---|---|---|
| `.onOverflow().buffer(n)` | Acumula até N, estoura `BackPressureFailure` | Default seguro. N = quanto você aguenta na memória |
| `.onOverflow().dropPreviousItems()` | Descarta item antigo quando chega novo | Cotação de preço, última posição de GPS |
| `.onOverflow().drop()` | Descarta item novo se buffer cheio | Telemetria "nice to have" |
| `.onOverflow().dropNew()` | Sinônimo de `.drop()` | — |
| `.onOverflow().keep(n)` | Mantém só os últimos N | Logs recentes |

### 💻 Debugging reativo — a sua melhor ferramenta

```java
// Adicione .log() para ver eventos no stream
Uni<Order> result = orderRepository.findByIdAsync(id)
        .log("findById")  // emite logs em cada onSubscribe, onItem, onFailure
        .onItem().transform(this::enrich)
        .log("enriched")
        .onFailure().invoke(e -> LOG.error("Failed", e));
```

> 💡 **Stack trace em código reativo é horrível** porque o trace é da thread do scheduler, não da cadeia lógica. Mutiny tem `ContextPropagation` para trace, e `.log()` no meio de cadeias ajuda muito em dev. Em prod, prefira virtual threads se não precisa do paradigma reativo — stack trace fica legível.

### 🚫 Do's and Don'ts

✅ **DO**: `@RunOnVirtualThread` em endpoints com JDBC/Mongo síncrono.
❌ **DON'T**: forçar reativo quando imperativo resolve.
→ **POR QUÊ**: reativo adiciona custo cognitivo. Use só quando o ganho justifica (composição de N chamadas assíncronas, streaming real).

✅ **DO**: `Uni.combine()` para paralelizar chamadas a serviços independentes.
❌ **DON'T**: chamar serviços em sequência e depois ficar surpreso com latência.
→ **POR QUÊ**: 3 chamadas de 100ms sequenciais = 300ms. Paralelas = 100ms.

✅ **DO**: backpressure explícito em `Multi` consumido por cliente externo (SSE, WebSocket).
❌ **DON'T**: `Multi` sem `onOverflow` em produção.
→ **POR QUÊ**: consumer lento + producer rápido = OOM em minutos.

✅ **DO**: `.log("stage-name")` durante debug; remova em prod.
❌ **DON'T**: `.await().indefinitely()` na event loop.
→ **POR QUÊ**: deadlock. A event loop trava, app deixa de responder, health check falha, pod é morto.

### 🎚️ Níveis
- 🟢 Entender o modelo de threads + usar `@RunOnVirtualThread`
- 🟡 `Uni`: composição, erro, timeout, retry + quando migrar de virtual thread para reativo
- 🔴 `Multi` com backpressure, `combine` de N Unis, integração com Kafka/Pub/Sub streaming, debug com context propagation

### 🏗️ PBL Fase 2.3 — Threading & Mutiny

1. **Auditoria**: revise todos os endpoints — `@RunOnVirtualThread` onde tem JDBC/Mongo.
2. Endpoint `GET /orders/{id}/enriched` que chama em paralelo: `customer-service`, `pricing-service`, `loyalty-service` com `Uni.combine()`. Timeout total 3s. Se um falhar, retorne parcial.
3. Endpoint `GET /orders/events` (SSE) que emite `Multi<OrderEvent>` com `onOverflow().dropPreviousItems()`. Teste com cliente lento — confirme que a app não cresce em memória.
4. **Teste de stress**: `wrk` com 200 conexões no `/orders` antes e depois de adicionar `@RunOnVirtualThread`. Compare.

### 📚 Aprofundamento
- Mutiny docs: https://smallrye.io/smallrye-mutiny/
- Quarkus reactive: https://quarkus.io/guides/quarkus-reactive-architecture
- Virtual threads: https://quarkus.io/guides/virtual-threads
- Reactive vs imperative: https://quarkus.io/guides/getting-started-reactive

---


# 📍 FASE 3 — PRODUÇÃO: Resiliência, Observabilidade (Grafana deep), Segurança, Cache (Semana 3)

---

## Módulo 7 — Resiliência: Sobrevivendo a falhas que vão acontecer

> **O Problema**: em produção, serviços caem. Rede treme. Timeouts estouram. Sem resiliência, uma falha num downstream não-crítico (notificação) derruba o crítico (pedido). Resiliência é o que separa código que funciona em dev de código que fica 4h no ar.

### 🎯 80/20

A composição `@Bulkhead → @CircuitBreaker → @Retry → @Timeout → @Fallback` em toda chamada externa resolve 80% dos cenários. Coisa de 20 linhas de código, 2 horas para entender, anos de incidentes evitados.

### 🧠 A cascata da morte (sem resiliência)

```
Inventário trava (GC long pause, deploy ruim, rede) 
   │
   ├─► sua chamada HTTP fica esperando 30s (default)
   │     └─► thread presa no pool do Vert.x worker ou no virtual thread carrier
   │
   ├─► próximo request também trava
   │     └─► pool do Vert.x esgota
   │
   ├─► health check `/q/health/ready` depende de algo que usa pool → falha
   │
   ├─► Tsuru marca pod como unhealthy → reinicia
   │
   └─► Inventário continua fora → loop infinito
            └─► o serviço upstream (que te chama) começa o mesmo ciclo
```

### 💻 A composição de produção

```bash
quarkus ext add smallrye-fault-tolerance  # se não veio no create
```

```java
@ApplicationScoped
public class ResilientInventoryClient {

    private static final Logger LOG = Logger.getLogger(ResilientInventoryClient.class);

    @Inject @RestClient InventoryRestClient inventoryApi;

    @Bulkhead(value = 10, waitingTaskQueue = 20)    // isola: max 10 concorrentes
    @CircuitBreaker(
        requestVolumeThreshold = 10,                 // avalia após 10 requests
        failureRatio = 0.5,                          // abre se 50%+ falharem
        delay = 10000,                                // fica aberto 10s
        successThreshold = 3                          // 3 sucessos para fechar
    )
    @Retry(
        maxRetries = 2,
        delay = 300, jitter = 100,                   // jitter evita thundering herd
        retryOn = { IOException.class, TimeoutException.class },  // só transientes
        abortOn = { BusinessException.class }        // não retry em erro de negócio
    )
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    @Fallback(fallbackMethod = "checkStockFallback")
    public StockResponse checkStock(String productId, int quantity) {
        return inventoryApi.checkStock(productId, quantity);
    }

    // Degradação graciosa — aceita otimistamente
    private StockResponse checkStockFallback(String productId, int quantity) {
        LOG.warnf("Inventory unavailable — optimistic ACK for %s", productId);
        return new StockResponse(true, -1, "OPTIMISTIC");
    }
}
```

**Ordem de execução (de fora para dentro)**:
```
Request → Bulkhead → CircuitBreaker → Retry → Timeout → Fallback → método real
```

### 🧠 Circuit Breaker — os 3 estados

```
         sucesso < threshold
         ┌────────────────┐
         ▼                │
    ┌─────────┐       ┌────────┐
    │ CLOSED  │──────▶│  OPEN  │◀─┐
    │(normal) │fail % │(rejeita│  │timeout delay
    └─────────┘       │ tudo)  │  │expira
         ▲            └────┬───┘  │
         │                 │      │
     N sucessos            ▼      │
         │           ┌──────────┐ │
         └───────────│ HALF-OPEN │─┘
                     │(testando)│
                     └──────────┘
```

- **CLOSED**: normal. Falhas contadas.
- **OPEN**: requests rejeitados **imediatamente** (sem nem chamar downstream). Dá tempo para o serviço respirar.
- **HALF-OPEN**: após `delay`, poucos requests passam para testar. Se sucesso > threshold → CLOSED. Se falha → OPEN.

### 🔥 Erros clássicos

**1. Retry sem jitter**
```java
@Retry(maxRetries = 3, delay = 1000)  // ❌ sem jitter
// 1000 consumers retentam no mesmo instante → thundering herd derruba o serviço que acabou de voltar
```
Fix: `@Retry(delay = 1000, jitter = 500)` — cada retry dentro de [500ms, 1500ms].

**2. Retry em erro de negócio**
```java
@Retry(maxRetries = 3)  // ❌ sem abortOn
// Cliente manda "quantity=-1" → 400 → retry 3× → mesmo 400 → tempo perdido + métricas ruins
```
Fix: `@Retry(abortOn = {BusinessException.class, ConstraintViolationException.class})`.

**3. Timeout ausente ou muito alto**
```java
@Retry(maxRetries = 3)
// sem @Timeout → chamada trava 30s default → 3 retries × 30s = 90s preso
```
Fix: sempre `@Timeout`, geralmente 2–5s para chamadas internas.

### 🚫 Do's and Don'ts

✅ **DO**: `@Timeout` em **toda** chamada externa.
✅ **DO**: `@Retry` com jitter + `retryOn` restrito a erros transientes.
✅ **DO**: `@Fallback` para serviços não-críticos (notificação, analytics). Degradação > falha total.
❌ **DON'T**: retry infinito. Circuit breaker dá tempo.
❌ **DON'T**: retry em 4xx — é responsabilidade do chamador corrigir.
❌ **DON'T**: `@Fallback` mascarando bug real. Fallback é para falhas de infra.

### 🎚️ Níveis
- 🟢 Composição padrão em chamada REST externa
- 🟡 Fallback com lógica de negócio (cache stale, default), métricas de circuit breaker no dashboard
- 🔴 Bulkhead dimensionado por latency budget, chaos testing (toxiproxy), customização de estratégias

### 🏗️ PBL Fase 3.1 — Resiliência

1. `ResilientInventoryClient` com composição completa.
2. Teste: mock que falha 50% → confirme que circuit breaker abre.
3. Teste: inventário 100% fora → pedido criado com status `OPTIMISTIC`.
4. Teste: 30 requests simultâneos com 2s de latência → bulkhead limita a 10 ativos.

### 📚 Aprofundamento
- SmallRye Fault Tolerance: https://quarkus.io/guides/smallrye-fault-tolerance
- Release It! (Nygard) — capítulos 4–7 são obrigatórios

---

## Módulo 8 — Observabilidade: Grafana, Prometheus, Loki, Tempo (DEEP)

> **O Problema**: às 3h27 da manhã, seu celular vibra. "p99 está em 8s". Sem observabilidade, você abre o código, roda mentalmente, chuta, e aceita. Com os 3 pilares conectados, você encontra a causa em 5 minutos, arruma, volta a dormir.

### 🎯 80/20

1. **Traces te dizem ONDE**. Métricas te dizem QUE algo está errado. Logs te dizem O QUE aconteceu.
2. **`traceId` em todo log** é o fio condutor. Configure uma vez, colha para sempre.
3. **RED metrics** (Rate, Errors, Duration) num dashboard por serviço é o mínimo viável.
4. **LogQL** é só `{label}` + `|= "termo"` + `| json` para 80% dos casos.

### 🧠 Os 3 pilares conectados

```
                 ┌───────────────┐
                 │  Alerta 3h27  │
                 │  p99 > 500ms  │
                 └───────┬───────┘
                         ▼
         ┌──────────────────────────────┐
         │        Métricas (PromQL)     │
         │  Dashboard mostra: POST      │
         │  /orders p99 = 8s, 5xx = 12% │
         └───────┬──────────────────────┘
                 │ "qual request teve esse tempo?"
                 ▼
         ┌──────────────────────────────┐
         │       Trace (Tempo)          │
         │  Trace abc123 mostra:        │
         │  span SELECT orders = 7.8s   │
         └───────┬──────────────────────┘
                 │ "por que?"
                 ▼
         ┌──────────────────────────────┐
         │         Logs (Loki)          │
         │  traceId=abc123:             │
         │  "slow query: missing index" │
         └───────┬──────────────────────┘
                 ▼
         ┌──────────────────────────────┐
         │  Root cause: migration sem   │
         │  índice em orders.status     │
         │  → CREATE INDEX → resolvido  │
         └──────────────────────────────┘
```

### 💻 Stack local — docker-compose para dev

```yaml
# docker-compose-observability.yml
version: '3.8'
services:
  otel-collector:
    image: otel/opentelemetry-collector-contrib:latest
    ports: ["4317:4317", "4318:4318"]
    volumes: ["./otel-config.yml:/etc/otel-config.yml"]
    command: ["--config", "/etc/otel-config.yml"]

  prometheus:
    image: prom/prometheus:latest
    ports: ["9090:9090"]
    volumes: ["./prometheus.yml:/etc/prometheus/prometheus.yml"]

  tempo:
    image: grafana/tempo:latest
    ports: ["3200:3200"]
    command: ["-config.file=/etc/tempo.yml"]
    volumes: ["./tempo.yml:/etc/tempo.yml"]

  loki:
    image: grafana/loki:latest
    ports: ["3100:3100"]

  grafana:
    image: grafana/grafana:latest
    ports: ["3000:3000"]
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
    volumes: ["./grafana-datasources.yml:/etc/grafana/provisioning/datasources/ds.yml"]
```

```yaml
# prometheus.yml
global:
  scrape_interval: 15s
scrape_configs:
  - job_name: 'order-service'
    metrics_path: '/q/metrics'
    static_configs:
      - targets: ['host.docker.internal:8080']
```

```yaml
# grafana-datasources.yml — datasources já configurados
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    access: proxy
    isDefault: true
  - name: Loki
    type: loki
    url: http://loki:3100
    access: proxy
    jsonData:
      derivedFields:
        - datasourceUid: tempo
          matcherRegex: 'traceId=(\w+)'
          name: traceId
          url: '$${__value.raw}'
  - name: Tempo
    type: tempo
    uid: tempo
    url: http://tempo:3200
    access: proxy
    jsonData:
      tracesToLogsV2:
        datasourceUid: loki
        filterByTraceID: true
      tracesToMetrics:
        datasourceUid: prometheus
```

> 💡 A mágica de `derivedFields` + `tracesToLogsV2`: no Grafana Explore, clicar em `traceId=abc123` num log abre o trace. Clicar num span do trace mostra os logs daquele request. É a **correlação** que torna observabilidade útil.

### 💻 OpenTelemetry no Quarkus

```properties
quarkus.otel.exporter.otlp.endpoint=${OTEL_ENDPOINT:http://localhost:4317}
quarkus.otel.service.name=order-service
quarkus.otel.resource.attributes=environment=${ENV:dev},team=platform

# Sampling — crucial em prod
quarkus.otel.traces.sampler=parentbased_traceratio
quarkus.otel.traces.sampler.arg=0.1           # 10% em prod
%dev.quarkus.otel.traces.sampler.arg=1.0      # 100% em dev

# Propagação — W3C é o padrão moderno
quarkus.otel.propagators=tracecontext,baggage
```

```java
@ApplicationScoped
public class OrderProcessingService {

    @WithSpan("process-order")   // span customizado
    public void process(
            @SpanAttribute("order.id") long orderId,
            @SpanAttribute("order.customer") String customerId) {
        Span current = Span.current();
        current.setAttribute("business.flow", "order-processing");

        validateStock(orderId);
        calculatePricing(orderId);
        updateStatus(orderId, OrderStatus.CONFIRMED);
    }

    @WithSpan
    private void validateStock(long orderId) {
        // span automático com o nome do método
    }
}
```

### 💻 Métricas de negócio (não só técnicas)

```java
@ApplicationScoped
public class OrderMetrics {

    private final Counter ordersCreated;
    private final Counter ordersFailed;
    private final Timer processingDuration;
    private final Gauge pendingOrders;

    @Inject
    public OrderMetrics(MeterRegistry registry, OrderRepository repo) {
        ordersCreated = Counter.builder("orders_created_total")
                .description("Total orders successfully created")
                .register(registry);

        ordersFailed = Counter.builder("orders_failed_total")
                .description("Orders that failed")
                .tag("reason_available_labels", "validation|stock|payment|internal")
                .register(registry);

        processingDuration = Timer.builder("order_processing_duration_seconds")
                .description("Time to process an order end-to-end")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        pendingOrders = Gauge.builder("orders_pending_current",
                    () -> repo.count("status", OrderStatus.PENDING))
                .description("Orders awaiting processing")
                .register(registry);
    }

    public void recordCreated() { ordersCreated.increment(); }
    public void recordFailed(String reason) {
        Counter.builder("orders_failed_total").tag("reason", reason).register(Metrics.globalRegistry).increment();
    }
    public Timer.Sample startTimer() { return Timer.start(); }
    public void stopTimer(Timer.Sample s) { s.stop(processingDuration); }
}
```

> ⚠️ **Cardinalidade mata Prometheus.** Nunca use `user_id`, `email`, `order_id` como label. 1M users = 1M séries temporais → Prometheus explode. Labels devem ser **finitos** (status HTTP, método, endpoint, região, reason enum).

### 💻 Logs estruturados + MDC

```properties
quarkus.log.console.json=true
quarkus.log.console.json.additional-field."service".value=order-service
quarkus.log.console.json.additional-field."environment".value=${ENV:dev}
%dev.quarkus.log.console.json=false           # dev humano lê melhor em texto
```

```java
@ApplicationScoped
public class OrderService {

    private static final Logger LOG = Logger.getLogger(OrderService.class);

    public OrderResponse create(CreateOrderRequest request) {
        MDC.put("customerId", request.customerId());
        MDC.put("itemCount", String.valueOf(request.items().size()));
        try {
            LOG.info("Creating order");  // JSON terá customerId + traceId + itemCount
            // ...
            LOG.info("Order created");
            return response;
        } finally {
            MDC.clear();
        }
    }
}
```

**O log resultante** (JSON):
```json
{
  "timestamp": "2026-04-17T13:42:01.234Z",
  "level": "INFO",
  "service": "order-service",
  "environment": "prod",
  "traceId": "abc123def",
  "spanId": "span456",
  "customerId": "cust-001",
  "itemCount": "3",
  "message": "Creating order",
  "logger": "com.example.OrderService"
}
```

### 💻 Dashboard RED — o mínimo viável

Um dashboard por serviço, 4 painéis:

```promql
# 1. RATE — requests por segundo, por endpoint
sum(rate(http_server_requests_seconds_count{service="order-service"}[1m])) by (uri)

# 2. ERRORS — % de 5xx
sum(rate(http_server_requests_seconds_count{service="order-service",status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count{service="order-service"}[5m]))

# 3. DURATION — p50, p95, p99
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{service="order-service"}[5m])) by (le, uri))
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{service="order-service"}[5m])) by (le, uri))
histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{service="order-service"}[5m])) by (le, uri))

# 4. BUSINESS — pedidos criados vs falhos
rate(orders_created_total[1m])
rate(orders_failed_total[1m])
```

### 💻 Dashboard (JSON snippet — importável no Grafana)

```json
{
  "title": "Order Service — RED",
  "panels": [
    {
      "title": "Rate (req/s)",
      "type": "timeseries",
      "targets": [{
        "expr": "sum(rate(http_server_requests_seconds_count{service=\"order-service\"}[1m])) by (uri)",
        "legendFormat": "{{uri}}"
      }]
    },
    {
      "title": "Errors (%)",
      "type": "stat",
      "targets": [{
        "expr": "100 * sum(rate(http_server_requests_seconds_count{service=\"order-service\",status=~\"5..\"}[5m])) / sum(rate(http_server_requests_seconds_count{service=\"order-service\"}[5m]))"
      }],
      "fieldConfig": {
        "defaults": {
          "thresholds": {
            "steps": [
              {"color": "green", "value": null},
              {"color": "yellow", "value": 1},
              {"color": "red", "value": 5}
            ]
          }
        }
      }
    },
    {
      "title": "Latency p99",
      "type": "timeseries",
      "targets": [{
        "expr": "histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{service=\"order-service\"}[5m])) by (le, uri))",
        "legendFormat": "p99 {{uri}}"
      }]
    }
  ]
}
```

### 💻 LogQL — do básico ao útil

```logql
# Básico
{service="order-service"}                                  # todos os logs
{service="order-service"} |= "ERROR"                       # com ERROR no texto
{service="order-service"} != "health"                      # sem "health"

# JSON parsing
{service="order-service"} | json                           # parseia JSON
{service="order-service"} | json | level="ERROR"           # filtro em campo JSON
{service="order-service"} | json | customerId="cust-001"   # erro deste cliente
{service="order-service"} | json | traceId="abc123"        # trace específico

# Agregações (derivadas)
rate({service="order-service"} |= "ERROR" [1m])            # taxa de erros
sum by (level) (count_over_time({service="order-service"} | json [5m]))

# Pattern matching
{service="order-service"} | json | line_format "{{.level}} [{{.traceId}}] {{.message}}"

# Regex
{service="order-service"} |~ "timeout|deadline"
```

### 🧠 SLI / SLO — o que você promete

```
SLI (Service Level Indicator):  o que você mede
  ─ % de requests que retornam < 500ms
  ─ % de requests sem 5xx

SLO (Objective):  o alvo
  ─ 99.9% < 500ms em 30 dias   → Error Budget: ~43min/mês fora do SLO
  ─ 99.95% sem 5xx em 30 dias  → Error Budget: ~21min/mês

SLA (Agreement):  o que você contrata com o cliente (≈ SLO - margem)
```

**Regra da casa**: cada serviço tem SLO documentado + dashboard de Error Budget. Quando você queima 50% do budget da janela, freia feature e foca em estabilidade.

### 💻 Fluxo de investigação (runbook)

```
1. ALERTA: error rate > 5% no order-service
2. DASHBOARD (Grafana): spike de 5xx no POST /orders (~13:42)
3. LOGS (Loki Explore): 
   {service="order-service"} | json | level="ERROR" | __error__=""
   → encontra "CircuitBreakerOpenException for inventory-api"
4. TRACE (traceId do log → Tempo): 
   span `inventoryApi.checkStock` → TIMEOUT após 3s
5. MÉTRICAS do inventário: p99 subiu de 200ms para 8s
6. ROOT CAUSE: inventário com deploy ruim de índice
7. AÇÃO: fallback ativou (`OPTIMISTIC`), orders continuam — contato com time de inventário
```

### 🚫 Do's and Don'ts

✅ **DO**: `traceId` em todo log (automático com OTel, **verifique**).
❌ **DON'T**: logar PII (CPF, token, senha, email completo).
→ **POR QUÊ**: LGPD + auditoria. Mascare ou omita.

✅ **DO**: métricas de negócio + técnicas.
❌ **DON'T**: labels de alta cardinalidade (user_id, order_id, email).
→ **POR QUÊ**: Prometheus explode. Custo de storage explode. Dashboard fica inutilizável.

✅ **DO**: `sampling=0.1` em prod, `1.0` em dev.
❌ **DON'T**: always-on sampling em prod com alto tráfego.
→ **POR QUÊ**: 10k req/s × 100% = 10k traces/s = custo insustentável.

✅ **DO**: dashboard RED por serviço + Error Budget dashboard.
❌ **DON'T**: deployar sem um dashboard que prove que está funcionando.

### 🎚️ Níveis
- 🟢 OTel automático + logs JSON + `/q/metrics` + dashboard RED
- 🟡 Spans customizados, métricas de negócio, LogQL, correlação trace↔log, alertas
- 🔴 Sampling adaptativo, SLO tracking com Error Budget, exporters custom, tracing de Pub/Sub end-to-end

### 🏗️ PBL Fase 3.2 — Observabilidade

1. Suba stack local com `docker-compose-observability.yml`.
2. Configure OTel + `logging-json` + Micrometer.
3. `OrderMetrics` com counter/timer/gauge.
4. `@WithSpan` nos métodos de negócio.
5. Faça um POST /orders → no Grafana Explore, **reconstrua o fluxo completo em < 5 min** partindo só do `traceId`.
6. Crie dashboard RED (painéis de rate/errors/duration) e importe no Grafana.
7. Crie alerta: `error_rate > 5% por 5min`.
8. **Chaos test**: derrube o inventory mock → veja CB abrir nos painéis, veja logs de fallback correlacionados com o trace.

### 📚 Aprofundamento
- Quarkus OpenTelemetry: https://quarkus.io/guides/opentelemetry
- Quarkus Micrometer: https://quarkus.io/guides/micrometer
- Grafana Loki: https://grafana.com/docs/loki/latest/
- PromQL: https://prometheus.io/docs/prometheus/latest/querying/basics/
- SRE Book (Google): https://sre.google/books/

---

## Módulo 9 — Segurança: OIDC/JWT

> **O Problema**: seu serviço está aberto. Qualquer um cria pedido para qualquer customerId. Produção sem auth = incidente.

### 🎯 80/20

1. `@Authenticated` na classe + `@RolesAllowed` nos métodos resolve 80%.
2. `@TestSecurity` para testes — **nunca** desabilite segurança em teste.
3. Propagação de token para downstream é config, não código.

### 💻 OIDC no Quarkus

```properties
quarkus.oidc.auth-server-url=https://auth.example.com/realms/orders
quarkus.oidc.client-id=order-service
quarkus.oidc.credentials.secret=${OIDC_CLIENT_SECRET}
quarkus.oidc.tls.verification=required
quarkus.oidc.application-type=service  # microsserviço (bearer token), não web app

# Dev: Dev Services do Keycloak sobe automaticamente
%dev.quarkus.keycloak.devservices.enabled=true
```

### 💻 Protegendo endpoints

```java
@Path("/orders")
@Authenticated
public class OrderResource {

    @Inject SecurityIdentity identity;
    @Inject JsonWebToken jwt;

    @GET
    @RolesAllowed("orders:read")
    @RunOnVirtualThread
    public List<OrderResponse> list() {
        String userId = jwt.getSubject();
        return orderService.findByUser(userId);
    }

    @POST
    @RolesAllowed("orders:write")
    @RunOnVirtualThread
    public Response create(@Valid CreateOrderRequest request) {
        // Zero-trust: verifique que o customerId do request é do próprio user
        if (!request.customerId().equals(jwt.getSubject())) {
            throw new ForbiddenException("Cannot create order for another user");
        }
        return Response.created(...).entity(orderService.create(request)).build();
    }

    @DELETE @Path("/{id}")
    @RolesAllowed("admin")
    @RunOnVirtualThread
    public void delete(@PathParam("id") Long id) {
        orderService.delete(id);
    }
}
```

### 💻 Testando com `@TestSecurity`

```java
@QuarkusTest
class SecuredOrderResourceTest {

    @Test
    @TestSecurity(user = "cust-001", roles = "orders:write")
    void should_create_order_when_authenticated() {
        given().contentType("application/json")
                .body("{\"customerId\":\"cust-001\",\"items\":[...]}")
                .when().post("/orders")
                .then().statusCode(201);
    }

    @Test
    void should_return_401_when_no_token() {
        given().when().post("/orders").then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "cust-002", roles = "orders:write")
    void should_return_403_when_creating_for_another_user() {
        given().contentType("application/json")
                .body("{\"customerId\":\"cust-001\",...}")
                .when().post("/orders")
                .then().statusCode(403);
    }
}
```

### 🚫 Do's and Don'ts

✅ **DO**: `@RolesAllowed`. Zero-trust — compare `jwt.getSubject()` com o recurso.
❌ **DON'T**: role check manual (`if (jwt.hasRole("x"))`). Menos legível, menos testável.

✅ **DO**: `@TestSecurity` em todo teste de endpoint protegido.
❌ **DON'T**: desabilitar segurança em profile de teste.
→ **POR QUÊ**: teste passa, prod quebra. Teste precisa refletir produção.

### 🏗️ PBL Fase 3.3 — Segurança

1. Configure OIDC com Dev Services (Keycloak automático).
2. Proteja todos os endpoints.
3. Testes: 401 sem token, 403 com role errada, 200/201 com role certa.
4. Propague token para o `InventoryRestClient`.

### 📚 Aprofundamento
- Quarkus OIDC: https://quarkus.io/guides/security-oidc-bearer-token-authentication
- `@TestSecurity`: https://quarkus.io/guides/security-testing

---

## Módulo 10 — Cache (quando e como)

### 🎯 80/20

1. `@CacheResult` em consulta por ID que é **read-heavy**. TTL curto.
2. `@CacheInvalidate` quando atualiza.
3. Cache **errado** polui dados e esconde bugs de escrita. Use com disciplina.

```java
@ApplicationScoped
public class OrderService {

    @CacheResult(cacheName = "orders-by-id")
    public OrderResponse findById(Long id) {
        return orderRepository.findByIdWithItems(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @CacheInvalidate(cacheName = "orders-by-id")
    @Transactional
    public void updateStatus(Long id, OrderStatus newStatus) {
        Order order = orderRepository.findByIdOptional(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
        order.status = newStatus;
        order.updatedAt = LocalDateTime.now();
    }
}
```

```properties
quarkus.cache.caffeine."orders-by-id".maximum-size=1000
quarkus.cache.caffeine."orders-by-id".expire-after-write=PT5M
```

**Tabela de quando cachear**:

| Cenário | Cache? | Por quê |
|---|---|---|
| `findById` read-heavy | ✅ | Reduz ida ao banco |
| Listagem com filtros variados | ❌ | Muitas chaves, cache inútil |
| Config/referência (moedas) | ✅ (TTL longo) | Muda raramente |
| Chamada a serviço externo | ✅ (TTL curto) | Reduz latência + protege upstream |
| Dados que mudam em tempo real | ❌ | Cache invalidado imediatamente = zero benefício |

### 🏗️ PBL Fase 3.4 — Cache

1. Adicione `@CacheResult` em `findById`, TTL 5min.
2. Adicione `@CacheInvalidate` em `updateStatus`.
3. **Meça**: `wrk` com 100 req no `GET /orders/{id}` antes e depois. Compare latência p99.

### 📚 Aprofundamento
- Quarkus Cache: https://quarkus.io/guides/cache

---


# 📍 FASE 4 — OPERAÇÃO: Tsuru, GraalVM Native (DEEP), Testes (Semana 4)

---

## Módulo 11 — Tsuru: deploy e operação

> **O Problema**: o serviço precisa ir pra produção. E mais importante — quando algo quebrar, você precisa **saber operar**. Deploy é fácil. Operar é o diferencial.

### 🎯 80/20

Os 5 comandos que você usa todo dia:

```bash
tsuru app-log -a order-service -f               # debug #1
tsuru app-info -a order-service                 # status
tsuru app-deploy-rollback -a order-service      # incidente: reverta PRIMEIRO
tsuru app-restart -a order-service              # solução nuclear, mas funciona
tsuru env-set -a order-service KEY=val          # config
```

### 🧠 Vocabulário

- **App** = seu serviço
- **Unit** = instância (pod)
- **Pool** = cluster/zona
- **Plan** = CPU/memória (ex: `c4m1`)
- **Service** = recurso externo (Postgres, Mongo, Redis) bindado

### 💻 Procfile + health checks

```procfile
# Procfile — o Tsuru lê isto para saber como subir a app
web: java -jar target/quarkus-app/quarkus-run.jar
```

```java
@Liveness
@ApplicationScoped
public class LivenessCheck implements HealthCheck {
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("alive");
    }
}

@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {

    @Inject EntityManager em;
    @Inject MongoClient mongoClient;

    public HealthCheckResponse call() {
        try {
            // Checa Postgres
            em.createNativeQuery("SELECT 1").getSingleResult();
            // Checa Mongo
            mongoClient.getDatabase("admin").runCommand(new Document("ping", 1));
            return HealthCheckResponse.up("dependencies");
        } catch (Exception e) {
            return HealthCheckResponse.down("dependencies");
        }
    }
}
```

> 💡 **Liveness vs Readiness**: `liveness` indica "estou vivo, não me mate". `readiness` indica "estou pronto para receber tráfego". Banco fora? Liveness = UP (não adianta restart), Readiness = DOWN (tire da rotação do LB).

### 🧠 Runbook — 4 cenários operacionais que você vai encarar

**1. OOMKill (pod reiniciando)**
```bash
tsuru app-log -a order-service -l 200 | grep -i "killed\|OOM"
tsuru app-shell -a order-service
# dentro:
free -m
jcmd 1 GC.heap_info
# Fix rápido:
tsuru env-set -a order-service JAVA_OPTS="-Xmx256m -XX:+UseG1GC"
# Fix real: profile heap, achar o vazamento
```

**2. Deploy quebrado**
```bash
tsuru app-log -a order-service -f     # veja o erro
tsuru app-deploy-rollback -a order-service  # ⬅️ FAÇA ISSO PRIMEIRO
# Depois, tranquilo, investigue no branch
```

**3. Latência alta**
```bash
tsuru app-shell -a order-service
jcmd 1 Thread.print | grep -A 5 "BLOCKED"
curl -s localhost:8080/q/metrics | grep agroal   # pool de conexões
curl -s localhost:8080/q/metrics | grep http_server_requests_seconds_sum
# Causas comuns: pool esgotado, N+1, GC pause, serviço downstream lento
```

**4. Scaling**
```bash
# ❌ Errado: escalar sem entender o gargalo
tsuru unit-add 10 -a order-service  # mais instâncias do mesmo problema

# ✅ Certo: diagnosticar primeiro
tsuru app-info -a order-service
# Se CPU saturada → +units
# Se pool de DB saturado → aumentar pool, não units
# Se latência de downstream → resiliência, não escala
```

### 🚫 Do's and Don'ts

✅ **DO**: health checks ANTES do primeiro deploy.
❌ **DON'T**: deploy sem health check — Tsuru marca "sucesso" enquanto app crasha em loop.

✅ **DO**: rollback PRIMEIRO, investigue DEPOIS.
❌ **DON'T**: debugar produção com serviço fora.

✅ **DO**: teste rollback em staging periodicamente.
❌ **DON'T**: descobrir como fazer rollback **durante** incidente.

### 🎚️ Níveis
- 🟢 Deploy, logs, env-set, restart, app-info
- 🟡 Scaling, service-bind, rollback, tuning de health check
- 🔴 Multi-pool strategy, hooks de deploy, autoscaling config, custom platform

### 🏗️ PBL Fase 4.1 — Tsuru

1. Procfile + health checks (liveness + readiness).
2. Deploy em staging.
3. **Simule incidente**: faça deploy com bug de startup → rollback.
4. **Simule latência**: thread dump via `tsuru app-shell`.
5. **Documente runbook pessoal**: 1 página com comandos para os 4 cenários acima.

### 📚 Aprofundamento
- Tsuru docs: https://docs.tsuru.io/
- Quarkus Kubernetes (mental model): https://quarkus.io/guides/deploying-to-kubernetes

---

## Módulo 12 — GraalVM Native Image: entenda o contrato, pague o preço com olhos abertos

> **O Problema**: "vamos compilar nativo, startup instantâneo!" Então o build começa a demorar 8 minutos. Aí o teste passa em JVM e falha em native. Aí a biblioteca X usa reflection. Aí o RSS é realmente menor, mas o throughput cai 20%. **Native é trade-off, não upgrade.**

### 🎯 80/20

1. **Closed-world assumption**: em native, TUDO precisa ser conhecido em build-time. Nada de carregar classe dinamicamente.
2. **O que quebra**: reflection, proxies dinâmicos, serialização, resources não declarados, `ClassLoader.getSystemClassLoader().getResource()`, JNI ad-hoc.
3. **Como arrumar**: `@RegisterForReflection`, `reflect-config.json`, `resource-config.json`, serialization hints.
4. **Quando vale**: cold-start domina (Cloud Run, Lambda, CLI). Pods long-running no Tsuru geralmente preferem JVM + C2 JIT.

### 🧠 O que é GraalVM Native Image

```
┌───────────────────────────────────────────────┐
│  Bytecode Java (.jar)                         │
│                                               │
│  JVM padrão:                                  │
│   - Interpreta + JIT compila em runtime       │
│   - Classes resolvidas dinamicamente          │
│   - Reflection livre                          │
│   - Startup: 2-5s / RAM: 200-400MB            │
└───────────────────────────────────────────────┘
                    │
                    │  native-image (Substrate VM)
                    ▼
┌───────────────────────────────────────────────┐
│  Binário ELF / Mach-O nativo                  │
│                                               │
│  SubstrateVM (VM minimal embutida):           │
│   - AOT compilado (tudo compilado no build)   │
│   - Closed-world: classpath FECHADO em build  │
│   - Reflection PRECISA ser declarada          │
│   - Startup: 20-50ms / RAM: 30-60MB           │
└───────────────────────────────────────────────┘
```

**SubstrateVM** é uma VM mínima que entra **dentro** do binário. Ela sabe apenas sobre as classes que foram analisadas no build (`points-to analysis`). Se seu código chama `Class.forName("com.Foo")` e `Foo` não foi alcançado pela análise estática, `Foo` não está no binário.

### 🧠 O que quebra em native (e como arrumar)

**Reflection**
```java
// ❌ Falha em native: "No such constructor accessible"
Class<?> clazz = Class.forName("com.example.Foo");
Object obj = clazz.getDeclaredConstructor().newInstance();

// ✅ Declare que precisa de reflection
@RegisterForReflection
public class Foo { ... }

// ✅ Ou em reflect-config.json (para libs de terceiros)
```

**Dynamic proxies**
```java
// Proxies dinâmicos: também precisam ser conhecidos no build
// Geralmente, extensões Quarkus já lidam com isto (JPA, REST Client, etc.)
// Para libs fora do Quarkus: src/main/resources/META-INF/native-image/proxy-config.json
```

**Resources**
```java
// ❌ Arquivo não incluso no binário
InputStream in = getClass().getResourceAsStream("/templates/email.html");

// ✅ Declare em resource-config.json ou use a config do Quarkus
// application.properties:
// quarkus.native.resources.includes=templates/**,META-INF/some-data.json
```

**Serialization (Java Serialization, não JSON)**
```java
// ObjectOutputStream / readObject — precisa de hints
// src/main/resources/META-INF/native-image/serialization-config.json
```

**Build-time vs runtime initialization**
```java
// Por default, Quarkus inicializa classes em BUILD-TIME.
// Isso é mais rápido, mas alguns casos precisam ser runtime.
// Ex: classes que leem System.getenv em static initializer — não deve ser build-time.
// Config: --initialize-at-run-time=com.foo.MyClass
```

### 💻 Build nativo — local e CI

```bash
# Build local (precisa de GraalVM instalado OU container build)
quarkus build --native --no-tests -Dquarkus.native.container-build=true

# O `container-build=true` usa um container com GraalVM
# → você não precisa instalar GraalVM localmente
# → o build é reproduzível (mesma imagem em dev e CI)
```

```dockerfile
# Dockerfile.native — multi-stage
FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:22.3-java21 AS build
COPY --chown=quarkus:quarkus . /code
USER quarkus
WORKDIR /code
RUN ./mvnw package -Pnative -DskipTests

FROM quay.io/quarkus/quarkus-micro-image:2.0
WORKDIR /work/
COPY --from=build /code/target/*-runner /work/application
RUN chmod 775 /work
EXPOSE 8080
USER 1001
CMD ["./application", "-Dquarkus.http.host=0.0.0.0"]
```

### 💻 Quando o build nativo quebra

```
Error: Classes that should be initialized at run time got initialized during image building:
  org.example.ProblemClass was unintentionally initialized...
```

```bash
# Em application.properties:
quarkus.native.additional-build-args=\
  --initialize-at-run-time=org.example.ProblemClass,\
  -H:+ReportExceptionStackTraces,\
  -H:ReflectionConfigurationFiles=reflect-config.json
```

### 💻 Debug: o que falta no binário?

```bash
# Execute com tracing (em dev/staging, nunca em prod)
native-image-agent run -jar target/quarkus-app/quarkus-run.jar
# Gera arquivos de config em META-INF/native-image/*.json

# Ou com Quarkus tracing agent
mvn clean package -Pnative \
  -Dquarkus.native.agent-configuration-apply=true
```

### 🧠 Spring Native vs Quarkus Native — quem ganha

| Dimensão | Spring Native (AOT) | Quarkus Native |
|---|---|---|
| Filosofia | Tentativa de AOT sobre framework runtime-first | AOT desde o design |
| Maturidade | GA no Spring Boot 3, mas cada starter tem nuances | Core da proposta do Quarkus desde v1 |
| Starters/extensões | Nem todos têm hints prontos | Extensões Quarkus já trazem hints nativos |
| Build time típico | 5–15min | 3–10min |
| Erros de reflection | Frequentes em libs Spring legadas | Raros (ecossistema menor mas curado) |
| Dev experience | `mvn spring-boot:build-image` | `quarkus build --native` + Dev Services |
| Quando você ganha produtividade | Se já está no Spring e não quer reescrever | Se está começando greenfield |

**Veredito honesto**: Quarkus Native tem menos atrito porque o framework foi desenhado para isso. Spring Native é viável, especialmente no Boot 3+, mas você vai gastar mais tempo caçando hints AOT em dependências.

### 🧠 Decision framework — quando native vale a pena

| Cenário | Native? | JVM? | Razão |
|---|---|---|---|
| Cloud Run / Lambda (cold start domina) | ✅ | | Startup 50ms vs 3s = diferença entre SLA OK e cliente esperando |
| CLI tool | ✅ | | Startup instantâneo é obrigatório |
| Microsserviço long-running no Tsuru/K8s | | ✅ | Throughput pós-warmup > native. JVM JIT otimiza hot paths |
| Ambiente com RAM constrita (edge, IoT) | ✅ | | RSS 10× menor |
| Build time é constraint no CI | | ✅ | Native add 3–10min por build |
| Libs pesadas de reflection (Drools, alguns ORMs) | | ✅ | Muito trabalho de hints |
| Debugging em prod necessário | | ✅ | Native tem debug limitado |

### 🔥 War story — Native em produção errada

Time decidiu "nativo para todos". Migrou 8 serviços. Startup ficou 50ms (de 3s). Deu highfive. 3 semanas depois:
- CI do monorepo ficou 40min (de 8min). 
- Uma lib legada começou a dar `UnsupportedFeatureError` em um fluxo raro de segunda-feira. Reflection não declarada.
- Throughput no serviço de cálculo pesado caiu 15%. C2 JIT otimizava hot path, native não.
- **Reverteram 6 dos 8 serviços para JVM**. Mantiveram native só em 2 (que eram Cloud Functions).

**Lição**: native é ferramenta, não religião. JVM é o padrão. Native é decisão deliberada com métrica.

### 💻 Benchmark metodológico (não chute — meça)

```bash
# 1. Build nativo
quarkus build --native --no-tests -Dquarkus.native.container-build=true

# 2. Startup time
time ./target/order-service-*-runner --quarkus.http.port=8080 &
# Mata quando estiver pronto

# 3. RSS em idle
ps -o rss= -p $(pgrep -f order-service) | awk '{print $1/1024 " MB"}'

# 4. Throughput — warmup + steady state
wrk -t2 -c10 -d30s http://localhost:8080/orders               # JVM warmup
wrk -t4 -c50 -d60s http://localhost:8080/orders               # steady state

# Compare com mesmo procedimento no build JVM
```

### 🚫 Do's and Don'ts

✅ **DO**: profile antes de decidir native. Meça ganho REAL.
❌ **DON'T**: native por hype.

✅ **DO**: `@RegisterForReflection` para DTOs que vêm de/vão para JSON externo se usarem reflection customizada.
❌ **DON'T**: ignorar warnings do native-image agent.

✅ **DO**: `@QuarkusIntegrationTest` para testar no binário nativo (herda testes JVM).
❌ **DON'T**: confiar que "passou em JVM, passa em native".

### 🎚️ Níveis
- 🟢 Entender trade-offs, rodar build nativo local, reconhecer erro típico
- 🟡 Dockerfile multi-stage, resolver reflection com `@RegisterForReflection`, `@QuarkusIntegrationTest`
- 🔴 Profile-guided optimization (PGO), tuning de flags (`-O3`, `-H:...`), integração com native-image-agent em CI

### 🏗️ PBL Fase 4.2 — Native

1. Build nativo do Order Service.
2. Meça: startup, RSS, throughput (wrk). Documente.
3. Force um erro de reflection: crie classe com lookup dinâmico, observe falha em native, arrume com `@RegisterForReflection`.
4. **Decisão documentada**: escreva 1 parágrafo argumentando JVM vs Native para o seu serviço, **com números**.

### 📚 Aprofundamento
- Quarkus Native: https://quarkus.io/guides/building-native-image
- GraalVM reference: https://www.graalvm.org/latest/reference-manual/native-image/
- Substrate VM: https://www.graalvm.org/latest/reference-manual/native-image/basics/
- Tracing agent: https://www.graalvm.org/latest/reference-manual/native-image/metadata/AutomaticMetadataCollection/

---

## Módulo 13 — Testes que passam em code review

> **O Problema**: todo mundo escreve teste. Pouca gente escreve teste que o reviewer sênior aprova sem pedir mudanças. Teste bom é o que fala pelo nome, isola setup, e testa comportamento.

### 🎯 80/20

1. **Nomes**: `should_<resultado>_when_<condição>`. Sempre.
2. **Isolamento**: cada teste independente. Sem ordem implícita.
3. **Testcontainers real**, H2 é enganosa.
4. **Sem `Thread.sleep`**. Use `Awaitility`.

### 💻 `@QuarkusTest` + RestAssured + Testcontainers

```java
@QuarkusTest
class OrderResourceTest {

    @Test
    void should_create_order_and_return_201_with_location_header() {
        given().contentType("application/json")
                .body("""
                    {"customerId":"cust-001","items":[
                        {"productId":"prod-1","quantity":2,"unitPrice":29.90}
                    ]}""")
                .when().post("/orders")
                .then()
                .statusCode(201)
                .header("Location", containsString("/orders/"))
                .body("status", equalTo("PENDING"))
                .body("totalAmount", equalTo(59.80f));
    }

    @Test
    void should_return_404_when_order_not_found() {
        given().when().get("/orders/999999")
                .then()
                .statusCode(404)
                .body("code", equalTo("ORDER_NOT_FOUND"));
    }

    @Test
    void should_return_400_when_customer_id_is_blank() {
        given().contentType("application/json")
                .body("""{"customerId":"","items":[...]}""")
                .when().post("/orders")
                .then()
                .statusCode(400)
                .body("code", equalTo("VALIDATION_FAILED"));
    }
}
```

### 💻 Mockando beans com `@InjectMock`

```java
@QuarkusTest
class OrderServiceTest {

    @InjectMock
    InventoryClient inventoryClient;

    @Inject OrderService orderService;

    @Test
    void should_create_order_when_stock_available() {
        Mockito.when(inventoryClient.checkStock(any(), anyInt()))
                .thenReturn(true);

        OrderResponse response = orderService.create(validRequest());

        assertThat(response.status()).isEqualTo("PENDING");
        Mockito.verify(inventoryClient).checkStock("prod-1", 2);
    }
}
```

### 💻 Testes de resiliência (verifica que o CB realmente abre)

```java
@QuarkusTest
class ResilientInventoryClientTest {

    @InjectMock @RestClient InventoryRestClient inventoryApi;
    @Inject ResilientInventoryClient client;

    @Test
    void should_use_fallback_when_inventory_unavailable() {
        Mockito.when(inventoryApi.checkStock(any(), anyInt()))
                .thenThrow(new WebApplicationException(503));

        StockResponse response = client.checkStock("prod-1", 5);
        assertThat(response.status()).isEqualTo("OPTIMISTIC");
    }

    @Test
    void should_retry_on_transient_failure_then_succeed() {
        Mockito.when(inventoryApi.checkStock(any(), anyInt()))
                .thenThrow(new IOException("connection reset"))   // 1º
                .thenThrow(new IOException("connection reset"))   // 2º
                .thenReturn(new StockResponse(true, 10, "OK"));   // 3º

        StockResponse r = client.checkStock("prod-1", 5);
        assertThat(r.available()).isTrue();
        Mockito.verify(inventoryApi, Mockito.times(3)).checkStock(any(), anyInt());
    }
}
```

### 💻 Testando consumer de Pub/Sub (emulator)

```java
@QuarkusTest
class OrderCreatedConsumerTest {

    @Inject OrderEventPublisher publisher;

    @Test
    void should_process_event_once_even_when_published_multiple_times() {
        OrderCreatedEvent event = new OrderCreatedEvent(42L, "cust-001",
                new BigDecimal("99.90"), LocalDateTime.now());

        publisher.publish(event);
        publisher.publish(event);  // mesmo id interno
        publisher.publish(event);

        // Dá 2s para consumer processar
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            long processed = ProcessedMessage.count();
            assertThat(processed).isEqualTo(1);
        });
    }
}
```

### 💻 `@QuarkusIntegrationTest` — teste em cima do binário nativo

```java
// Herda os testes de OrderResourceTest, mas roda contra o binário nativo
@QuarkusIntegrationTest
class OrderResourceIT extends OrderResourceTest {
}
```

Se falha aqui mas passa no `@QuarkusTest` → é problema de native (reflection, init time, etc.).

### 💻 Performance test com `wrk`

```bash
# Baseline
wrk -t2 -c10 -d30s http://localhost:8080/orders

# Carga realista
wrk -t4 -c100 -d60s http://localhost:8080/orders

# O que olhar:
#   Latency avg/p99 — se p99 > 500ms, investigue
#   Requests/sec — compare build JVM vs native
#   Socket errors — se > 0, pool esgotou ou event loop bloqueou
```

### 🚫 Do's and Don'ts

✅ **DO**: nomes `should_X_when_Y`.
❌ **DON'T**: `test1()`, `testSave()`.
→ **POR QUÊ**: no CI, "test1 FAILED" não ajuda. "should_reject_order_when_stock_is_zero FAILED" conta a história.

✅ **DO**: Testcontainers com Postgres/Mongo reais.
❌ **DON'T**: H2 como substituto de Postgres.
→ **POR QUÊ**: H2 não suporta JSONB, arrays, window functions. Query passa em H2 e falha em prod.

✅ **DO**: `Awaitility.await().untilAsserted(...)` para assíncrono.
❌ **DON'T**: `Thread.sleep(1000)`.
→ **POR QUÊ**: sleep flaky. Awaitility é determinístico, poll + timeout.

### 🎚️ Níveis
- 🟢 `@QuarkusTest` + RestAssured + Testcontainers + mocks simples
- 🟡 `@InjectMock`, Awaitility, teste de consumer Pub/Sub, `@QuarkusIntegrationTest`
- 🔴 Contract tests (Pact), mutation testing (PIT), chaos tests com Toxiproxy

### 🏗️ PBL Fase 4.3 — Testes

1. 5 testes no `OrderResourceTest` — happy path + 400 + 404 + paginação.
2. Teste de resiliência (CB abre, fallback retorna OPTIMISTIC).
3. Teste de consumer com idempotência (3 publish → 1 process).
4. `@QuarkusIntegrationTest` herdando seu `OrderResourceTest`.
5. `wrk` — documente baseline.

### 📚 Aprofundamento
- Quarkus Testing: https://quarkus.io/guides/getting-started-testing
- Testcontainers: https://java.testcontainers.org/
- Awaitility: http://www.awaitility.org/

---

# FASE 5 — ORQUESTRAÇÃO (semana 4, parte 2)

> O trabalho raramente é "uma chamada HTTP". É uma coreografia: valida estoque → talvez aprovação humana → cobra cartão → se falhou, libera estoque → notifica. Quando isso vira `if/else` encadeado em código, ninguém entende o fluxo em 6 meses. Camunda existe para tornar o fluxo **visível, auditável e modificável**.

---

## Módulo 14 — Camunda 7: BPMN como Código Visual

### 🎯 Pareto 80/20

20% que resolve 80% dos casos:

- **BPMN básico**: Start Event, Service Task, User Task, Exclusive Gateway, End Event.
- **JavaDelegate** com `@Named` + `@ApplicationScoped`.
- `BpmnError` (erro de negócio) vs `RuntimeException` (erro técnico / incident).
- **Business Key** = seu `orderId` (para achar a instância depois).
- **Async before** em tasks que chamam serviço externo.
- **Cockpit** para inspecionar instâncias ativas e resolver incidents.

O que fica fora do 80/20 por enquanto: DMN (Decision Model), CMMN, multi-tenancy, Process Instance Migration, Camunda 8 (Zeebe) — aprende depois que dominar o básico.

### 🧠 Modelo Mental: Motor de Estado Persistente

Camunda é um **motor de estado persistente** para fluxos de trabalho. Pense em uma state machine que:

1. Lê um diagrama BPMN (arquivo `.bpmn`, XML).
2. Cria uma **instância** quando você chama `startProcessInstanceByKey(...)`.
3. Avança de nó em nó — **persistindo o estado no Postgres a cada passo** (tabelas `ACT_RU_*`).
4. Quando chega em user task → pausa e espera chamada de API.
5. Quando chega em timer → agenda job, dorme por dias se preciso.
6. Quando um delegate falha → cria **incident** e espera intervenção.
7. Sobrevive a restart da aplicação — o estado está no banco.

**A diferença chave** vs. código puro: o estado do fluxo é **dado no banco**, não variável em memória. Sua aplicação pode reiniciar que o pedido #42 continua exatamente onde parou.

### 🧠 Quando Usar Camunda (e quando NÃO)

| Cenário | Camunda? | Por quê |
|---|---|---|
| Fluxo com aprovação humana | ✅ | Persiste estado, task list pronta |
| Fluxo que muda por semanas/meses | ✅ | Engine guarda, resiste a restart |
| Fluxo com compensação ("desfaz o passo X") | ✅ | BPMN tem compensation events nativos |
| Processo auditável pelo negócio | ✅ | Cockpit mostra cada instância |
| CRUD simples | ❌ | Overhead injustificado |
| Pipeline ETL | ❌ | Airflow/dbt |
| Fire-and-forget | ❌ | Mensageria pura |
| 1-2 ifs | ❌ | Código é melhor |

**Regra de ouro**: se o fluxo cabe em um método de 30 linhas sem espera humana e sem compensação — **não use Camunda**. Se envolve espera, humano, múltiplos serviços com compensação — **Camunda paga**.

### 🧠 Vocabulário Essencial

| Conceito | O que é | Analogia |
|---|---|---|
| **Process Definition** | Blueprint (`.bpmn`) | Classe Java |
| **Process Instance** | Execução ativa | Objeto instanciado |
| **Task** | Etapa do fluxo | Método |
| **Execution** | Ponteiro "onde estou" | Program counter |
| **Variable** | Dado da instância | Variável de escopo |
| **Delegate** | Código Java que executa Service Task | Implementação |
| **Incident** | Falha requer intervenção | Bug em prod |
| **Job** | Trabalho assíncrono | Mensagem em fila |
| **Business Key** | ID legível (`orderId`) | Foreign key semântica |

### 💻 Setup no Quarkus

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.camunda.bpm.quarkus</groupId>
  <artifactId>camunda-bpm-quarkus-engine</artifactId>
  <version>7.18.0</version>
</dependency>
<dependency>
  <groupId>org.camunda.bpm</groupId>
  <artifactId>camunda-engine-cdi</artifactId>
  <version>7.18.0</version>
</dependency>
```

```properties
# application.properties
quarkus.camunda.datasource=default
quarkus.camunda.job-executor.thread-pool.max-pool-size=6
quarkus.camunda.job-executor.thread-pool.queue-size=3
quarkus.camunda.generic-config.history=full
# .bpmn deve estar em src/main/resources/ — é carregado no startup
```

⚠️ **Nota operacional sobre o status da extensão**: a extensão `camunda-bpm-quarkus-engine` **não é parte do core do Camunda**, é community-maintained. Antes de adotar em produção, valide no time: versão usada, suporte, plano de migração para Camunda 8 (Zeebe). Se o time já usa essa stack, você está ok. Se está começando do zero, considere também Spring Boot + Camunda 7 (combo mais maduro) ou já ir para Camunda 8 — é decisão de arquitetura, não sua.

### 💻 BPMN Mínimo Útil — `order-process.bpmn`

Você desenha no **Camunda Modeler** (desktop, gratuito). O resultado é XML. Descreva em texto:

```
[Start] → [Validate Stock (service)] → <gateway: stockAvailable?>
   sim → <gateway: amount > 1000?>
       sim → [Manager Approval (user task)] → [Process Payment (service, async before)]
       não → [Process Payment (service, async before)]
   não → [Notify Out Of Stock] → [End Error]

[Process Payment]
   ok → [Confirm Order] → [End Success]
   erro PAYMENT_FAILED (boundary) → [Release Stock] → [End Error]
```

🔥 **War story**: já vi projeto em que "aprovação de gestor" era implementada com um campo `status='PENDING_APPROVAL'` + cron + endpoint + flags no banco. 800 linhas espalhadas por 5 classes. Migraram para Camunda: **40 linhas de Java** (3 delegates) + 1 user task no BPMN. O PO passou a editar o fluxo **sozinho no Modeler** (adicionar uma 2ª aprovação para valores > R$10k, por exemplo). Valor real.

### 💻 JavaDelegate — a Unidade Básica

```java
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.delegate.BpmnError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named("validateStockDelegate")   // referenciado no BPMN: ${validateStockDelegate}
@ApplicationScoped                // CDI obrigatório
public class ValidateStockDelegate implements JavaDelegate {

    @Inject ResilientInventoryClient inventory;

    @Override
    public void execute(DelegateExecution execution) {
        String productId = (String) execution.getVariable("productId");
        int qty = (int) execution.getVariable("quantity");

        StockResponse r = inventory.checkStock(productId, qty);

        execution.setVariable("stockAvailable", r.available());
        execution.setVariable("stockStatus", r.status());
    }
}
```

```java
@Named("processPaymentDelegate") @ApplicationScoped
public class ProcessPaymentDelegate implements JavaDelegate {

    @Inject PaymentService paymentService;

    @Override
    public void execute(DelegateExecution execution) {
        String orderId = (String) execution.getVariable("orderId");
        BigDecimal amount = (BigDecimal) execution.getVariable("amount");

        try {
            PaymentResult r = paymentService.charge(orderId, amount);
            execution.setVariable("paymentId", r.transactionId());
        } catch (InsufficientFundsException e) {
            // BpmnError → engine NÃO cria incident. Fluxo desvia via Error Boundary.
            throw new BpmnError("PAYMENT_FAILED", "No funds: " + orderId);
        }
        // Outra exceção → incident → fica parado esperando retry manual no Cockpit.
    }
}
```

### 😱 `BpmnError` vs `RuntimeException` — A Distinção Mais Importante

| Tipo de falha | Lance | Comportamento |
|---|---|---|
| Negócio esperado (cartão sem saldo, CEP inválido, estoque zero) | `BpmnError("CODE")` | Engine segue caminho alternativo definido no BPMN. Sem incident. |
| Técnico inesperado (timeout, 500 do serviço, NPE) | `RuntimeException` | Engine cria **incident**, pausa instância, espera humano no Cockpit. |

🔥 **War story**: time lançava `RuntimeException` para "cartão recusado". Resultado: **3.000 incidents** acumulados no Cockpit em 1 semana (3k pedidos legítimos com cartão sem saldo). Time de ops afogado. Fix: trocar para `BpmnError("CARD_DECLINED")` e adicionar Error Boundary com fluxo "Notificar cliente". Zero incidents, fluxo normal de negócio.

### 😱 Erros Clássicos

- **Delegate sem `@ApplicationScoped`** → `"No bean found for ${xxx}"`. CDI precisa do escopo.
- **Gateway sem default flow** → se nenhuma condição for `true`, engine cria incident. Sempre marque um flow como default.
- **Armazenar entidade JPA em process variable** → `ClassNotFoundException` após redeploy se classe mudou. Use **JSON string** ou tipos primitivos.
- **Chamar HTTP sem timeout dentro de delegate** → bloqueia job executor. Sempre componha com `@Timeout` do Fault Tolerance.
- **Chain longa sem `async before`** → uma falha no meio faz rollback de tudo.

### 💻 Iniciando um Processo a partir do seu Serviço

```java
@ApplicationScoped
public class OrderProcessStarter {

    @Inject RuntimeService runtimeService;

    public String start(OrderCreatedEvent e) {
        Map<String, Object> vars = Map.of(
            "orderId",    e.orderId(),
            "customerId", e.customerId(),
            "amount",     e.totalAmount(),
            "productId",  e.productId(),
            "quantity",   e.quantity()
        );

        ProcessInstance inst = runtimeService.startProcessInstanceByKey(
            "order-process",                  // ID do process definition no .bpmn
            String.valueOf(e.orderId()),      // Business Key
            vars
        );

        return inst.getId();
    }
}
```

🧠 **Business Key é ouro**: use o `orderId` (ou equivalente do domínio). Torna trivial achar a instância sem saber o UUID do Camunda: `createProcessInstanceQuery().processInstanceBusinessKey("42").singleResult()`.

### 💻 User Task — Quando o Humano Entra no Fluxo

```java
@Path("/orders/{id}")
@ApplicationScoped
public class OrderApprovalResource {

    @Inject TaskService taskService;

    @POST @Path("/approve")
    @RolesAllowed("manager")
    public Response approve(@PathParam("id") long id,
                            @Context SecurityIdentity identity,
                            ApprovalRequest req) {
        Task task = taskService.createTaskQuery()
            .processInstanceBusinessKey(String.valueOf(id))
            .taskDefinitionKey("manager-approval")
            .singleResult();

        if (task == null) return Response.status(404).build();

        taskService.claim(task.getId(), identity.getPrincipal().getName());
        taskService.complete(task.getId(), Map.of("approved", req.approved()));
        return Response.accepted().build();
    }
}
```

No BPMN, o Exclusive Gateway após a user task avalia `${approved == true}` e decide o caminho.

### 💻 Interação com Transactional Outbox

Você já construiu o Outbox para publicar eventos confiáveis (Módulo 4). **Camunda combina bem**:

```
REST POST /orders
    ↓ (transação JPA)
  OrderService.create()
    ├─→ salva Order(PENDING) no orders
    ├─→ salva OrderCreatedEvent no outbox_events
    ↓ commit
    (retorna 202 para o cliente)

[poller a cada 5s]
    ↓
  Publisher lê outbox → publica no Pub/Sub

[consumer do próprio serviço ou dedicated]
    ↓ OrderCreatedEvent
  OrderProcessStarter.start(event)
    → runtimeService.startProcessInstanceByKey("order-process", ...)
```

Isso evita o anti-pattern de iniciar o processo Camunda **dentro** da transação da criação do Order. Se o `startProcessInstanceByKey` falhar, você fica com Order criado mas sem processo — ou, pior, com Outbox event duplicado se a transação reverter.

🧠 **Princípio**: **quem é dono do dado, dono é da transação**. Order é da sua tabela. Processo é da engine. Ligue-os via evento — não via transação compartilhada, mesmo que tecnicamente possível (ambos no mesmo Postgres).

### 💻 Async Before — Isolamento de Falhas

No Modeler, selecione uma service task que chama serviço externo → Properties → **Asynchronous Before** ✅.

O que muda: a engine **faz commit** antes da task, cria um **job** na tabela `ACT_RU_JOB`, e o **job executor** pega o job em outra transação para executar o delegate.

Benefícios:
- Falha no delegate = retry isolado (não rola back passos anteriores).
- Permite paralelização (se não é sequencial dependente).
- Job executor pode ser escalado independentemente.

Regra: **toda service task que chama serviço externo (HTTP, Pub/Sub, gRPC) → async before**.

### 💻 External Task Pattern — Alternativa Desacoplada

Ao invés do JavaDelegate (engine empurra para seu código), o worker **puxa** tarefas da engine:

```java
@ApplicationScoped
public class PaymentWorker {

    @Inject ExternalTaskService ext;

    @Scheduled(every = "5s")
    void poll() {
        List<LockedExternalTask> tasks = ext.fetchAndLock(10, "payment-worker")
            .topic("process-payment", 30_000L)   // lock por 30s
            .execute();

        for (var t : tasks) {
            try {
                var r = paymentService.charge(
                    (String) t.getVariable("orderId"),
                    (BigDecimal) t.getVariable("amount"));
                ext.complete(t.getId(), "payment-worker", Map.of("paymentId", r.id()));
            } catch (InsufficientFundsException e) {
                ext.handleBpmnError(t.getId(), "payment-worker", "PAYMENT_FAILED");
            } catch (Exception e) {
                ext.handleFailure(t.getId(), "payment-worker", e.getMessage(), 2, 5_000L);
            }
        }
    }
}
```

| Critério | JavaDelegate | External Task |
|---|---|---|
| Simplicidade | ✅ | Mais código |
| Mesma transação da engine | ✅ | Não |
| Worker escala separado | Não | ✅ |
| Linguagem diferente do core | Não | ✅ |
| Afinidade com Camunda 8 (Zeebe) | — | ✅ padrão nativo no C8 |

🎚️ **Decisão**: Camunda 7, time unificado em Java, sem plano de migração para C8 → **JavaDelegate**. Heterogêneo (Go, Python), ou migração para C8 no horizonte → **External Task**.

### 💻 Cockpit — a Ponte Entre Você e Operação

```xml
<dependency>
  <groupId>org.camunda.bpm</groupId>
  <artifactId>camunda-webapp-webjar</artifactId>
  <version>7.18.0</version>
</dependency>
```

Acesse em `http://localhost:8080/camunda/app/cockpit/`.

Você vê:
- Instâncias ativas, **onde estão no diagrama** (token overlay).
- **Incidents**: stack trace, retry via botão.
- Histórico de instâncias fechadas.
- Variáveis por instância.

```java
// Automação de retry em bulk
List<Incident> incidents = runtimeService.createIncidentQuery()
    .processDefinitionKey("order-process")
    .list();

for (var i : incidents) {
    managementService.setJobRetries(i.getConfiguration(), 1);
}
```

### 💻 Testando Processos End-to-End

```java
@QuarkusTest
class OrderProcessTest {

    @Inject RuntimeService runtimeService;
    @Inject HistoryService historyService;
    @Inject TaskService taskService;

    @InjectMock @RestClient InventoryRestClient inventoryApi;
    @InjectMock PaymentService paymentService;

    @Test
    void should_complete_when_amount_below_threshold_and_payment_ok() {
        when(inventoryApi.checkStock(any(), anyInt()))
            .thenReturn(new StockResponse(true, 10, "OK"));
        when(paymentService.charge(any(), any()))
            .thenReturn(new PaymentResult("txn-1", "SUCCESS"));

        ProcessInstance inst = runtimeService.startProcessInstanceByKey(
            "order-process", "order-42",
            Map.of("orderId", 42L, "amount", new BigDecimal("99.90"),
                   "productId", "p1", "quantity", 2));

        HistoricProcessInstance h = historyService.createHistoricProcessInstanceQuery()
            .processInstanceId(inst.getId()).singleResult();

        assertThat(h.getEndActivityId()).isEqualTo("end-success");
    }

    @Test
    void should_wait_on_approval_when_amount_above_threshold() {
        when(inventoryApi.checkStock(any(), anyInt()))
            .thenReturn(new StockResponse(true, 10, "OK"));

        runtimeService.startProcessInstanceByKey("order-process", "order-99",
            Map.of("orderId", 99L, "amount", new BigDecimal("5000.00"),
                   "productId", "p1", "quantity", 1));

        Task task = taskService.createTaskQuery()
            .processInstanceBusinessKey("order-99").singleResult();

        assertThat(task).isNotNull();
        assertThat(task.getTaskDefinitionKey()).isEqualTo("manager-approval");
    }
}
```

**Filosofia**: teste o **fluxo**, não delegates isolados. O valor do Camunda é a orquestração; teste que o BPMN + delegates + gateways executam o cenário certo.

### 🚫 Do's and Don'ts

✅ **DO**: `BpmnError` para erros de negócio.
❌ **DON'T**: `RuntimeException` para "cartão recusado".
→ **POR QUÊ**: incidents explodem, operação afogada. Erros de negócio devem ter **caminho alternativo no BPMN**.

✅ **DO**: Business Key = ID do domínio (`orderId`).
❌ **DON'T**: Iniciar sem Business Key.
→ **POR QUÊ**: achar instância vira query complicada. Business Key é FK semântica.

✅ **DO**: Variáveis = primitivos ou JSON String.
❌ **DON'T**: `execution.setVariable("order", orderEntity)`.
→ **POR QUÊ**: classes mudam, `ClassNotFoundException` após redeploy. JSON sobrevive.

✅ **DO**: Async before em toda chamada externa.
❌ **DON'T**: Cadeia síncrona de 5 tasks com I/O.
→ **POR QUÊ**: falha no meio faz rollback de tudo; sem retry granular.

✅ **DO**: Default flow em todo Exclusive Gateway.
❌ **DON'T**: Gateway dependente só de condições explícitas.
→ **POR QUÊ**: valor inesperado → nenhuma condição `true` → incident.

✅ **DO**: Testar fluxo completo com delegates reais + mocks externos.
❌ **DON'T**: Apenas teste unitário de delegate.
→ **POR QUÊ**: o valor do Camunda é orquestração; teste orquestração.

### 🎚️ Níveis
- 🟢 Desenhar BPMN simples, criar delegates, iniciar processo, completar user task, ler no Cockpit
- 🟡 Error boundary, async before, External Task, Transactional Outbox → Camunda, testes end-to-end
- 🔴 Process Instance Migration, DMN, listener de eventos customizado, métricas da engine no Grafana

### 🏗️ PBL Fase 5 — Order Approval com Camunda

**Contexto**: PO quer que pedidos acima de R$1.000 passem por aprovação do gestor antes do pagamento. Em código puro, isso é scheduler + flag + endpoint + retry. Em Camunda, é um bloco no diagrama.

**Entregável**:

1. Adicione dependências Camunda + webapp + CDI no `pom.xml`.
2. Configure `application.properties` (datasource compartilhado, job executor, history=full).
3. Desenhe `order-process.bpmn` no Modeler conforme a descrição acima.
4. Implemente 4 delegates: `ValidateStockDelegate`, `ProcessPaymentDelegate` (com `BpmnError` em falha de pagamento), `ConfirmOrderDelegate`, `ReleaseStockDelegate`.
5. Marque `process-payment` e `validate-stock` como **async before**.
6. Integre: consumer de `OrderCreatedEvent` chama `OrderProcessStarter.start(event)`.
7. Endpoint `POST /orders/{id}/approve` completa a user task (role `manager`).
8. Teste 3 cenários:
   - Pedido R$500 → passa direto, completa com sucesso.
   - Pedido R$2.000 aprovado → paga e confirma.
   - Pedido R$2.000 rejeitado → libera estoque, termina erro.
   - Bonus: pedido com `InsufficientFundsException` → `BpmnError` → libera estoque.
9. Abra o Cockpit e encontre uma instância parada na user task. Inspecione variáveis.

**Critério de sucesso**: o PO consegue abrir o `.bpmn` no Modeler e entender o fluxo. Adicionar uma segunda aprovação (para pedidos > R$10k) custa 1 bloco novo + 1 delegate.

### 📚 Aprofundamento
- Camunda 7 Docs: https://docs.camunda.org/manual/7.18/
- Quarkus Integration: https://docs.camunda.org/manual/7.18/user-guide/quarkus-integration/
- BPMN 2.0 Reference: https://docs.camunda.org/manual/7.18/reference/bpmn20/
- Modeler download: https://camunda.com/download/modeler/
- Camunda Best Practices: https://camunda.com/best-practices/
- Exemplos: https://github.com/camunda/camunda-bpm-examples

---

## Módulo 15 — Projeto Integrador: Costurando Tudo

### 🎯 Pareto da Integração

Você chegou aqui. Agora o objetivo é **ter um único repositório** que exercita todas as peças. O projeto é o mesmo **Order Service** que foi crescendo desde o Módulo 2.

### 🧠 Arquitetura de Referência (ASCII)

```
                              ┌─────────────────────┐
                              │   Browser / Client  │
                              └──────────┬──────────┘
                                         │ HTTPS + JWT (Bearer)
                                         ▼
                           ┌───────────────────────────────┐
                           │   Order Service (Quarkus)     │
                           │                               │
    ┌─────────┐            │  ┌─────────────────────────┐  │         ┌───────────────┐
    │  OIDC   │◄──────────►│  │  REST + gRPC layer      │  │◄───────►│  Inventory    │
    │(Keycloak│            │  │  (OrderResource, gRPC)  │  │  REST   │   Service     │
    │ or GCP) │            │  └──────────┬──────────────┘  │         └───────────────┘
    └─────────┘            │             │                 │
                           │  ┌──────────▼──────────────┐  │         ┌───────────────┐
                           │  │  Application Services   │  │◄───────►│  Payment      │
                           │  │  (OrderService)         │  │  gRPC   │   Service     │
                           │  │  + Fault Tolerance      │  │         └───────────────┘
                           │  │  + Cache                │  │
                           │  └───┬────────────┬────────┘  │
                           │      │            │           │
                           │      ▼            ▼           │
                           │  ┌────────┐  ┌──────────┐     │
                           │  │Postgres│  │ MongoDB  │     │
                           │  │(orders,│  │(products)│     │
                           │  │ outbox,│  │          │     │
                           │  │ camunda│  │          │     │
                           │  │ tables)│  │          │     │
                           │  └────────┘  └──────────┘     │
                           │                               │
                           │  ┌─────────────────────────┐  │         ┌───────────────┐
                           │  │  Outbox Poller (5s)     │──┼────────►│   Pub/Sub     │
                           │  └─────────────────────────┘  │ publish │(order-events) │
                           │                               │         └───────┬───────┘
                           │  ┌─────────────────────────┐  │                 │ subscribe
                           │  │  Pub/Sub Consumer       │◄─┼─────────────────┘
                           │  │  (payment-events)       │  │
                           │  └────────────┬────────────┘  │
                           │               │               │
                           │               ▼               │
                           │  ┌─────────────────────────┐  │
                           │  │  Camunda Engine         │  │
                           │  │  (order-process.bpmn)   │  │
                           │  └─────────────────────────┘  │
                           └───────────────┬───────────────┘
                                           │
                                           │ OTel OTLP + Prometheus + JSON logs
                                           ▼
                        ┌───────────────────────────────────────┐
                        │    Grafana + Tempo + Loki + Prom      │
                        └───────────────────────────────────────┘
```

### 💻 Code Review Checklist Final

Use isto como filtro antes de abrir PR. Se falhar em qualquer item, refatore.

**Estrutura**
- [ ] Beans com escopo explícito (`@ApplicationScoped`), não `@Singleton` por padrão.
- [ ] DTOs e entidades separados. Entidade não sai da camada de persistência.
- [ ] `record` para DTOs imutáveis.
- [ ] Package por feature (`orders/`, `payments/`), não por camada (`controllers/`, `services/`).

**REST / gRPC**
- [ ] Endpoints retornam `Response` com status correto (201 em POST, 204 em DELETE, 404 quando não existe).
- [ ] `ExceptionMapper<T>` para `EntityNotFound`, `ValidationException`.
- [ ] Bean Validation (`@Valid`, `@NotBlank`, `@Min`).
- [ ] REST Client com `@Timeout` + `@Retry` + `@CircuitBreaker`.
- [ ] gRPC com `Deadline` no client.

**Persistência**
- [ ] Flyway controla schema. Nunca `hibernate.hbm2ddl=update` em prod.
- [ ] `@Transactional` nos métodos de escrita, não na classe inteira.
- [ ] Queries com JOIN FETCH ou `@EntityGraph` — sem N+1.
- [ ] Para Mongo: índice declarado no `@Indexed`/migration, não assumido.
- [ ] Sem `findAll()` sem paginação.

**Assíncrono / Eventos**
- [ ] Publicação em Pub/Sub via Transactional Outbox — não direto no service.
- [ ] Consumer idempotente (tabela `processed_events` com `messageId` único).
- [ ] DLQ configurada na Subscription + alerta `gcp.pubsub.dead_letter_messages_total > 0`.
- [ ] Consumer usa `@RunOnVirtualThread` se o trabalho é blocking.

**Resiliência**
- [ ] Stack completo: Bulkhead + Timeout + Retry + CircuitBreaker + Fallback.
- [ ] Fallback retorna **resposta degradada válida** (não throw).
- [ ] `@Retry` só para operações idempotentes.

**Observabilidade**
- [ ] Logs JSON com `traceId`, `spanId`, `orderId` via MDC.
- [ ] Métricas RED em endpoints e dependências externas (Timer + Counter).
- [ ] Dashboard Grafana com painéis RPS, latência p95/p99, erro %, saturação (pool).
- [ ] SLO definido (ex: p95 < 200ms, 99.5% disponibilidade) com alerta em 50% de Error Budget.

**Segurança**
- [ ] Endpoints protegidos com `@RolesAllowed`.
- [ ] Nada de secrets em código; só via env var / secret manager.
- [ ] Input validado (Bean Validation + whitelist de enums).

**Operação**
- [ ] `/q/health/live` (processo vivo) e `/q/health/ready` (depedências ok).
- [ ] `quarkus.shutdown.timeout=30s` para drain de requests em flight.
- [ ] Limites explícitos: `-Xmx`, pool, worker, job executor.

**Camunda (se aplicável)**
- [ ] Delegates com `@Named` + `@ApplicationScoped`.
- [ ] `BpmnError` para negócio, exceção para técnico.
- [ ] Business Key = ID do domínio.
- [ ] Async before em service task externa.
- [ ] Default flow em todo gateway.

**Testes**
- [ ] `@QuarkusTest` para resource + service.
- [ ] Testcontainers para Postgres/Mongo (não H2).
- [ ] Teste de resiliência (CB abre, fallback funciona).
- [ ] Pelo menos 1 `@QuarkusIntegrationTest` (roda contra binário JVM ou native).

### 💡 Insight Final Sobre Qualidade

**A diferença entre sênior e pleno não é saber mais APIs**. É saber **o que não fazer** e **qual a pergunta certa** antes de codar:

- "Esse retry é idempotente?" antes de adicionar `@Retry`.
- "Esse bean precisa de estado?" antes de escolher escopo.
- "Esse cache tem invalidação correlata?" antes de `@CacheResult`.
- "Esse processo precisa ser auditável pelo negócio?" antes de escolher Camunda ou código.
- "O que acontece se 100 requests chegarem ao mesmo tempo?" antes de mergear.

O código cresce; as perguntas não. Domine as perguntas.


---

# APÊNDICE A — `application.properties` Completo

> Ponto de partida. Copie, remova o que não usa, e customize. Secrets sempre via variável de ambiente — nunca commitados.

```properties
# ============================================================
#  ORDER SERVICE — application.properties
# ============================================================

# ---- Aplicação ----
quarkus.application.name=order-service
quarkus.application.version=1.0.0

# ---- HTTP ----
quarkus.http.port=8080
quarkus.http.cors=true
quarkus.http.cors.origins=${CORS_ORIGINS:http://localhost:3000}

# ---- Graceful Shutdown (drain de requests em flight) ----
quarkus.shutdown.timeout=30s

# ============================================================
#  PERSISTÊNCIA — Postgres (pedidos, outbox, camunda)
# ============================================================
quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${DB_USERNAME:orders_app}
quarkus.datasource.password=${DB_PASSWORD:orders_dev_pwd}
quarkus.datasource.jdbc.url=jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:orders}

# Pool Agroal — dimensione baseado em métricas, não chute
quarkus.datasource.jdbc.min-size=5
quarkus.datasource.jdbc.max-size=20
quarkus.datasource.jdbc.idle-removal-interval=PT5M
quarkus.datasource.jdbc.max-lifetime=PT30M
quarkus.datasource.jdbc.acquisition-timeout=PT5S

# Hibernate — schema gerenciado pelo Flyway em prod
quarkus.hibernate-orm.database.generation=none
%dev.quarkus.hibernate-orm.log.sql=true
%test.quarkus.hibernate-orm.log.sql=true

# Flyway
quarkus.flyway.migrate-at-start=true
quarkus.flyway.locations=db/migration
quarkus.flyway.baseline-on-migrate=true

# ============================================================
#  PERSISTÊNCIA — MongoDB (products, catálogo)
# ============================================================
quarkus.mongodb.connection-string=${MONGO_URI:mongodb://localhost:27017}
quarkus.mongodb.database=${MONGO_DB:products}
quarkus.mongodb.credentials.username=${MONGO_USERNAME:}
quarkus.mongodb.credentials.password=${MONGO_PASSWORD:}
quarkus.mongodb.max-pool-size=50
quarkus.mongodb.min-pool-size=5
quarkus.mongodb.read-preference=primaryPreferred

# Dev services — Mongo em container no `quarkus dev`
%dev.quarkus.mongodb.devservices.enabled=true

# ============================================================
#  REST CLIENT — Serviços externos
# ============================================================
quarkus.rest-client.inventory-api.url=${INVENTORY_SERVICE_URL:http://localhost:8081}
quarkus.rest-client.inventory-api.scope=jakarta.inject.Singleton
quarkus.rest-client.inventory-api.connect-timeout=2000
quarkus.rest-client.inventory-api.read-timeout=3000

# ============================================================
#  gRPC — client e server
# ============================================================
quarkus.grpc.server.port=9000

quarkus.grpc.clients.payment.host=${PAYMENT_GRPC_HOST:localhost}
quarkus.grpc.clients.payment.port=9001
quarkus.grpc.clients.payment.deadline=5s

# ============================================================
#  GCP PUB/SUB
# ============================================================
gcp.project-id=${GCP_PROJECT_ID:local-project}

# Emulator em dev — detecta via PUBSUB_EMULATOR_HOST=localhost:8085
%dev.quarkus.google.cloud.pubsub.devservice.enabled=true

# Topic/subscription names (referenciados no código)
pubsub.topic.order-events=order-events
pubsub.topic.order-events-dlq=order-events-dlq
pubsub.subscription.payment-events=payment-events-sub
# ADC (Application Default Credentials) em prod:
# GOOGLE_APPLICATION_CREDENTIALS=/var/secrets/gcp/sa.json
# Ou via Workload Identity no GKE/Tsuru

# Outbox poller
outbox.poll-interval=5s
outbox.batch-size=100

# ============================================================
#  SECURITY — OIDC / JWT
# ============================================================
quarkus.oidc.auth-server-url=${OIDC_AUTH_SERVER_URL:http://localhost:8180/realms/orders}
quarkus.oidc.client-id=${OIDC_CLIENT_ID:order-service}
quarkus.oidc.credentials.secret=${OIDC_CLIENT_SECRET:secret}
quarkus.oidc.application-type=service
quarkus.oidc.tls.verification=required

# Dev services — sobe Keycloak automaticamente em `quarkus dev`
%dev.quarkus.keycloak.devservices.enabled=true

# ============================================================
#  FAULT TOLERANCE
# ============================================================
# Globais; anotações no código sobrescrevem
MP_Fault_Tolerance_NonFallback_Enabled=true
# Métricas: habilitadas por padrão via quarkus-micrometer

# ============================================================
#  CACHE (Caffeine)
# ============================================================
quarkus.cache.caffeine."orders-by-id".maximum-size=1000
quarkus.cache.caffeine."orders-by-id".expire-after-write=PT5M

quarkus.cache.caffeine."products-by-sku".maximum-size=5000
quarkus.cache.caffeine."products-by-sku".expire-after-write=PT10M

# ============================================================
#  OBSERVABILIDADE — OpenTelemetry
# ============================================================
quarkus.otel.service.name=order-service
quarkus.otel.resource.attributes=service.namespace=orders,deployment.environment=${ENV:dev}
quarkus.otel.exporter.otlp.endpoint=${OTEL_ENDPOINT:http://localhost:4317}
quarkus.otel.exporter.otlp.protocol=grpc

# Sampling — 10% em prod, 100% em dev
quarkus.otel.traces.sampler=parentbased_traceratio
quarkus.otel.traces.sampler.arg=0.1
%dev.quarkus.otel.traces.sampler.arg=1.0

# ============================================================
#  OBSERVABILIDADE — Métricas (Micrometer → Prometheus)
# ============================================================
# Endpoint: GET /q/metrics
quarkus.micrometer.export.prometheus.enabled=true
quarkus.micrometer.binder.jvm=true
quarkus.micrometer.binder.system=true
quarkus.micrometer.binder.http-server.enabled=true
quarkus.micrometer.binder.http-server.match-patterns=/orders/.*

# ============================================================
#  OBSERVABILIDADE — Logs JSON
# ============================================================
quarkus.log.console.json=true
quarkus.log.console.json.additional-field."service".value=order-service
quarkus.log.console.json.additional-field."environment".value=${ENV:dev}
quarkus.log.console.json.additional-field."version".value=${APP_VERSION:unknown}
# Mantém log humano em dev, JSON em prod/staging
%dev.quarkus.log.console.json=false
quarkus.log.category."com.example".level=INFO
%prod.quarkus.log.category."org.hibernate.SQL".level=WARN

# ============================================================
#  HEALTH CHECKS
# ============================================================
# Endpoints automáticos:
#   /q/health/live   — liveness (processo vivo)
#   /q/health/ready  — readiness (depedências ok)
#   /q/health        — agregado
# Health checks padrões incluem datasource, mongo, grpc, pubsub
# Customizados com @Liveness / @Readiness

# ============================================================
#  CAMUNDA 7
# ============================================================
quarkus.camunda.datasource=default
quarkus.camunda.job-executor.thread-pool.max-pool-size=6
quarkus.camunda.job-executor.thread-pool.queue-size=3
quarkus.camunda.generic-config.history=full
# Webapp (Cockpit) em /camunda

# ============================================================
#  APP CONFIG
# ============================================================
app.order.max-items=50
app.order.default-currency=BRL
app.order.approval-threshold=1000.00
app.order.retry.max-attempts=3

# ============================================================
#  PROFILES ESPECÍFICOS
# ============================================================
%test.quarkus.datasource.devservices.enabled=true
%test.quarkus.mongodb.devservices.enabled=true
%test.quarkus.flyway.migrate-at-start=true
```

---

# APÊNDICE B — Troubleshooting: Erro → Causa → Fix

> Esse apêndice é o seu 80/20 de debug. Tenha aberto no segundo monitor.

### Startup Failures

| Erro | Causa | Fix |
|---|---|---|
| `UnsatisfiedResolutionException: no bean found for type X` | Bean sem escopo | Adicione `@ApplicationScoped` |
| `DeploymentException: no matching config property for app.x` | `@ConfigProperty` sem valor e sem default | Adicione em `application.properties` ou default com `defaultValue=` |
| `FlywayValidateException: checksum mismatch` | Migration alterada após aplicada | **Nunca altere migration aplicada**. Crie V2, V3... |
| `Connection refused: localhost:5432` | Postgres fora | `quarkus dev` = Dev Services. Prod: verifique `DB_HOST`, VPC |
| `Port 8080 already in use` | Outro processo na porta | `lsof -i :8080 \| grep LISTEN` e mate |
| Camunda: `BPMN XML not parseable` | `.bpmn` corrompido ou diagrama incompleto | Abra no Modeler, valide, salve de novo |
| `MongoSecurityException: authentication failed` | User/pass errados ou `authSource` ausente | Verifique `connection-string`. Adicione `?authSource=admin` se preciso |

### Runtime Errors — Quarkus / CDI / JPA

| Erro | Causa | Fix |
|---|---|---|
| `NullPointerException` em bean injetado | `@Singleton` sem proxy ou bean sem escopo | `@ApplicationScoped` |
| `@Transactional` "não funciona" (dados inconsistentes) | Bean é `@Singleton` — não-proxied | Troque para `@ApplicationScoped` |
| `BlockedThreadChecker: thread blocked for 2000ms` | I/O bloqueante na event loop | `@RunOnVirtualThread` ou `@Blocking` |
| `LazyInitializationException` | Acesso lazy fora da transação | `JOIN FETCH` ou `@EntityGraph` |
| `AgroalConnectionPoolExhaustedException` | Pool esgotado | Métricas de pool → aumente `max-size` ou elimine queries lentas |
| `CircuitBreakerOpenException` | CB aberto | Verifique fallback; investigue downstream |
| `TimeoutException` em REST Client | Serviço externo lento ou offline | Confirme `@Timeout` + investigue downstream |

### Runtime Errors — MongoDB

| Erro | Causa | Fix |
|---|---|---|
| Query lenta (> 500ms) | Falta índice ou índice não usado | `db.collection.explain("executionStats").find(...)` — procure `COLLSCAN` |
| `DuplicateKeyException: E11000` | Violação de índice único | Verifique se deve ser único; ou use `upsert` |
| Docs "sumiram" após update | Usou `replaceOne` ao invés de `updateOne` + `$set` | Sempre prefira operadores (`$set`, `$inc`, `$push`) |
| Write timeout em replica set | Write concern `majority` sem quórum | Verifique estado dos nodes |

### Runtime Errors — Pub/Sub

| Erro | Causa | Fix |
|---|---|---|
| Consumer "não consome" | Subscription errada, ou ack prematuro | Verifique nome da subscription; não dê ack antes do processo terminar |
| Mensagens duplicadas | At-least-once normal; faltou idempotência | Implemente `processed_events` com `messageId` único |
| Mensagens empilhando (backlog cresce) | Consumer lento ou quebrado | Métrica `num_undelivered_messages` → escala consumer ou conserta bug |
| `PERMISSION_DENIED` | SA sem role `pubsub.subscriber`/`pubsub.publisher` | IAM: conceda role correta |
| Emulator ignorado em dev | `PUBSUB_EMULATOR_HOST` não setado | `export PUBSUB_EMULATOR_HOST=localhost:8085` |

### Runtime Errors — Camunda

| Sintoma | Causa | Fix |
|---|---|---|
| `No bean found for ${xxx}` | Delegate sem `@Named` / sem escopo | Adicione `@Named("xxx") @ApplicationScoped` |
| Incident em service task | Exceção não tratada | Cockpit → inspecione stack → fix + retry |
| Gateway sem saída (incident) | Nenhuma condição `true`, sem default | Marque sequence flow como default |
| `ClassNotFoundException` em variável | Entidade JPA armazenada; classe mudou | Use JSON String ou primitivos |
| Processo "trava" em user task | Normal — aguarda complete | `POST /orders/{id}/approve` |
| Jobs não executam | Job executor travado / config | Verifique `max-pool-size`, logs do engine |

### Native Build (GraalVM)

| Erro | Causa | Fix |
|---|---|---|
| `ClassNotFoundException` em runtime | Reflection não registrada | `@RegisterForReflection` na classe |
| `UnsupportedFeatureError: Proxy class` | Dynamic proxy faltando | Verifique se a extensão suporta native; ou use `native-image.properties` |
| `NoSuchFileException: META-INF/...` | Resource não incluído | `quarkus.native.resources.includes=META-INF/**/*.xml` |
| `No instances of X are allowed in the image heap` | Objeto inicializado em build-time quando deveria ser runtime | `--initialize-at-run-time=<class>` |
| Build demora > 15min / OOM | Muita reflection + memória default | `-J-Xmx6g` e aceite CI long-running |
| Tempo de startup "só" 500ms | Configurou alguma inicialização eager | Mova para `@Startup`-aware ou lazy |

### Runtime Errors — Grafana / Prometheus / Loki

| Erro | Causa | Fix |
|---|---|---|
| Dashboard vazio (sem dados) | Prometheus não scrapeia endpoint | Verifique `scrape_configs` e `/q/metrics` funciona |
| Traces não aparecem em Tempo | OTel endpoint errado ou sampler=0 | `quarkus.otel.exporter.otlp.endpoint` e sampler |
| Logs não aparecem em Loki | Promtail/Fluent Bit config errado | Verifique labels e path dos logs |
| Loki query lenta | LogQL com regex pesado ou cardinalidade alta | Restrinja janela temporal; use labels antes de regex |
| TraceId em logs ≠ Tempo | OTel log correlation desligada | `quarkus.otel.logs.enabled=true` |

### Tsuru (deploy / runtime)

| Sintoma | Causa provável | Ação |
|---|---|---|
| Pod em `CrashLoopBackOff` | OOMKill ou startup crash | `tsuru app-log`. Se OOM: `JAVA_OPTS="-Xmx256m"`. Se crash: investigue stack |
| Health check failing pós-deploy | Migration falhou ou config faltando | **Rollback primeiro**. Investigue depois |
| Latência subiu repentinamente | Pool esgotado, N+1, ou downstream | Thread dump + métricas de pool + Grafana |
| Deploy "sucesso" mas 502 | App sobe mas não responde a tempo | `shutdown.timeout` + readiness probe |

### Thread blocking & Performance

| Sintoma | Causa | Fix |
|---|---|---|
| CPU = 100%, throughput baixo | GC excessivo ou loop apertado | `jstack` + heap dump |
| p99 = 2s mas p50 = 20ms | GC pause ou pool contention | `-XX:+PrintGCDetails`; métricas do pool |
| Latency cresce com warmup | JIT ainda otimizando | Normal em JVM. Native não tem warmup |
| `OutOfMemoryError: Metaspace` | Classes carregando sem parar | ClassLoader leak; revise hot reload |

---

# APÊNDICE C — Referências

### Documentação Oficial
- **Quarkus Guides (índice completo)**: https://quarkus.io/guides/
- **Quarkus CDI Reference**: https://quarkus.io/guides/cdi-reference
- **Quarkus REST**: https://quarkus.io/guides/rest
- **Quarkus REST Client**: https://quarkus.io/guides/rest-client
- **Quarkus Hibernate Panache**: https://quarkus.io/guides/hibernate-orm-panache
- **Quarkus MongoDB Panache**: https://quarkus.io/guides/mongodb-panache
- **Quarkus gRPC**: https://quarkus.io/guides/grpc-getting-started
- **Quarkus Mutiny**: https://smallrye.io/smallrye-mutiny/
- **Quarkus Virtual Threads**: https://quarkus.io/guides/virtual-threads
- **Quarkus Fault Tolerance**: https://quarkus.io/guides/smallrye-fault-tolerance
- **Quarkus OpenTelemetry**: https://quarkus.io/guides/opentelemetry
- **Quarkus Micrometer**: https://quarkus.io/guides/micrometer
- **Quarkus Logging**: https://quarkus.io/guides/logging
- **Quarkus Security OIDC**: https://quarkus.io/guides/security-oidc-bearer-token-authentication
- **Quarkus Cache**: https://quarkus.io/guides/cache
- **Quarkus Scheduler**: https://quarkus.io/guides/scheduler
- **Quarkus Testing**: https://quarkus.io/guides/getting-started-testing
- **Quarkus Native (build, tuning, troubleshoot)**: https://quarkus.io/guides/building-native-image
- **Quarkus Native Reflection Config**: https://quarkus.io/guides/writing-native-applications-tips

### GraalVM
- **GraalVM Native Image Docs**: https://www.graalvm.org/latest/reference-manual/native-image/
- **Closed-world assumption**: https://www.graalvm.org/latest/reference-manual/native-image/basics/#closed-world-assumption
- **Reachability Metadata**: https://www.graalvm.org/latest/reference-manual/native-image/metadata/
- **Native Image Benchmarking**: https://www.graalvm.org/latest/reference-manual/native-image/guides/

### Spring Native (comparação)
- **Spring Boot Native Image**: https://docs.spring.io/spring-boot/docs/current/reference/html/native-image.html
- **Spring AOT Engine**: https://docs.spring.io/spring-framework/reference/core/aot.html

### GCP
- **Pub/Sub Documentation**: https://cloud.google.com/pubsub/docs
- **Pub/Sub Emulator**: https://cloud.google.com/pubsub/docs/emulator
- **Pub/Sub Dead Letter Topics**: https://cloud.google.com/pubsub/docs/handling-failures
- **Application Default Credentials**: https://cloud.google.com/docs/authentication/application-default-credentials
- **Quiver of Quarkiverse GCP extensions**: https://docs.quarkiverse.io/quarkus-google-cloud-services/

### MongoDB
- **MongoDB Manual**: https://www.mongodb.com/docs/manual/
- **Schema Design Patterns**: https://www.mongodb.com/blog/post/building-with-patterns-a-summary
- **Aggregation Pipeline**: https://www.mongodb.com/docs/manual/aggregation/
- **Indexes (best practices)**: https://www.mongodb.com/docs/manual/applications/indexes/

### Observabilidade
- **OpenTelemetry Java**: https://opentelemetry.io/docs/languages/java/
- **Micrometer docs**: https://micrometer.io/docs
- **Prometheus PromQL**: https://prometheus.io/docs/prometheus/latest/querying/basics/
- **Grafana Tempo**: https://grafana.com/docs/tempo/latest/
- **Grafana Loki LogQL**: https://grafana.com/docs/loki/latest/logql/
- **SRE Book (Google)**: https://sre.google/sre-book/table-of-contents/
- **The RED Method**: https://www.weave.works/blog/the-red-method-key-metrics-for-microservices-architecture/
- **USE Method (Brendan Gregg)**: https://www.brendangregg.com/usemethod.html

### Camunda
- **Camunda 7 Docs**: https://docs.camunda.org/manual/7.18/
- **Quarkus Integration**: https://docs.camunda.org/manual/7.18/user-guide/quarkus-integration/
- **BPMN 2.0 Reference**: https://docs.camunda.org/manual/7.18/reference/bpmn20/
- **JavaDelegate**: https://docs.camunda.org/manual/7.18/user-guide/process-engine/delegation-code/
- **External Tasks**: https://docs.camunda.org/manual/7.18/user-guide/process-engine/external-tasks/
- **Modeler**: https://camunda.com/download/modeler/
- **Best Practices**: https://camunda.com/best-practices/
- **Exemplos**: https://github.com/camunda/camunda-bpm-examples

### Tsuru
- **Docs**: https://docs.tsuru.io/
- **CLI Reference**: https://docs.tsuru.io/stable/reference/tsuru-client.html

### Livros (ordem sugerida)
1. **Quarkus in Action** (Alex Soto Bueno, Jason Porter — Manning) — ponto de entrada canônico.
2. **Release It!** (Michael Nygard, 2ª ed.) — patterns de resiliência que sustentam todo este guia.
3. **Designing Data-Intensive Applications** (Martin Kleppmann) — sistemas distribuídos, a bíblia informal.
4. **Site Reliability Engineering** (Google) — gratuito online; SLO/SLI/Error Budget.
5. **Database Internals** (Alex Petrov) — se quiser entender Postgres e Mongo por dentro.
6. **Reactive Systems in Java** (Clement Escoffier — autor da Mutiny) — gratuito na Red Hat.

### Canais de trilha contínua
- Quarkus blog: https://quarkus.io/blog/
- Red Hat Developer: https://developers.redhat.com/topics/quarkus
- Josh Long (Spring Tips) — comparação permanente Spring ↔ Quarkus: https://spring.io/blog/category/engineering
- Performance corner (Aleksey Shipilev, Shipilev, JVM things): https://shipilev.net/

---

## Última Nota

> Este guia é **um mapa, não o território**.
>
> Mapa mostra o caminho geral. Território tem buracos, desvios, atalhos e monstros que só quem anda descobre. Leia código do time antes de opinar. Pareie antes de inventar padrão novo. Pergunte antes de assumir. Quebre coisas em staging, nunca em produção na sexta.
>
> Você não precisa dominar tudo daqui em 4 semanas. Você precisa:
>
> 1. Entregar o primeiro PR útil na semana 1 (bug pequeno, refactor com escopo claro).
> 2. Ler e entender um fluxo end-to-end na semana 2 (desde REST até Pub/Sub até consumer até banco).
> 3. Propor uma melhoria com base em métrica (não em gosto) na semana 3.
> 4. Estar confortável em plantão / on-call na semana 4 (sabe abrir dashboards, sabe onde procurar).
>
> O resto vem com tempo, erro e muita leitura de código alheio. Bons commits.
