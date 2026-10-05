package app.sprout.plans;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Plans: systematic investment plans (SIPs), a fixed amount into a share every month.
 *
 * <p>Runs on its own ({@link #main}) or inside a shared JVM host, which calls {@link #builder()}.
 * Either way it reads {@code plans.yml}, never {@code application.yml}, so services sharing a
 * host can't read each other's settings.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PlansApplication {

    public static final String CONFIG_NAME = "plans";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(PlansApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME);
    }
}
