package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.support.JwtTestKeys;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ReflectionUtils;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * C9(a) / AC-5.10, first half: the outbox relay is actually SCHEDULED, at the delay the shipped
 * posture produces.
 *
 * <p><b>Why this needs a context of its own.</b> {@code SchedulingConfig} is
 * {@code @ConditionalOnProperty(name = "app.scheduling.enabled", matchIfMissing = true)} and the
 * test overlay sets it to {@code false}, so every other suite in this service runs with scheduling
 * OFF and drives {@link OutboxRelay#drain()} by hand. That is deliberate and must stay — but it
 * leaves the whole suite unable to observe the relay ceasing to be scheduled at all. There is no
 * outbox-lag metric and no HealthIndicator behind it (contract C9), so a relay that never fires is
 * indistinguishable from the contract's intended "rows accumulate until the queue exists". Until
 * this class the only witness was a human watching compose.
 *
 * <p><b>This class deliberately carries no {@code @ActiveProfiles("test")}</b>: it must read {@code
 * src/main/resources/application.yml} alone. Activating the overlay would hand this context {@code
 * app.scheduling.enabled=false} — no scheduled task at all — and {@code
 * outbox.relay.fixed-delay-ms=60000}, which is precisely the value the provenance row below exists
 * to prove is NOT in play. The absence of both is asserted rather than assumed, so a later
 * "tidy-up" that re-adds the profile cannot pass silently.
 *
 * <p>Boot needs registered below are the shipped placeholders that carry no default, enumerated
 * from the file rather than discovered one context failure at a time ({@code grep -nE
 * '\$\{[A-Z_]+\}' src/main/resources/application.yml}): the three {@code spring.datasource.*} keys,
 * {@code JWT_PUBLIC_KEY_PATH} and {@code PAYMENT_WEBHOOK_SECRET}. {@code spring.rabbitmq.dynamic}
 * is the other half of what dropping the profile costs: the shipped default lets {@code
 * RabbitAdmin} declare topology against the compose hostname {@code rabbitmq}, which does not
 * resolve here. Neither {@code outbox.relay.fixed-delay-ms} nor {@code app.scheduling.enabled} is
 * registered, and registering either would destroy the pin.
 *
 * <p>The scheduler does run for the lifetime of this class, against an empty {@code outbox_events}
 * table in its own container: {@code drain()} finds no rows and returns at the empty-batch guard,
 * so it never reaches a publish and never needs the broker.
 */
@SpringBootTest
class OutboxRelayScheduleIntegrationTest {

  /**
   * The placeholder key on {@code OutboxRelay.drain}'s {@code @Scheduled(fixedDelayString = ...)}.
   *
   * <p><b>payment's provenance clause is the INVERSE of order's.</b> order's shipped yml sets its
   * interval, so order asserts the winning property source is the shipped {@code application.yml}.
   * payment's shipped yml never sets this key at all — so what must be pinned here is the ABSENCE
   * of any origin plus an effective delay equal to the annotation's own fallback. Asserting a
   * shipped origin here would be asserting a fact that is not true of this service.
   */
  private static final String INTERVAL_KEY = "outbox.relay.fixed-delay-ms";

  /** The {@code :1000} fallback written into the {@code @Scheduled} placeholder itself. */
  private static final Duration ANNOTATION_DEFAULT_DELAY = Duration.ofMillis(1000);

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("payment_db")
          .withUsername("payment")
          .withPassword("payment");

  @BeforeAll
  static void startContainer() {
    if (!POSTGRES.isRunning()) {
      POSTGRES.start();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("JWT_PUBLIC_KEY_PATH", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add("PAYMENT_WEBHOOK_SECRET", () -> "test-webhook-secret");
    registry.add("spring.rabbitmq.dynamic", () -> "false");
  }

  @Autowired private ScheduledTaskHolder scheduledTaskHolder;
  @Autowired private OutboxRelay outboxRelay;
  @Autowired private ConfigurableEnvironment environment;

  /**
   * The C9(a) row itself: exactly one fixed-delay task, every second, on {@code OutboxRelay.drain}.
   * Identity is asserted through the target BEAN and the reflected {@code Method} rather than
   * through a rendered string, so a rename or a second scheduled method cannot slip past a
   * substring match.
   */
  @Test
  void exactlyOneFixedDelayTask_runsOutboxRelayDrain_everySecond() {
    List<FixedDelayTask> fixedDelayTasks =
        scheduledTaskHolder.getScheduledTasks().stream()
            .map(ScheduledTask::getTask)
            .filter(FixedDelayTask.class::isInstance)
            .map(FixedDelayTask.class::cast)
            .toList();

    assertThat(fixedDelayTasks)
        .as(
            "the relay must be scheduled exactly once; every scheduled task in this context: %s",
            scheduledTaskHolder.getScheduledTasks().stream().map(ScheduledTask::getTask).toList())
        .hasSize(1);

    FixedDelayTask relayTask = fixedDelayTasks.get(0);
    ScheduledMethodRunnable scheduled = scheduledMethodOf(relayTask);

    assertThat(AopProxyUtils.ultimateTargetClass(scheduled.getTarget()))
        .as("the fixed-delay task must run OutboxRelay, not some other component")
        .isEqualTo(OutboxRelay.class);
    assertThat(scheduled.getTarget())
        .as("and it must be bound to the live singleton, not a second instance")
        .isSameAs(outboxRelay);
    assertThat(scheduled.getMethod().getName()).isEqualTo("drain");
    assertThat(relayTask.getIntervalDuration())
        .as("the fixed delay the deploy actually runs with")
        .isEqualTo(ANNOTATION_DEFAULT_DELAY);
  }

  /**
   * The provenance half, inverted for payment. The row above is satisfiable by a value this test
   * handed itself, so what the effective 1000 ms MEANS has to be pinned separately — and on this
   * service the meaningful fact is that nothing configures it. Two clauses, neither redundant:
   *
   * <ul>
   *   <li>scheduling must be armed by {@code SchedulingConfig}'s {@code matchIfMissing} default,
   *       not by a value any source supplied — that is the posture the deploy relies on;
   *   <li>no property source may declare {@code outbox.relay.fixed-delay-ms} at all, which is what
   *       makes the 1000 ms above attributable to the annotation's own fallback rather than to
   *       config. Registering the key in this class's own {@code @DynamicPropertySource} — or
   *       adding it to the shipped yml — leaves the row above green (the value really would be
   *       1000) and reddens only here.
   * </ul>
   *
   * <p>The failure message names the winning source, because "somebody set this key" is only
   * actionable if you can see who.
   */
  @Test
  void theOneSecondDelay_comesFromTheAnnotationFallback_notFromAnyConfigSource() {
    assertThat(environment.getProperty("app.scheduling.enabled"))
        .as(
            "nothing may switch scheduling on or off for this context — the relay must be enabled"
                + " by SchedulingConfig's matchIfMissing default, which is the shipped posture")
        .isNull();

    List<String> declaringSources = new ArrayList<>();
    for (PropertySource<?> source : environment.getPropertySources()) {
      if (source.containsProperty(INTERVAL_KEY)) {
        declaringSources.add(source.getName());
      }
    }

    assertThat(declaringSources)
        .as(
            "%s must be declared by NOTHING, so the effective 1000 ms can only be the @Scheduled"
                + " fallback; sources declaring it: %s",
            INTERVAL_KEY, declaringSources)
        .isEmpty();
    assertThat(environment.getProperty(INTERVAL_KEY))
        .as("and it must therefore resolve to nothing")
        .isNull();
  }

  /**
   * {@code Task.getRunnable()} hands back Spring's package-private {@code
   * Task$OutcomeTrackingRunnable} wrapper, not the {@link ScheduledMethodRunnable} the annotation
   * produced, so a plain cast fails. Unwrap along the wrapper's delegate field and refuse loudly if
   * the shape ever changes — an unexplained cast failure would read as a relay bug.
   */
  private static ScheduledMethodRunnable scheduledMethodOf(FixedDelayTask task) {
    Runnable current = task.getRunnable();
    for (int depth = 0; depth < 4; depth++) {
      if (current instanceof ScheduledMethodRunnable found) {
        return found;
      }
      Field delegate = ReflectionUtils.findField(current.getClass(), "runnable", Runnable.class);
      if (delegate == null) {
        break;
      }
      ReflectionUtils.makeAccessible(delegate);
      if (!(ReflectionUtils.getField(delegate, current) instanceof Runnable next)) {
        break;
      }
      current = next;
    }
    throw new AssertionError(
        "cannot derive the scheduled method: no ScheduledMethodRunnable under "
            + task.getRunnable().getClass().getName()
            + " — Spring changed how a scheduled task wraps its runnable, so this test needs"
            + " updating, not the relay");
  }
}
