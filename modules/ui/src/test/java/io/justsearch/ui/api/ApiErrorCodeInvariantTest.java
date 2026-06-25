package io.justsearch.ui.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.ErrorClass;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Backend invariants on {@link ApiErrorCode} and {@link ErrorClass}.
 *
 * <p>Per tempdoc 431 §B decision E: this file inherits from the former
 * {@code ApiErrorCodeContractTest}, with tests #1 and #2 (FE catalog ↔ enum sync) removed.
 * Those tests retired when the source of truth for error messages moved to the backend
 * ({@code modules/app-api/src/main/resources/messages/errors.en.properties}). The new
 * coverage analog is {@link io.justsearch.app.api.ErrorMessagePropertiesContractTest} on
 * the {@code app-api} side.
 *
 * <p>Tests #3, #4, #5 here are unrelated to FE state — they assert backend invariants on
 * the enum's internal contract. They survive verbatim.
 */
@DisplayName("ApiErrorCode invariants")
final class ApiErrorCodeInvariantTest {

    @Test
    @DisplayName("every ApiErrorCode has a non-null ErrorClass")
    void everyCodeHasErrorClass() {
        for (ApiErrorCode code : ApiErrorCode.values()) {
            assertThat(code.errorClass())
                    .as("ApiErrorCode.%s must have a non-null ErrorClass", code.name())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("all ErrorClass values are used by at least one ApiErrorCode")
    void allErrorClassesAreUsed() {
        Set<ErrorClass> usedClasses = Arrays.stream(ApiErrorCode.values())
                .map(ApiErrorCode::errorClass)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(ErrorClass.class)));

        assertThat(usedClasses)
                .as("Every ErrorClass value should be used by at least one ApiErrorCode")
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ErrorClass.class));
    }

    @Test
    @DisplayName("isRetryable() is consistent with ErrorClass.TRANSIENT")
    void retryableConsistentWithTransient() {
        for (ApiErrorCode code : ApiErrorCode.values()) {
            boolean expected = code.errorClass() == ErrorClass.TRANSIENT;
            assertThat(code.isRetryable())
                    .as("ApiErrorCode.%s: isRetryable() should match TRANSIENT class", code.name())
                    .isEqualTo(expected);
        }
    }
}
