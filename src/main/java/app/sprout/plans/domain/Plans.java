package app.sprout.plans.domain;

import app.sprout.plans.config.PlansProperties;
import app.sprout.plans.domain.Upstreams.Market;
import app.sprout.plans.domain.Upstreams.Reply;
import app.sprout.plans.domain.Upstreams.Unreachable;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Plans and their instalments. An instalment is due on the plan's day each month (or the first session
 * after it), at most once a month: the (plan, month) key makes a second impossible, and the order is
 * placed under a key made from the same pair, so a retry never buys twice.
 */
@Service
public class Plans {

    private static final Logger log = LoggerFactory.getLogger(Plans.class);

    public record Instalment(YearMonth month, String status, UUID orderId, Long quantity, Long pricePaise, String reason, Instant at) {}

    public record Plan(UUID id, UUID userId, String symbol, long amountPaise, int dayOfMonth, boolean startNow, String status, LocalDate startDate,
                       Instant createdAt, List<Instalment> instalments) {

        /** When the next instalment is due: the plan's day in the month after the last one (or at once, for a plan started now). */
        public LocalDate nextDue() {
            if (instalments.isEmpty()) {
                if (startNow) {
                    return startDate;
                }
                LocalDate thisMonth = startDate.withDayOfMonth(dayOfMonth);
                return thisMonth.isBefore(startDate) ? thisMonth.plusMonths(1) : thisMonth;
            }
            return instalments.get(0).month().plusMonths(1).atDay(dayOfMonth);
        }

        public long investedPaise() {
            return instalments.stream().filter(i -> i.status().equals("FILLED"))
                    .mapToLong(i -> i.quantity() * i.pricePaise()).sum();
        }
    }

    public record Created(Plan plan, boolean created) {}

    private final JdbcClient db;
    private final Clock clock;
    private final Upstreams up;
    private final PlansProperties props;

    public Plans(JdbcClient db, Clock clock, Upstreams up, PlansProperties props) {
        this.db = db;
        this.clock = clock;
        this.up = up;
        this.props = props;
    }

    // ── customers ────────────────────────────────────────────────────────────

