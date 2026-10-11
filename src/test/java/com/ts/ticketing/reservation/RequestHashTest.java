package com.ts.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RequestHashTest {

    @Test
    void isSha256HexOfShowIdColonSortedSeats() {
        // printf 'show-1:A1,A2' | sha256sum
        assertThat(RequestHash.of("show-1", List.of("A1", "A2")))
                .isEqualTo("b3867953a8ea18323f947138e0bee93c1d9422ed74a101b22751dae05af2a44d");
    }

    @Test
    void differsPerShow() {
        assertThat(RequestHash.of("show-1", List.of("A1"))).isNotEqualTo(RequestHash.of("show-2", List.of("A1")));
    }
}
