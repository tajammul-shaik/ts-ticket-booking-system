package com.ts.ticketing.show;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * FR-02 body. price_paise is a JSON integer: floats and strings are rejected by Jackson
 * (coercion is disabled in application.yml), so "250.5" can never be stored as 250 (BR-13).
 */
public record CreateShowRequest(
        @NotBlank @Size(max = 100) String name,
        @NotEmpty @Size(max = ShowService.MAX_SEATS)
        List<@NotNull @Pattern(regexp = SeatLabels.PATTERN, message = SeatLabels.PATTERN_MESSAGE) String> seats,
        @NotNull @PositiveOrZero @Max(ShowService.MAX_PRICE_PAISE) Long pricePaise,
        @Min(1) @Max(ShowService.MAX_PER_USER_LIMIT) Integer perUserLimit) {
}
