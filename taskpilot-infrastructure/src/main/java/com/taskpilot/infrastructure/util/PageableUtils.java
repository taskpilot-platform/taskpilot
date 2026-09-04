package com.taskpilot.infrastructure.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

public final class PageableUtils {

    private PageableUtils() {
    }

    public static Pageable sanitize(Pageable pageable, String defaultSortField, Sort.Direction defaultDirection, String... allowedFields) {
        if (!pageable.getSort().isSorted()) {
            return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                    Sort.by(defaultDirection, defaultSortField));
        }

        Set<String> allowed = Set.of(allowedFields);
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(defaultDirection, defaultSortField));
            }
        }
        return pageable;
    }
}
