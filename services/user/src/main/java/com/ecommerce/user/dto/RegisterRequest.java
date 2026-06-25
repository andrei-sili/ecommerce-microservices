package com.ecommerce.user.dto;

import com.ecommerce.user.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @ValidPassword String password,
    @NotBlank @Size(min = 1, max = 100) String name) {}
