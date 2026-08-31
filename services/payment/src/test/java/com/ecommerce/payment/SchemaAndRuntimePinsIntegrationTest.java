package com.ecommerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.payment.client.OrderClient;
import com.ecommerce.payment.relay.OutboxRelay;
import com.ecommerce.payment.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Two invariants that survive only if something re-checks them after a dependency major: the
 * DB-level double-charge guard (AC-5.11) and the absence of a Spring Data AOT opt-out (D15).
 */
class SchemaAndRuntimePinsIntegrationTest extends AbstractIntegrationTest {

  /**
   * The exact {@code indexdef} Postgres reports for the partial unique index created by {@code
   * V5__enforce_single_active_payment_per_order.sql}, captured on 3.5.16 / Flyway 11.7.2 and
   * byte-identical to {@code agent_docs/baselines/boot4/payment-indexes.psv}.
   *
   * <p>Committed here rather than left in a baseline file because {@code agent_docs/} is gitignored
   * and never reaches the repository — a constant whose provenance lives only in an untracked file
   * is a string the next reader inherits with no history.
   */
  private static final String UIX_PAYMENTS_ORDER_ACTIVE_DEF =
      "CREATE UNIQUE INDEX uix_payments_order_active ON public.payments USING btree (order_id)"
          + " WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying,"
          + " 'SUCCEEDED'::character varying])::text[]))";

  /**
   * The four opt-outs Spring Data reads, taken from the strings in {@code
   * spring-data-commons-4.1.1.jar} rather than from memory: {@code grep -rhoaE
   * 'spring\.aot[a-zA-Z.-]*'} over the unpacked jar returns exactly these plus {@code
   * spring.aot.processing}, which is the build-time flag rather than a repository switch.
   */
  private static final List<String> AOT_OPT_OUT_KEYS =
      List.of(
          "spring.aot.repositories.enabled",
          "spring.aot.repositories.metadata.enabled",
          "spring.aot.data.accessors.enabled");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ConfigurableEnvironment environment;

  // Same override shape as the rest of the suite so this class reuses the cached context.
  @MockitoBean private OrderClient orderClient;
  @MockitoBean private OutboxRelay outboxRelay;

  /**
   * AC-5.11: the double-charge guard survives the Flyway 12 major.
   *
   * <p>{@code ddl-auto: validate} is armed and it is what turned the silent-Flyway failure red —
   * but validate compares TABLES and COLUMNS and <b>cannot see indexes at all</b>. So D3 alone
   * leaves the money guard unprotected: Flyway could stop applying V5, validate would still pass,
   * and two concurrent charges on one order with different idempotency keys would both reach the
   * gateway. The friendly {@code existsByOrderIdAndStatus} pre-check in {@code PaymentService} is a
   * race, not a guard; this index is the backstop.
   *
   * <p>The whole {@code indexdef} is compared, not just the name. A partial unique index whose
   * {@code WHERE} clause had lost {@code PENDING} would still be called {@code
   * uix_payments_order_active} while permitting exactly the double charge it exists to stop.
   */
  @Test
  void partialUniqueIndex_forSingleActivePaymentPerOrder_isPresentWithAnUnchangedDefinition() {
    List<String> defs =
        jdbcTemplate.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            String.class,
            "uix_payments_order_active");

    assertThat(defs)
        .as(
            "V5's partial unique index must exist after Flyway 12 — ddl-auto: validate cannot see"
                + " indexes, so nothing else in this suite would notice it missing")
        .hasSize(1);
    assertThat(defs.get(0))
        .as("and its definition must be unchanged, WHERE clause included")
        .isEqualTo(UIX_PAYMENTS_ORDER_ACTIVE_DEF);
  }

  /**
   * D15: Spring Data AOT repositories are on by default at Spring Data 2026.0 and this service does
   * not opt out — the suite passes with them enabled.
   *
   * <p>Asserted against the live {@link ConfigurableEnvironment} rather than by grepping the tree,
   * because a grep for an ABSENCE is a silent probe: it reads identically whether the tree is clean
   * or the pattern was wrong. Reading the Environment also covers the ways a grep would miss — an
   * opt-out arriving through {@code JAVA_TOOL_OPTIONS}, a {@code -D} system property, or a
   * container environment variable — and it fails with the offending source named.
   */
  @Test
  void noSpringDataAotOptOutIsConfigured_anywhereInTheEnvironment() {
    List<String> offenders = new ArrayList<>();
    for (String key : AOT_OPT_OUT_KEYS) {
      String value = environment.getProperty(key);
      if (value != null) {
        offenders.add(key + "=" + value + " (from " + sourceOf(key) + ")");
      }
    }

    assertThat(offenders)
        .as("no Spring Data AOT opt-out may be configured; found: %s", offenders)
        .isEmpty();
  }

  /**
   * Positive control for the row above. Without it, "no offenders" is satisfied equally by a clean
   * environment and by a lookup that can never find anything — the silent-probe failure. So the
   * same lookup is pointed at a key this context certainly DOES set, and must return it.
   */
  @Test
  void theAotLookupCanActuallyFindAKeyThatIsSet() {
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
        .as("the same Environment lookup must be able to return a value that IS present")
        .isEqualTo("validate");
    assertThat(sourceOf("spring.jpa.hibernate.ddl-auto"))
        .as("and it must be able to name where that value came from")
        .isNotEqualTo("<no source>");
  }

  private String sourceOf(String key) {
    for (PropertySource<?> source : environment.getPropertySources()) {
      if (source instanceof EnumerablePropertySource<?> && source.containsProperty(key)) {
        return source.getName();
      }
    }
    return "<no source>";
  }
}
