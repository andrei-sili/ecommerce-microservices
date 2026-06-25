package com.ecommerce.user.controller;

import com.ecommerce.user.dto.ChangePasswordRequest;
import com.ecommerce.user.dto.ProfileResponse;
import com.ecommerce.user.dto.UpdateProfileRequest;
import com.ecommerce.user.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

  private final UserProfileService userProfileService;

  public UserController(UserProfileService userProfileService) {
    this.userProfileService = userProfileService;
  }

  @GetMapping("/me")
  public ResponseEntity<ProfileResponse> getMe(@AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok(userProfileService.getProfile(userId));
  }

  @PutMapping("/me")
  public ResponseEntity<ProfileResponse> updateMe(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody UpdateProfileRequest request) {
    return ResponseEntity.ok(userProfileService.updateName(userId, request));
  }

  @PutMapping("/me/password")
  public ResponseEntity<Void> changePassword(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody ChangePasswordRequest request) {
    userProfileService.changePassword(userId, request);
    return ResponseEntity.noContent().build();
  }
}
