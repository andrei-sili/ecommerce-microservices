package com.ecommerce.product.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.product.dto.CategoryResponse;
import com.ecommerce.product.exception.ConflictException;
import com.ecommerce.product.security.JwtService;
import com.ecommerce.product.security.RestAccessDeniedHandler;
import com.ecommerce.product.security.RestAuthenticationEntryPoint;
import com.ecommerce.product.service.CategoryService;
import com.ecommerce.product.support.TestJwt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CategoryController.class)
@Import({
  com.ecommerce.product.config.SecurityConfig.class,
  JwtService.class,
  RestAuthenticationEntryPoint.class,
  RestAccessDeniedHandler.class,
  com.ecommerce.product.exception.GlobalExceptionHandler.class
})
@TestPropertySource(properties = "security.jwt.secret=" + TestJwt.SECRET)
class CategoryControllerWebTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private CategoryService categoryService;

  private static final String ADMIN = TestJwt.bearer(TestJwt.token("1", List.of("ADMIN")));
  private static final String USER = TestJwt.bearer(TestJwt.token("2", List.of("USER")));

  @Test
  void listCategories_isPublic() throws Exception {
    when(categoryService.list())
        .thenReturn(List.of(new CategoryResponse(3L, "Apparel", "apparel")));
    mockMvc
        .perform(get("/api/v1/categories"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].slug").value("apparel"));
  }

  @Test
  void createCategory_admin_returns201() throws Exception {
    when(categoryService.create(any())).thenReturn(new CategoryResponse(3L, "Apparel", "apparel"));
    mockMvc
        .perform(
            post("/api/v1/categories")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apparel\",\"slug\":\"apparel\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(3));
  }

  @Test
  void createCategory_noToken_returns401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apparel\",\"slug\":\"apparel\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(categoryService);
  }

  @Test
  void createCategory_nonAdmin_returns403() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/categories")
                .header("Authorization", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apparel\",\"slug\":\"apparel\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(categoryService);
  }

  @Test
  void createCategory_duplicateSlug_returns409() throws Exception {
    when(categoryService.create(any()))
        .thenThrow(new ConflictException("SLUG_ALREADY_EXISTS", "exists"));
    mockMvc
        .perform(
            post("/api/v1/categories")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apparel\",\"slug\":\"apparel\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("SLUG_ALREADY_EXISTS"));
  }

  @Test
  void createCategory_invalidSlug_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/categories")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apparel\",\"slug\":\"Not A Slug\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    verifyNoInteractions(categoryService);
  }
}
