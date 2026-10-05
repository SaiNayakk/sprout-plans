package app.sprout.plans.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code sprout.plans} in plans.yml. */
@ConfigurationProperties("sprout.plans")
public record PlansProperties(Duration every, String serviceKey, String minimum, String maximum, int maxActive, String marketdataUrl,
                              String omsUrl, String accountsUrl) {}
