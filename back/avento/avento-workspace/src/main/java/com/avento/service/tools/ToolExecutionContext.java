package com.avento.service.tools;

import com.avento.service.dto.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Service;

/** Carries authenticated ownership through synchronous local and MCP tool calls. */
@Service
public class ToolExecutionContext {

    public static final String ANONYMOUS_SCOPE = "local";

    private final ThreadLocal<Context> current = new ThreadLocal<>();

    public <T> T call(Context context, Callable<T> action) throws Exception {
        Context previous = current.get();
        current.set(context == null ? Context.local() : context);
        try {
            return action.call();
        } finally {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        }
    }

    public Context current() {
        Context context = current.get();
        return context == null ? Context.local() : context;
    }

    public Context fromArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Context.local();
        }
        return new Context(
                parseUuid(arguments.get("_userId")),
                parseLong(arguments.get("_chatId")),
                text(arguments.get("_runId")));
    }

    private UUID parseUuid(Object value) {
        String text = text(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = text(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
