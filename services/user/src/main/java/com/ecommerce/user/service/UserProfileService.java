package com.ecommerce.user.service;

import com.ecommerce.user.dto.ChangePasswordRequest;
import com.ecommerce.user.dto.ProfileResponse;
import com.ecommerce.user.dto.UpdateProfileRequest;
import com.ecommerce.user.exception.InvalidCredentialsException;
import com.ecommerce.user.exception.UserNotFoundException;
import com.ecommerce.user.model.User;
import com.ecommerce.user.repository.RefreshTokenRepository;
import com.ecommerce.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;

  public UserProfileService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      PasswordEncoder passwordEncoder) {
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.passwordEncoder = passwordEncoder;
  }

  @Transactional(readOnly = true)
  public ProfileResponse getProfile(Long userId) {
    return ProfileResponse.from(loadUser(userId));
  }

  @Transactional
  public ProfileResponse updateName(Long userId, UpdateProfileRequest request) {
    User user = loadUser(userId);
    user.setName(request.name().trim());
    return ProfileResponse.from(userRepository.save(user));
  }

  @Transactional
  public void changePassword(Long userId, ChangePasswordRequest request) {
    User user = loadUser(userId);
    if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
    user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    userRepository.save(user);
    // Revoke all refresh tokens so existing sessions cannot continue after a password change.
    refreshTokenRepository.revokeAllForUser(userId);
  }

  private User loadUser(Long userId) {
    return userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
  }
}
