package app.sprout.plans;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.sprout.contracts.Contracts;
import app.sprout.plans.domain.Plans;
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plans on a real Postgres, against stand-ins for accounts, market data (whose session the tests move
 * through the calendar) and the order service (which can fill, refuse for money, or go quiet).
 */
@Testcontainers
@SpringBootTest(properties = {"spring.config.name=plans", "sprout.plans.every=1h"})
@AutoConfigureMockMvc
class PlansApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> ACCOUNTS = ConcurrentHashMap.newKeySet();
    static final AtomicReference<String> SESSION = new AtomicReference<>("2026-10-07");
    static final AtomicBoolean OPEN = new AtomicBoolean(true);
    static final Map<String, Double> LAST = new ConcurrentHashMap<>(Map.of("HARBOR", 1000.0, "KOSHA", 310.0));
    static final Map<String, Map<String, Object>> ORDERS = new ConcurrentHashMap<>();   // idempotency key -> order
    static final AtomicReference<String> OMS_MODE = new AtomicReference<>("FILL");      // FILL, POOR, DOWN
    static final HttpServer STANDINS = standIns();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://127.0.0.1:" + STANDINS.getAddress().getPort();
        r.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=plans");
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("sprout.plans.marketdata-url", () -> base);
        r.add("sprout.plans.oms-url", () -> base);
        r.add("sprout.plans.accounts-url", () -> base);
    }

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-10-07T05:00:00Z"));
        }
    }

    static final OpenApiInteractionValidator CONTRACT = OpenApiInteractionValidator
            .createForInlineApiSpecification(Contracts.read(Contracts.PLANS_V1))
            .withBasePathOverride("/")
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();
    static final ResultMatcher MATCHES_CONTRACT = openApi().isValid(CONTRACT);

    @Autowired MockMvc mvc;
    @Autowired Plans plans;

    UUID user;

    @BeforeEach
    void aCustomer() {
        user = UUID.randomUUID();
        ACCOUNTS.add(user.toString());
        SESSION.set("2026-10-07");
        OPEN.set(true);
        OMS_MODE.set("FILL");
    }

    ResultActions create(String symbol, String amount, int day, boolean startNow) throws Exception {
        return mvc.perform(post("/v1/plans").header("X-User-Id", user.toString()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("symbol", symbol, "amount", amount, "dayOfMonth", day, "startNow", startNow))));
    }

    JsonNode body(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    JsonNode plan(JsonNode p) throws Exception {
        return body(mvc.perform(get("/v1/plans/" + p.path("id").asText()).header("X-User-Id", user.toString())).andExpect(status().isOk())
                .andExpect(MATCHES_CONTRACT));
    }

    long ordersFor(JsonNode p) {
        return ORDERS.keySet().stream().filter(k -> k.startsWith("sip:" + p.path("id").asText())).count();
    }

    @Test
    void aPlanStartedNowBuysWholeSharesAtOnceThenWaitsForNextMonth() throws Exception {
        JsonNode p = body(create("KOSHA", "1000", 5, true).andExpect(status().isCreated()).andExpect(MATCHES_CONTRACT));
        plans.runDue();
        JsonNode now = plan(p);
        JsonNode first = now.path("instalments").get(0);
        assertThat(first.path("month").asText()).isEqualTo("2026-10");
        assertThat(first.path("status").asText()).isEqualTo("FILLED");
        assertThat(first.path("quantity").asLong()).as("₹1,000 at ₹310: 3 whole shares").isEqualTo(3);
        assertThat(now.path("invested").asText()).isEqualTo("930.00");
        assertThat(now.path("nextDue").asText()).isEqualTo("2026-11-05");
        plans.runDue();
        SESSION.set("2026-10-30");
        plans.runDue();
        assertThat(ordersFor(p)).as("one instalment in October").isEqualTo(1);
        SESSION.set("2026-11-05");
        plans.runDue();
        assertThat(plan(p).path("instalments").get(0).path("month").asText()).isEqualTo("2026-11");
        assertThat(ordersFor(p)).isEqualTo(2);
    }

    @Test
    void aPlanWaitsForItsDayAndTheMarketToBeOpen() throws Exception {
        JsonNode p = body(create("HARBOR", "2500", 12, false));
        assertThat(p.path("nextDue").asText()).isEqualTo("2026-10-12");
        plans.runDue();
        assertThat(plan(p).path("instalments").size()).isZero();
        SESSION.set("2026-10-13");   // the 12th was a holiday: the first session after it
        OPEN.set(false);
        plans.runDue();
        assertThat(plan(p).path("instalments").size()).as("not while the market is closed").isZero();
        OPEN.set(true);
        plans.runDue();
        JsonNode i = plan(p).path("instalments").get(0);
        assertThat(i.path("month").asText()).isEqualTo("2026-10");
        assertThat(i.path("quantity").asLong()).isEqualTo(2);
    }

    @Test
    void monthsThatCantBuyAreSkippedWithTheReasonAndNeverBoughtLate() throws Exception {
        JsonNode small = body(create("HARBOR", "500", 1, true));        // less than one share
        plans.runDue();
        JsonNode s = plan(small).path("instalments").get(0);
        assertThat(s.path("status").asText()).isEqualTo("SKIPPED");
        assertThat(s.path("reason").asText()).contains("less than one share");

        OMS_MODE.set("POOR");
        JsonNode poor = body(create("KOSHA", "5000", 1, true));
        plans.runDue();
        assertThat(plan(poor).path("instalments").get(0).path("reason").asText()).contains("needs");
        OMS_MODE.set("FILL");

        SESSION.set("2027-01-02");   // November and December passed with nothing placed
        plans.runDue();
        plans.runDue();
        plans.runDue();
        JsonNode later = plan(poor).path("instalments");
        assertThat(later.get(later.size() - 2).path("reason").asText()).as("November").contains("Not placed in time");
        assertThat(later.get(0).path("month").asText()).isEqualTo("2027-01");
        assertThat(later.get(0).path("status").asText()).isEqualTo("FILLED");
        assertThat(ordersFor(poor)).as("October (refused) and January only").isEqualTo(2);
    }

    @Test
    void anInstalmentWhoseAnswerWasLostIsSettledLaterNotPlacedTwice() throws Exception {
        JsonNode p = body(create("KOSHA", "1000", 1, true));
        OMS_MODE.set("DOWN");
        plans.runDue();   // the order service doesn't answer: the month is claimed, the order's fate unknown
        assertThat(plan(p).path("instalments").get(0).path("status").asText()).isEqualTo("PLACED");
        OMS_MODE.set("FILL");
        plans.runDue();
        assertThat(plan(p).path("instalments").get(0).path("status").asText()).isEqualTo("FILLED");
        assertThat(ordersFor(p)).isEqualTo(1);
    }

    @Test
    void plansPauseResumeAndStop() throws Exception {
        JsonNode p = body(create("HARBOR", "1500", 1, true));
        String id = p.path("id").asText();
        mvc.perform(post("/v1/plans/" + id + "/pause").header("X-User-Id", user.toString())).andExpect(status().isOk())
                .andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.status").value("PAUSED"));
        plans.runDue();
        assertThat(plan(p).path("instalments").size()).as("paused: nothing bought").isZero();
        mvc.perform(post("/v1/plans/" + id + "/pause").header("X-User-Id", user.toString())).andExpect(status().isConflict())
                .andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("PLAN_STATE"));
        mvc.perform(post("/v1/plans/" + id + "/resume").header("X-User-Id", user.toString())).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(delete("/v1/plans/" + id).header("X-User-Id", user.toString())).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/v1/plans/" + id + "/resume").header("X-User-Id", user.toString())).andExpect(status().isConflict());
        mvc.perform(get("/v1/plans/" + id).header("X-User-Id", UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }

    @Test
    void plansMustMakeSense() throws Exception {
        create("HARBOR", "50", 1, false).andExpect(status().isBadRequest()).andExpect(MATCHES_CONTRACT);
        create("HARBOR", "1000", 29, false).andExpect(status().isBadRequest());
        create("SPROUT20", "1000", 1, false).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("UNKNOWN_INSTRUMENT"));
        for (int i = 0; i < 10; i++) {
            create("HARBOR", "1000", 1, false).andExpect(status().isCreated());
        }
        create("HARBOR", "1000", 1, false).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TOO_MANY_PLANS"));
        String key = UUID.randomUUID().toString();
        UUID stranger = UUID.randomUUID();
        mvc.perform(post("/v1/plans").header("X-User-Id", stranger.toString()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"symbol\":\"HARBOR\",\"amount\":\"1000\",\"dayOfMonth\":1}"))
                .andExpect(status().isNotFound()).andExpect(MATCHES_CONTRACT).andExpect(jsonPath("$.code").value("NO_ACCOUNT"));
        assertThat(body(mvc.perform(get("/v1/plans").header("X-User-Id", user.toString())).andExpect(MATCHES_CONTRACT)).path("plans").size())
                .isEqualTo(10);
    }

    // ── the stand-ins ────────────────────────────────────────────────────────

    static HttpServer standIns() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/internal/v1/accounts/", ex -> {
                String id = ex.getRequestURI().getPath().substring("/internal/v1/accounts/".length());
                reply(ex, ACCOUNTS.contains(id) ? 200 : 404, Map.of("userId", id));
            });
            s.createContext("/v1/market", ex -> reply(ex, 200, Map.of("state", OPEN.get() ? "OPEN" : "CLOSED", "sessionDate", SESSION.get())));
            s.createContext("/v1/instruments/", ex -> {
                String sym = ex.getRequestURI().getPath().substring("/v1/instruments/".length());
                reply(ex, 200, Map.of("symbol", sym, "tradable", !sym.equals("SPROUT20")));
            });
            s.createContext("/v1/quotes", ex -> {
                String sym = ex.getRequestURI().getQuery().replace("symbols=", "");
                reply(ex, 200, Map.of("quotes", List.of(Map.of("symbol", sym, "last", LAST.get(sym)))));
            });
            s.createContext("/internal/v1/orders", ex -> {
                if (OMS_MODE.get().equals("DOWN")) {
                    reply(ex, 503, Map.of());
                    return;
                }
                String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
                JsonNode o = JSON.readTree(ex.getRequestBody().readAllBytes());
                Map<String, Object> existing = ORDERS.get(key);
                if (existing != null) {
                    reply(ex, 200, existing);
                    return;
                }
                Map<String, Object> order = new LinkedHashMap<>();
                order.put("id", UUID.randomUUID().toString());
                order.put("symbol", o.path("symbol").asText());
                if (OMS_MODE.get().equals("POOR")) {
                    order.put("status", "REJECTED");
                    order.put("rejection", Map.of("code", "INSUFFICIENT_FUNDS", "message", "This order needs ₹5,000 and you have ₹0 available."));
                } else {
                    order.put("status", "FILLED");
                    order.put("price", String.format("%.2f", LAST.get(o.path("symbol").asText())));
                }
                ORDERS.put(key, order);
                reply(ex, 201, order);
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
