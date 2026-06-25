package com.ecommerce.order.security;

import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injects the authenticated {@link CurrentUser} into controller methods. Reaches here only on
 * authenticated requests (SecurityConfig rejects anonymous ones with 401 first), so the
 * authentication is always present.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

  private static final String ROLE_PREFIX = "ROLE_";

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType().equals(CurrentUser.class);
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Long userId = Long.valueOf((String) authentication.getPrincipal());
    List<String> roles =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .map(a -> a.startsWith(ROLE_PREFIX) ? a.substring(ROLE_PREFIX.length()) : a)
            .toList();
    String bearerToken = (String) authentication.getCredentials();
    return new CurrentUser(userId, roles, bearerToken);
  }
}
