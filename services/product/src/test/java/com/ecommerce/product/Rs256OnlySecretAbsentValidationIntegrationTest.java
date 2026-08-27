package com.ecommerce.product;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.config.JwtProperties;
import com.ecommerce.product.model.Category;
import com.ecommerce.product.repository.CategoryRepository;
import com.ecommerce.product.repository.ProductRepository;
import com.ecommerce.product.support.JwtTestKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Phase-3 production shape: {@code accepted-algs} rides the shipped yaml default (RS256) and the
 * {@code security.jwt.secret} property is ENTIRELY ABSENT — the exact posture the deploy ships.
 *
 * <p>Every other dual-accept suite injects a test secret via {@link
 * com.ecommerce.product.support.AbstractIntegrationTest}, so none of them proves the secret can be
 * missing. This standalone boot deliberately registers no secret and no {@code accepted-algs}
 * override, coupling the test to BOTH phase-3 edits: if the yaml default regressed to a dual
 * allowlist, {@code loadHmacKey} would demand the now-absent secret and the context would fail to
 * start.
 *
 * <p><b>Which row carries which claim — read this before trusting the headline.</b> The absence of
 * the secret is asserted by exactly ONE row, {@link
 * #secretProperty_bindsNull_provingTheShippedYamlCarriesNoSecret()}. The two request rows cannot
 * see a resurrected {@code secret:} and must not be read as evidence for it: the RS256 row passes
 * regardless, because RS256 validation never consults the secret; and the HS256 forgery is rejected
 * at the ALLOWLIST — {@code loadHmacKey} returns {@code null} early when HS256 is off — so it would
 * still return 401 with a secret fully bound. Measured, not reasoned: resurrecting a shipped {@code
 * secret:} reddens the binding row alone and leaves both request rows green. Deleting the binding
 * row therefore does not weaken this class's coverage, it removes it entirely.
 *
 * <p>Unlike cart's twin, product's shipped yaml binds {@code security.internal-api-key} with no
 * default, so it is registered here too — otherwise {@code SecurityConfig} fails the context at
 * boot before either assertion runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Rs256OnlySecretAbsentValidationIntegrationTest {

  private static final String SUBJECT = "7";
  private static final String PRODUCT_PATH = "/api/v1/products";

  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("product_db")
          .withUsername("product")
          .withPassword("product");

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
    // Wire only the RS256 public key. accepted-algs is left to the yaml default (RS256) and the
    // secret is deliberately never registered — this is the whole point of the test. The internal
    // API key has no yaml default in product, so it must be provided or the context fails to boot.
    registry.add(
        "security.jwt.public-keys[" + JwtTestKeys.KID_A + "]", () -> JwtTestKeys.PUBLIC_KEY_PATH_A);
    registry.add("security.internal-api-key", () -> "test-internal-api-key");
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private JwtProperties jwtProperties;

  private long categoryId;

  @BeforeEach
  void seedCategory() {
    productRepository.deleteAll();
    categoryRepository.deleteAll();
    categoryId = categoryRepository.save(new Category("Apparel", "apparel")).getId();
  }

  /**
   * The class's headline claim, and the only row that can detect its violation.
   *
   * <p>{@code security.jwt.secret} is absent from the shipped {@code application.yml} (phase-3
   * fail-closed posture, D3: re-widening needs a git revert plus a NEW secret, never a live env
   * toggle) and this standalone context registers none, so the binding is {@code null}. Nothing
   * else in the suite asserts this: a resurrected {@code secret:} is inert at runtime while HS256
   * is off the allowlist — {@code JwtService.loadHmacKey} returns early without reading it — so it
   * changes no status code, no envelope and no startup outcome. That inertness is exactly why the
   * property needs a direct assertion rather than a behavioural one.
   */
  @Test
  void secretProperty_bindsNull_provingTheShippedYamlCarriesNoSecret() {
    assertNull(
        jwtProperties.secret(),
        "security.jwt.secret must bind null: the shipped application.yml carries no secret: key"
            + " (phase-3 fail-closed posture) and this context registers none");
  }

  @Test
  void rs256Token_accepted_whenSecretPropertyAbsent() throws Exception {
    String token = JwtTestKeys.mintRs256(SUBJECT, JwtTestKeys.KID_A, JwtTestKeys.KEY_PAIR_A);
    mockMvc
        .perform(
            post(PRODUCT_PATH)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(productBody("RS256-ABSENT-SECRET")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sku", is("RS256-ABSENT-SECRET")))
        .andExpect(jsonPath("$.available", is(true)));
  }

  @Test
  void freshHs256Token_rejected401_whenSecretPropertyAbsent() throws Exception {
    String token = JwtTestKeys.mintHs256(SUBJECT);
    mockMvc
        .perform(
            post(PRODUCT_PATH)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(productBody("HS256-ABSENT-SECRET")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error", is("UNAUTHORIZED")))
        .andExpect(jsonPath("$.message", is("Authentication required")))
        .andExpect(jsonPath("$.path", is(PRODUCT_PATH)))
        .andExpect(jsonPath("$.timestamp", notNullValue()));
  }

  private String productBody(String sku) {
    return "{\"sku\":\""
        + sku
        + "\",\"name\":\"Tee\",\"price\":19.99,\"currency\":\"EUR\",\"category_id\":"
        + categoryId
        + ",\"stock_quantity\":10}";
  }
}
