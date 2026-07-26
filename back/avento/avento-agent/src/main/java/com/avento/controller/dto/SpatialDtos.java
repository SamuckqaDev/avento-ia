package com.avento.controller.dto;

public class SpatialDtos {

    public record SpatialClickRequest(
        double xRatio,
        double yRatio,
        Boolean isDouble,
        Boolean isRight
    ) {}

    public record SpatialMoveRequest(
        double xRatio,
        double yRatio
    ) {}

    public record SpatialDragRequest(
        double xRatio,
        double yRatio,
        boolean isDown
    ) {}

    public record SpatialSwipeRequest(
        String direction
    ) {}
}
