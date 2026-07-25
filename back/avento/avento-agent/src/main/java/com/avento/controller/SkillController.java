package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.*;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.OperationResponse;
import com.avento.api.exception.ApiServiceException;
import com.avento.service.dto.Skill;
import com.avento.service.support.SkillRegistry;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillRegistry skillRegistry;

    public SkillController(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @GetMapping
    public ResponseEntity<BaseResponse<List<Skill>>> listSkills() {
        return ApiResponses.ok(skillRegistry.all());
    }

    @PostMapping
    public ResponseEntity<BaseResponse<Skill>> createSkill(@RequestBody CreateSkillRequest request) {
        try {
            Skill skill = skillRegistry.saveCustomSkill(
                    request.name(), request.description(), request.triggers(), request.body());
            return ApiResponses.created(skill);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiServiceException("Não foi possível salvar a skill.", exception);
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<BaseResponse<OperationResponse>> deleteSkill(@PathVariable String name) {
        try {
            skillRegistry.deleteCustomSkill(name);
            return ApiResponses.ok(new OperationResponse("Skill removida."));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiServiceException("Não foi possível remover a skill.", exception);
        }
    }
}
