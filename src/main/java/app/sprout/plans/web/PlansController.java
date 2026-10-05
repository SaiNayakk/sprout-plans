package app.sprout.plans.web;

import app.sprout.plans.domain.ApiException;
import app.sprout.plans.domain.ErrorCode;
import app.sprout.plans.domain.Money;
import app.sprout.plans.domain.Plans;
import app.sprout.plans.domain.Plans.Created;
import app.sprout.plans.domain.Plans.Plan;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The plans API (plans-v1.yaml). */
@RestController
public class PlansController {

    public record NewPlan(String symbol, String amount, Integer dayOfMonth, Boolean startNow) {}

    private final Plans plans;

    public PlansController(Plans plans) {
        this.plans = plans;
    }

    @PostMapping("/v1/plans")
    public ResponseEntity<Map<String, Object>> create(@RequestHeader(value = "X-User-Id", required = false) String user,
                                                      @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                      @RequestBody NewPlan req) {
        Created c = plans.create(userId(user), key, req.symbol(), req.amount(), req.dayOfMonth(), Boolean.TRUE.equals(req.startNow()));
        return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(dto(c.plan()));
    }

    @GetMapping("/v1/plans")
    public Map<String, Object> list(@RequestHeader(value = "X-User-Id", required = false) String user) {
        return Map.of("plans", plans.mine(userId(user)).stream().map(PlansController::dto).toList());
    }

    @GetMapping("/v1/plans/{id}")
    public Map<String, Object> get(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return dto(plans.mine(userId(user), id));
    }

    @PostMapping("/v1/plans/{id}/pause")
    public Map<String, Object> pause(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return dto(plans.change(userId(user), id, "ACTIVE", "PAUSED"));
    }

    @PostMapping("/v1/plans/{id}/resume")
    public Map<String, Object> resume(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return dto(plans.change(userId(user), id, "PAUSED", "ACTIVE"));
    }

    @DeleteMapping("/v1/plans/{id}")
    public Map<String, Object> cancel(@RequestHeader(value = "X-User-Id", required = false) String user, @PathVariable UUID id) {
        return dto(plans.change(userId(user), id, "ACTIVE,PAUSED", "CANCELLED"));
    }

    static UUID userId(String header) {
        try {
            return UUID.fromString(header);
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Sign in first.");
        }
    }

    static Map<String, Object> dto(Plan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id().toString());
        m.put("symbol", p.symbol());
        m.put("amount", Money.rupees(p.amountPaise()));
        m.put("dayOfMonth", p.dayOfMonth());
        m.put("status", p.status());
        if (p.status().equals("ACTIVE")) {
            m.put("nextDue", p.nextDue().toString());
        }
        m.put("invested", Money.rupees(p.investedPaise()));
        m.put("createdAt", p.createdAt().toString());
        m.put("instalments", p.instalments().stream().map(i -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("month", i.month().toString());
            out.put("status", i.status());
            if (i.orderId() != null) {
                out.put("orderId", i.orderId().toString());
            }
            if (i.quantity() != null) {
                out.put("quantity", i.quantity());
            }
            if (i.pricePaise() != null) {
                out.put("price", Money.rupees(i.pricePaise()));
            }
            if (i.reason() != null) {
                out.put("reason", i.reason());
            }
            out.put("at", i.at().toString());
            return out;
        }).toList());
        return m;
    }
}
