package me.virul.nexora.samples.springboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * An order API whose {@link me.virul.nexora.api.NexoraEngine} comes entirely from
 * nexora-spring-boot-starter: {@code application.yml} configures it, and the plugin and step
 * definitions in {@link OrderWorkflowConfig} are plain Spring beans.
 *
 * <pre>
 * java -jar tests/spring-boot-demo/target/spring-boot-demo.jar
 * curl -s localhost:8080/v1/orders -H 'Content-Type: application/json' -d '{"orderId":"ORD-1","amount":42.50}'
 * curl -s localhost:8080/actuator/health/readiness
 * </pre>
 */
@SpringBootApplication
public class SpringBootDemoApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootDemoApp.class, args);
    }
}
