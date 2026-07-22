package com.avento.service.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RunToolPolicyRegistryTest {

    private final RunToolPolicyRegistry registry = new RunToolPolicyRegistry();

    @Test
    void unknownRunHasNoRestriction() {
        assertThat(registry.allowed("run-x")).isEmpty();
        assertThat(registry.allowed(null)).isEmpty();
    }

    @Test
    void storesAndReturnsTheAllowList() {
        registry.allow("run-1", Set.of("read_file", "write_file"));

        assertThat(registry.allowed("run-1")).containsExactlyInAnyOrder("read_file", "write_file");
    }

    @Test
    void emptyOrNullAllowListIsIgnored() {
        registry.allow("run-2", Set.of());
        registry.allow("run-3", null);

        assertThat(registry.allowed("run-2")).isEmpty();
        assertThat(registry.allowed("run-3")).isEmpty();
    }

    @Test
    void clearRemovesTheRestriction() {
        registry.allow("run-4", Set.of("terminal_run"));
        registry.clear("run-4");

        assertThat(registry.allowed("run-4")).isEmpty();
    }
}
