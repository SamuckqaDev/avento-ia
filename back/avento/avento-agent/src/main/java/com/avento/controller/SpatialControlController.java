package com.avento.controller;

import com.avento.controller.dto.SpatialDtos.SpatialClickRequest;
import com.avento.controller.dto.SpatialDtos.SpatialDragRequest;
import com.avento.controller.dto.SpatialDtos.SpatialMoveRequest;
import com.avento.controller.dto.SpatialDtos.SpatialSwipeRequest;
import com.avento.service.SpatialControlService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spatial")
@RequiredArgsConstructor
public class SpatialControlController {

    private final SpatialControlService spatialControlService;

    @PostMapping("/click")
    public ResponseEntity<Map<String, Object>> handleClick(@RequestBody SpatialClickRequest request) {
        boolean isDouble = Boolean.TRUE.equals(request.isDouble());
        boolean isRight = Boolean.TRUE.equals(request.isRight());
        boolean success =
                spatialControlService.executeSpatialClick(request.xRatio(), request.yRatio(), isDouble, isRight);
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/move")
    public ResponseEntity<Map<String, Object>> handleMove(@RequestBody SpatialMoveRequest request) {
        boolean success = spatialControlService.executeSpatialMove(request.xRatio(), request.yRatio());
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/drag")
    public ResponseEntity<Map<String, Object>> handleDrag(@RequestBody SpatialDragRequest request) {
        boolean success =
                spatialControlService.executeSpatialDrag(request.xRatio(), request.yRatio(), request.isDown());
        return ResponseEntity.ok(Map.of("success", success));
    }

    @PostMapping("/swipe")
    public ResponseEntity<Map<String, Object>> handleSwipe(@RequestBody SpatialSwipeRequest request) {
        boolean success = spatialControlService.executeSpatialSwipe(request.direction());
        return ResponseEntity.ok(Map.of("success", success));
    }
}
