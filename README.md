# sprout-plans

**Systematic investment plans (SIPs)**: a fixed amount into a share every month, on the day the customer
chooses. Investing regularly is the habit Sprout is built to grow, and this is how.

- **An instalment** is a delivery market buy of as many whole shares as the amount covers, placed through the [order service](https://github.com/SaiNayakk/sprout-oms) on the customer's behalf (same checks, same charges), tagged `sip:<plan>`.
- **At most one a month**: on the plan's day or the first session after it. The (plan, month) pair is claimed before the order is placed, and the order goes under a key made from the same pair, so a retry never buys twice; an instalment whose answer was lost is settled by asking again with the same key.
- **Skipped months are recorded with the reason** (not enough money, less than one share) and never bought late; neither are months that passed while Sprout wasn't running.
- Plans can be paused, resumed and stopped; their instalments stay on record. Optionally the first instalment is made at once.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`plans-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/plans-v1.yaml)
in sprout-contracts. It runs inside the **trading** host.

`./mvnw verify` runs the tests on a real Postgres against stand-ins for accounts, market data (moved
through the calendar) and the order service (which can fill, refuse, or go quiet).

## License

MIT
