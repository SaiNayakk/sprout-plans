package app.sprout.plans.domain;

import app.sprout.plans.config.PlansProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * What plans need from the rest of Sprout: whether the customer has an account (accounts), the market's
 * session and prices and which shares trade (market data), and placing the instalment's order (the
 * order service, on the customer's behalf). Each call has a hard deadline.
 */
@Component
public class Upstreams {

    static final Duration DEADLINE = Duration.ofSeconds(4);

    public static class Unreachable extends RuntimeException {
        public Unreachable(String what) {
            super(what);
        }
    }

    public record Market(boolean open, LocalDate session) {}

    public record Reply(int status, JsonNode body) {}

    private final PlansProperties props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private final Onward onward;

    public Upstreams(PlansProperties props, ObjectMapper json, Onward onward) {
        this.onward = onward;
        this.props = props;
        this.json = json;
    }

    public void requireAccount(UUID user) {
        Reply r = send("accounts", HttpRequest.newBuilder(URI.create(props.accountsUrl() + "/internal/v1/accounts/" + user))
                .header("X-Service-Key", props.serviceKey()).GET());
        if (r.status() == 404) {
            throw new ApiException(ErrorCode.NO_ACCOUNT, "Open a Sprout account first.");
        }
        if (r.status() != 200) {
            throw unavailable();
        }
    }

    public boolean tradable(String symbol) {
        Reply r = send("market data", HttpRequest.newBuilder(URI.create(props.marketdataUrl() + "/v1/instruments/" + symbol)).GET());
        if (r.status() == 404 || r.status() == 422) {
            return false;
        }
        if (r.status() != 200) {
            throw unavailable();
        }
        return r.body().path("tradable").asBoolean();
    }

    public Market market() {
        Reply r = send("market data", HttpRequest.newBuilder(URI.create(props.marketdataUrl() + "/v1/market")).GET());
        if (r.status() != 200) {
            throw new Unreachable("market data answered " + r.status());
        }
        return new Market("OPEN".equals(r.body().path("state").asText()), LocalDate.parse(r.body().path("sessionDate").asText()));
    }

    public long lastPrice(String symbol) {
        Reply r = send("market data", HttpRequest.newBuilder(URI.create(props.marketdataUrl() + "/v1/quotes?symbols=" + symbol)).GET());
        if (r.status() != 200) {
            throw new Unreachable("market data answered " + r.status());
        }
        return Math.round(r.body().path("quotes").get(0).path("last").asDouble() * 100);
    }

    /** Places (or, repeated with the same key, finds) the instalment's order. */
    public Reply placeFor(UUID user, String key, String symbol, long quantity, String tag) {
        Map<String, Object> body = Map.of("userId", user.toString(), "symbol", symbol, "side", "BUY", "quantity", quantity,
                "orderType", "MARKET", "product", "CNC", "tag", tag);
        try {
            return send("the order service", HttpRequest.newBuilder(URI.create(props.omsUrl() + "/internal/v1/orders"))
                    .header("Content-Type", "application/json").header("X-Service-Key", props.serviceKey()).header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Reply send(String what, HttpRequest.Builder req) {
        onward.headers(req);
        try {
            HttpResponse<String> res = http.sendAsync(req.timeout(DEADLINE).build(), HttpResponse.BodyHandlers.ofString())
                    .get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            if (res.statusCode() >= 500) {
                throw new Unreachable(what + " answered " + res.statusCode());
            }
            return new Reply(res.statusCode(), res.body() == null || res.body().isBlank() ? null : json.readTree(res.body()));
        } catch (Unreachable e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new Unreachable(what + " unreachable: " + e.getClass().getSimpleName());
        }
    }

    public static ApiException unavailable() {
        return new ApiException(ErrorCode.UPSTREAM_UNAVAILABLE, "Part of Sprout isn't reachable right now. Try again shortly.", 5, Map.of());
    }
}
