package com.avento.service.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards against a whole class of runtime bug: a local tool can have a dispatch {@code case} in
 * McpController and be exposed to the model, yet be missing from {@link LocalToolNames#ALL}. The
 * router only dispatches names present in that set, so a missing entry makes every call return
 * "Tool not found or server disconnected". This asserts every dispatched tool is routable.
 */
class LocalToolRoutingTest {

    @Test
    void everyDispatchedLocalToolIsInTheRouterAllowList() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/avento/controller/McpController.java"));
        Matcher matcher = Pattern.compile("case \"([a-z_]+)\" ->").matcher(source);
        Set<String> dispatched = new HashSet<>();
        while (matcher.find()) {
            dispatched.add(matcher.group(1));
        }

        assertThat(dispatched)
                .as("expected to find dispatch cases in McpController")
                .isNotEmpty();
        assertThat(LocalToolNames.ALL)
                .as("tools dispatched by McpController must be routable via LocalToolNames.ALL")
                .containsAll(dispatched);
    }
}
