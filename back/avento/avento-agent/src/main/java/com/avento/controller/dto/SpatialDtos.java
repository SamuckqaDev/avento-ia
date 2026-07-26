package com.avento.controller.dto;

public class SpatialDtos {

    public record SpatialClickRequest(
        double xRatio,
        double yRatio,
        Boolean isDouble
    ) {}

    public record SpatialMoveRequest(
        double xRatio,
        double yRatio
    ) {}

    public record SpatialSwipeRequest(
        String direction
    ) {}
}
