package com.ecommerce.user.config;

import com.ecommerce.user.model.User;
import com.ecommerce.user.repository.UserRepository;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the first ADMIN from ADMIN_EMAIL / ADMIN_PASSWORD (env). Idempotent: inserts only if no
 * admin exists yet. The password is BCrypt-hashed at runtime — no secret or fixed hash is ever
 * committed (the secret lives only in the environment).
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

  private final AdminBootstrapProperties properties;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  public AdminBootstrapRunner(
      AdminBootstrapProperties properties,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder) {
    this.properties = properties;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!properties.isConfigured()) {
      log.info("Admin bootstrap skipped: ADMIN_EMAIL/ADMIN_PASSWORD not configured");
      return;
    }
    if (userRepository.existsByRolesContaining("ADMIN")) {
      log.info("Admin bootstrap skipped: an ADMIN user already exists");
      return;
    }
    String email = properties.email().trim().toLowerCase(Locale.ROOT);
    if (userRepository.existsByEmail(email)) {
      log.warn("Admin bootstrap skipped: ADMIN_EMAIL already exists as a non-admin account");
      return;
    }
    User admin = new User(email, passwordEncoder.encode(properties.password()), "Administrator", "ADMIN");
    userRepository.save(admin);
    log.info("Admin bootstrap: seeded initial ADMIN account");
  }
}
