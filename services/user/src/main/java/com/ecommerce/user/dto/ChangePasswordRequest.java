package com.ecommerce.user.dto;

import com.ecommerce.user.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(
    @NotBlank String currentPassword, @NotBlank @ValidPassword String newPassword) {}
