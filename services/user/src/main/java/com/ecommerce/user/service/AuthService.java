package com.ecommerce.user.service;

import com.ecommerce.user.config.JwtProperties;
import com.ecommerce.user.dto.LoginRequest;
import com.ecommerce.user.dto.RefreshRequest;
import com.ecommerce.user.dto.RegisterRequest;
import com.ecommerce.user.dto.RegisterResponse;
import com.ecommerce.user.dto.TokenResponse;
import com.ecommerce.user.event.OutboxService;
import com.ecommerce.user.exception.EmailAlreadyRegisteredException;
import com.ecommerce.user.exception.InvalidCredentialsException;
import com.ecommerce.user.exception.InvalidRefreshTokenException;
import com.ecommerce.user.model.RefreshToken;
import com.ecommerce.user.model.User;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.security.JwtService;
import com.ecommerce.user.security.TokenHasher;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final TokenHasher tokenHasher;
  private final OutboxService outboxService;
  private final long refreshTtlSeconds;

  public AuthService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      TokenHasher tokenHasher,
      OutboxService outboxService,
      JwtProperties jwtProperties) {
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.tokenHasher = tokenHasher;
    this.outboxService = outboxService;
    this.refreshTtlSeconds = jwtProperties.refreshTokenTtlSeconds();
  }

  @Transactional
  public RegisterResponse register(RegisterRequest request) {
    String email = normalizeEmail(request.email());
    if (userRepository.existsByEmail(email)) {
      throw new EmailAlreadyRegisteredException();
    }
    User user =
        new User(email, passwordEncoder.encode(request.password()), request.name().trim(), "USER");
    User saved = userRepository.save(user);
    outboxService.recordUserRegistered(saved.getId(), saved.getEmail(), Instant.now());
    return RegisterResponse.from(saved);
  }

  @Transactional
  public TokenResponse login(LoginRequest request) {
    String email = normalizeEmail(request.email());
    Optional<User> maybeUser = userRepository.findByEmail(email);
    // Always run a hash comparison to keep timing uniform and avoid user enumeration.
    if (maybeUser.isEmpty()) {
      passwordEncoder.matches(request.password(), "$2a$10$invalidinvalidinvalidinvalidinvalidi");
      throw new InvalidCredentialsException();
    }
    User user = maybeUser.get();
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    return issueTokens(user);
  }

  @Transactional
  public TokenResponse refresh(RefreshRequest request) {
    String hash = tokenHasher.hash(request.refreshToken());
    RefreshToken stored =
        refreshTokenRepository
            .findByTokenHash(hash)
            .orElseThrow(InvalidRefreshTokenException::new);
    if (!stored.isActive(Instant.now())) {
      throw new InvalidRefreshTokenException();
    }
    User user =
        userRepository.findById(stored.getUserId()).orElseThrow(InvalidRefreshTokenException::new);
    // Rotate: invalidate the presented token, then issue a fresh pair.
    stored.setRevoked(true);
    refreshTokenRepository.save(stored);
    return issueTokens(user);
  }

  private TokenResponse issueTokens(User user) {
    String accessToken = jwtService.issueAccessToken(user.getId(), user.roleSet());
    String rawRefresh = tokenHasher.generateRawToken();
    RefreshToken refreshToken =
        new RefreshToken(
            user.getId(),
            tokenHasher.hash(rawRefresh),
            Instant.now().plusSeconds(refreshTtlSeconds));
    refreshTokenRepository.save(refreshToken);
    return TokenResponse.bearer(accessToken, rawRefresh, jwtService.getAccessTtlSeconds());
  }

  private String normalizeEmail(String email) {
    return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
  }
}
