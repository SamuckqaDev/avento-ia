package com.avento.service.dto;

import java.util.List;

public record AdherenceReview(boolean available, int score, List<String> missing, String correction) {
    public static AdherenceReview unavailable() {
        return new AdherenceReview(false, -1, List.of(), "");
    }
}
