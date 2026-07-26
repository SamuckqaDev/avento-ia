package com.avento.controller.dto;

public class SpatialDtos {

    public record SpatialClickRequest(
        double xRatio,
        double yRatio,
        Boolean isDouble
    ) {}

    public record SpatialSwipeRequest(
        String direction
    ) {}
}
