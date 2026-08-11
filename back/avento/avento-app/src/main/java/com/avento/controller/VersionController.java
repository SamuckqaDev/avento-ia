package com.avento.controller;

import com.avento.controller.dto.VersionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class VersionController {

    private final BuildProperties buildProperties;

    @GetMapping("/api/version")
    public VersionResponse version() {
        return new VersionResponse(buildProperties.getVersion(), buildProperties.getTime());
    }
}
