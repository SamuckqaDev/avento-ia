package com.avento.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.avento.service.tools.ToolCapabilityRegistry;
import org.junit.jupiter.api.Test;

class CapabilityControllerTest {

    @Test
    void listsRegisteredToolCapabilities() {
        CapabilityController controller = new CapabilityController(new ToolCapabilityRegistry());

        var response = controller.listCapabilities().getBody().getData();

        assertTrue(response.tools().stream()
                .anyMatch(tool -> "write_file".equals(tool.name())
                        && "HIGH".equals(tool.riskLevel().name())
                        && "APPROVAL_REQUIRED".equals(tool.approvalPolicy().name())));
        assertTrue(response.tools().stream()
                .anyMatch(tool -> "open_browser_tab".equals(tool.name()) && tool.directAutoExecutable()));
    }
}
