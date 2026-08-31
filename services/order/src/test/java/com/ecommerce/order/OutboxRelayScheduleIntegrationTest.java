package com.ecommerce.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.order.event.OutboxRelay;
import com.ecommerce.order.support.JwtTestKeys;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.boot.origin.TextResourceOrigin;
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
 * C9(a) / AC-1b.11: the outbox relay is actually SCHEDULED, at the delay the shipped configuration
 * names.
 *
 * <p><b>Why this needs a context of its own.</b> {@code SchedulingConfig} is
 * {@code @ConditionalOnProperty(name = "app.scheduling.enabled", matchIfMissing = true)} and the
 * test overlay sets it to {@code false}, so every other suite in this service runs with scheduling
 * OFF and drives {@link OutboxRelay#drain()} by hand. That is deliberate and must stay — but it
 * leaves the whole suite unable to observe two regressions: the relay ceasing to be scheduled at
 * all, and {@code app.outbox.relay.interval-ms} ceasing to bind. Neither has a metric or a health
 * indicator behind it (contract C9), so a relay that never fires is indistinguishable from the
 * contract's intended "rows accumulate until the queue exists" — until this class, the only witness
 * was a human watching compose.
 *
 * <p><b>This class deliberately carries no {@code @ActiveProfiles("test")}</b>, for the same reason
 * {@link com.ecommerce.order.security.Rs256OnlySecretAbsentValidationIntegrationTest} does not: it
 * must read {@code src/main/resources/application.yml} alone. Activating the overlay would mean
 * handing this context {@code app.scheduling.enabled=true}, and the assertion would then be about a
 * value the test supplied rather than about the shipped posture — where the key is ABSENT and
 * {@code matchIfMissing = true} is what turns the relay on. The absence is asserted below rather
 * than assumed, so a later "tidy-up" that re-adds the profile cannot pass silently.
 *
 * <p>Order-specific boot needs, registered because the shipped yaml binds them with no default —
 * enumerated from the file, not discovered one context failure at a time ({@code grep -nE
 * '\$\{[A-Z_]+\}' src/main/resources/application.yml}): {@code spring.datasource.*}, {@code
 * JWT_PUBLIC_KEY_PATH} and {@code INTERNAL_API_KEY}. The two RabbitMQ switches are the other half
 * of what dropping the profile costs: the shipped defaults auto-start the listener containers and
 * let {@code RabbitAdmin} declare topology, both against the compose hostname {@code rabbitmq},
 * which does not resolve here. Neither key's shipped value is read by any assertion in this class.
 * {@code app.outbox.relay.interval-ms} and {@code app.scheduling.enabled} are NOT registered, and
 * registering either would destroy the pin.
 *
 * <p>The scheduler does run for the lifetime of this class, against an empty {@code outbox_events}
 * table in its own container: {@code drain()} finds no rows, so it never reaches a publish and
 * never needs the broker.
 */
@SpringBootTest
class OutboxRelayScheduleIntegrationTest {

  /**
   * The interval the {@code @Scheduled(fixedDelayString = ...)} placeholder resolves against. Note
   * that the annotation carries its own {@code :1000} fallback, so this test pins the EFFECTIVE
   * delay and — separately, below — where the effective value came from.
   */
  private static final String INTERVAL_KEY = "app.outbox.relay.interval-ms";

  private static final Duration SHIPPED_DELAY = Duration.ofMillis(1000);

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("order_db")
          .withUsername("order")
          .withPassword("order");

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
    registry.add("INTERNAL_API_KEY", () -> "test-internal-api-key");
    registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
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
   *
   * <p>Shown to discriminate rather than assumed to — and measured at FULL SUITE scope, which the
   * earlier wording here asserted without anyone having run it. Move the shipped {@code
   * interval-ms} to 2000 and a whole-module {@code clean verify} (no {@code -Dtest} scoping) fails
   * exactly two rows, BOTH of them in this class: this one on the delay ({@code expected: 1S but
   * was: 2S}) and {@link #theOneSecondDelay_isBoundFromTheShippedApplicationYml} on the resolved
   * value ({@code expected: "1000" but was: "2000"}). Every other class in the module stays green.
   * The second row was never "something else in the suite" — it is the provenance half below,
   * seeing the same change through its other clause.
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
        .isEqualTo(SHIPPED_DELAY);
  }

  /**
   * The provenance half. Without it the row above is satisfiable by a value this test handed itself
   * — or by the annotation's own {@code :1000} fallback with the shipped key deleted. So: the key
   * must resolve to 1000, and the property source that WINS for it must be the shipped {@code
   * application.yml}. Reading the winning source (rather than merely proving the file contains the
   * key) is what makes a re-declaration in any overlay or {@code @DynamicPropertySource} a red
   * rather than a silent shadow.
   *
   * <p>Neither clause is redundant with the row above, and that was measured rather than argued.
   * Delete {@code interval-ms} from the shipped file: the row above stays GREEN, because the
   * annotation's own fallback supplies 1000, and only the resolved-value assertion here fires. Let
   * this class register {@code INTERVAL_KEY} in its own {@code @DynamicPropertySource} instead: the
   * row above AND the resolved-value assertion both stay green — the value really is 1000 — and
   * only the origin assertion sees it, naming {@code "Dynamic Test Properties"}. Delete either
   * clause and one of those two regressions becomes invisible.
   */
  @Test
  void theOneSecondDelay_isBoundFromTheShippedApplicationYml() {
    assertThat(environment.getProperty("app.scheduling.enabled"))
        .as(
            "nothing may switch scheduling on for this context — the relay must be enabled by"
                + " SchedulingConfig's matchIfMissing default, which is the posture the deploy"
                + " relies on")
        .isNull();

    assertThat(environment.getProperty(INTERVAL_KEY))
        .as("the resolved value behind the @Scheduled placeholder")
        .isEqualTo("1000");

    PropertySource<?> winner = null;
    for (PropertySource<?> source : environment.getPropertySources()) {
      if (source.containsProperty(INTERVAL_KEY)) {
        winner = source;
        break;
      }
    }
    assertThat(winner).as("no property source declares %s at all", INTERVAL_KEY).isNotNull();

    // Boot attaches an aggregating `configurationProperties` view ahead of the real sources; it
    // answers on behalf of whichever source wins and wraps that source's origin, so follow the
    // chain down to the text resource instead of asserting on the view.
    Origin origin = OriginLookup.getOrigin(winner, INTERVAL_KEY);
    while (origin instanceof PropertySourceOrigin wrapper && wrapper.getOrigin() != null) {
      origin = wrapper.getOrigin();
    }

    assertThat(origin)
        .as(
            "%s must win from a config file, not from an in-test source (winning source: %s)",
            INTERVAL_KEY, winner)
        .isInstanceOf(TextResourceOrigin.class);
    assertThat(((TextResourceOrigin) origin).getResource().getFilename())
        .as("and that file must be the SHIPPED one (origin: %s)", origin)
        .isEqualTo("application.yml");
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