    public Created create(UUID user, String key, String symbolInput, String amount, Integer day, boolean startNow) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send an Idempotency-Key header (8 to 100 characters, e.g. a UUID).");
        }
        String symbol = symbolInput == null ? "" : symbolInput.trim().toUpperCase(Locale.ROOT);
        long paise = Money.paise(amount);
        if (paise < Money.paise(props.minimum()) || paise > Money.paise(props.maximum())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A plan is ₹" + props.minimum() + " to ₹" + props.maximum() + " a month.");
        }
        if (day == null || day < 1 || day > 28) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "dayOfMonth is 1 to 28, so every month has it.");
        }
        String hash = hash(symbol + "|" + paise + "|" + day + "|" + startNow);
        Optional<Created> earlier = byKey(user, key, hash);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        LocalDate session;
        try {
            up.requireAccount(user);
            if (!symbol.matches("[A-Z0-9&_-]{1,20}") || !up.tradable(symbol)) {
                throw new ApiException(ErrorCode.UNKNOWN_INSTRUMENT, "There's no tradable share called " + symbol + ".");
            }
            session = up.market().session();
        } catch (Unreachable e) {
            throw Upstreams.unavailable();
        }
        int active = db.sql("SELECT COUNT(*) FROM plans WHERE user_id = ? AND status = 'ACTIVE'").param(user).query(Integer.class).single();
        if (active >= props.maxActive()) {
            throw new ApiException(ErrorCode.TOO_MANY_PLANS, "You have " + active + " active plans, the most there can be. Pause or stop one first.");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            db.sql("""
                            INSERT INTO plans (id, user_id, idempotency_key, request_hash, symbol, amount_paise, day_of_month, start_now, status,
                                               start_date, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)""")
                    .params(id, user, key, hash, symbol, paise, day, startNow, session, ts(now), ts(now)).update();
        } catch (DuplicateKeyException e) {
            return byKey(user, key, hash).orElseThrow(() -> e);
        }
        return new Created(plan(id), true);
    }

    public List<Plan> mine(UUID user) {
        return db.sql("SELECT id FROM plans WHERE user_id = ? ORDER BY created_at DESC").param(user).query(UUID.class).list()
                .stream().map(this::plan).toList();
    }

    public Plan mine(UUID user, UUID id) {
        Plan p = db.sql(PLAN_SQL + " WHERE id = ? AND user_id = ?").params(id, user).query(this::row).optional()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such plan of yours."));
        return p;
    }

    public Plan change(UUID user, UUID id, String from, String to) {
        mine(user, id);
        int changed = db.sql("UPDATE plans SET status = ?, updated_at = ? WHERE id = ? AND user_id = ? AND status = ANY(?)")
                .params(to, ts(clock.instant()), id, user, from.split(",")).update();
        if (changed == 0) {
            throw new ApiException(ErrorCode.PLAN_STATE, "The plan is " + plan(id).status() + ".");
        }
        return plan(id);
    }

    // ── instalments ──────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${sprout.plans.every:30s}")
    void scheduled() {
        try {
            runDue();
        } catch (RuntimeException e) {
            log.warn("Plans round didn't finish: {}", e.getMessage());
        }
    }

    /** Places every due instalment, and settles ones still in flight. Only while the market is open. */
    public int runDue() {
        Market m = up.market();
        if (!m.open()) {
            return 0;
        }
        int placed = 0;
        for (UUID id : db.sql("SELECT plan_id FROM instalments WHERE status = 'PLACED'").query(UUID.class).list()) {
            settle(plan(id));
        }
        for (UUID id : db.sql("SELECT id FROM plans WHERE status = 'ACTIVE' ORDER BY created_at").query(UUID.class).list()) {
            try {
                placed += due(plan(id), m) ? 1 : 0;
            } catch (Unreachable e) {
                // this plan's instalment is claimed (PLACED) or untouched; the next round carries on, and the other plans go ahead now
                log.info("Plan {} waits: {}", id, e.getMessage());
            }
        }
        return placed;
    }

    private boolean due(Plan p, Market m) {
        LocalDate due = p.nextDue();
        if (m.session().isBefore(due)) {
            return false;
        }
        YearMonth month = p.instalments().isEmpty() && p.startNow() ? YearMonth.from(m.session()) : YearMonth.from(due);
        if (month.isBefore(YearMonth.from(m.session()))) {
            // a past month nothing was placed in (Sprout wasn't running): recorded, never bought late
            record(p, month, "SKIPPED", null, null, null, "Not placed in time: no instalment is made late.", clock.instant());
            return false;
        }
        return instalment(p, month, m.session());
    }

    private boolean instalment(Plan p, YearMonth month, LocalDate session) {
        Instant now = clock.instant();
        long last = up.lastPrice(p.symbol());
        long quantity = p.amountPaise() / last;
        if (quantity < 1) {
            return record(p, month, "SKIPPED", null, null, null,
                    "₹" + Money.rupees(p.amountPaise()) + " buys less than one share of " + p.symbol() + " (₹" + Money.rupees(last) + ").", now);
        }
        // claim the month first: whatever happens next, this plan makes no second instalment for it
        if (!record(p, month, "PLACED", null, quantity, null, null, now)) {
            return false;
        }
        apply(p, month, up.placeFor(p.userId(), key(p, month), p.symbol(), quantity, "sip:" + p.id()));
        log.info("Plan {}: instalment {} of {} x{} on {}", p.id(), month, p.symbol(), quantity, session);
        return true;
    }

    /** Asks again about an instalment whose order hadn't finished (same key: the order service answers with its current state). */
    private void settle(Plan p) {
        for (Instalment i : p.instalments()) {
            if (i.status().equals("PLACED")) {
                try {
                    apply(p, i.month(), up.placeFor(p.userId(), key(p, i.month()), p.symbol(), i.quantity(), "sip:" + p.id()));
                } catch (Unreachable e) {
                    log.info("Plan {} instalment {} still unconfirmed: {}", p.id(), i.month(), e.getMessage());
                }
            }
        }
    }

    private void apply(Plan p, YearMonth month, Reply r) {
        if (r.status() / 100 != 2) {
            update(p, month, "SKIPPED", null, null, "The order couldn't be placed (" + r.body().path("code").asText(String.valueOf(r.status())) + ").");
            return;
        }
        JsonNode o = r.body();
        UUID orderId = UUID.fromString(o.path("id").asText());
        switch (o.path("status").asText()) {
            case "FILLED" -> update(p, month, "FILLED", orderId, Money.paise(o.path("price").asText()), null);
            case "REJECTED", "CANCELLED", "EXPIRED" -> update(p, month, "SKIPPED", orderId, null,
                    o.path("rejection").path("message").asText(o.path("reason").asText("The order didn't execute.")));
            default -> db.sql("UPDATE instalments SET order_id = ? WHERE plan_id = ? AND month = ?").params(orderId, p.id(), month.toString()).update();
        }
    }

    private boolean record(Plan p, YearMonth month, String status, UUID orderId, Long quantity, Long price, String reason, Instant at) {
        return db.sql("""
                        INSERT INTO instalments (plan_id, month, status, order_id, quantity, price_paise, reason, at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""")
                .params(p.id(), month.toString(), status, orderId, quantity, price, reason, ts(at)).update() == 1;
    }

    private void update(Plan p, YearMonth month, String status, UUID orderId, Long price, String reason) {
        db.sql("UPDATE instalments SET status = ?, order_id = COALESCE(?, order_id), price_paise = ?, reason = ? WHERE plan_id = ? AND month = ?")
                .params(status, orderId, price, reason, p.id(), month.toString()).update();
    }

    static String key(Plan p, YearMonth month) {
        return "sip:" + p.id() + ":" + month;
    }

    // ── rows ─────────────────────────────────────────────────────────────────

    public Plan plan(UUID id) {
        return db.sql(PLAN_SQL + " WHERE id = ?").param(id).query(this::row).single();
    }

    private Optional<Created> byKey(UUID user, String key, String hash) {
        return db.sql("SELECT id, request_hash FROM plans WHERE user_id = ? AND idempotency_key = ?").params(user, key)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}).optional()
                .map(row -> {
                    if (!row[1].equals(hash)) {
                        throw new ApiException(ErrorCode.VALIDATION_FAILED, "This Idempotency-Key was already used for a different plan.");
                    }
                    return new Created(plan((UUID) row[0]), false);
                });
    }

    private static final String PLAN_SQL =
            "SELECT id, user_id, symbol, amount_paise, day_of_month, start_now, status, start_date, created_at FROM plans";

    private Plan row(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<Instalment> instalments = db.sql("""
                        SELECT month, status, order_id, quantity, price_paise, reason, at FROM instalments WHERE plan_id = ? ORDER BY month DESC""")
                .param(id)
                .query((r, i) -> new Instalment(YearMonth.parse(r.getString(1)), r.getString(2), r.getObject(3, UUID.class),
                        r.getObject(4, Long.class), r.getObject(5, Long.class), r.getString(6), r.getTimestamp(7).toInstant()))
                .list();
        return new Plan(id, rs.getObject("user_id", UUID.class), rs.getString("symbol"), rs.getLong("amount_paise"), rs.getInt("day_of_month"),
                rs.getBoolean("start_now"), rs.getString("status"), rs.getObject("start_date", LocalDate.class),
                rs.getTimestamp("created_at").toInstant(), instalments);
    }

    static String hash(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
