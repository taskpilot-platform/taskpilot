package com.taskpilot.infrastructure.util;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;

/**
 * Modern Spring Data JPA Sort Utilities.
 * Enables database-level expressions and functions (e.g. NULLS LAST, LENGTH)
 * without triggering Spring Data's PropertyReferenceException.
 */
public final class JpaSortUtils {

    private JpaSortUtils() {
    }

    /**
     * Creates an unsafe JpaSort instance from raw database expression.
     *
     * @param expression database sort expression, e.g. "deadline ASC NULLS LAST"
     * @return a Sort instance containing the unsafe JPA expression
     */
    public static Sort unsafe(String expression) {
        return JpaSort.unsafe(expression);
    }

    /**
     * Creates an unsafe JpaSort instance with direction and expression.
     *
     * @param direction Sort direction (ASC / DESC)
     * @param expression database sort expression
     * @return a Sort instance containing the unsafe JPA expression
     */
    public static Sort unsafe(Sort.Direction direction, String expression) {
        return JpaSort.unsafe(direction, expression);
    }
}
