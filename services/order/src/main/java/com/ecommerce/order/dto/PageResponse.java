package com.ecommerce.order.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages) {

  public static <E, T> PageResponse<T> of(Page<E> page, List<T> content) {
    return new PageResponse<>(
        content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }
}
