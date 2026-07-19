package com.avento.service.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record PreparedImageWorkflow(
        ObjectNode workflow,
        boolean referenceApplied,
        boolean identityReferenceApplied,
        boolean structureReferenceApplied,
        boolean poseApplied,
        String detailModeApplied) {}
