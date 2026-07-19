package com.avento.service.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PiperCommand {

    private PiperCommand() {}

    public static List<String> create(Path launcher, Path model, Path output) {
        return create(launcher, model, output, 1.0, 0.667, 0.8, 0.2);
    }

    public static List<String> create(
            Path launcher,
            Path model,
            Path output,
            double lengthScale,
            double noiseScale,
            double noiseWidthScale,
            double sentenceSilence) {
        List<String> command = new ArrayList<>();
        Path virtualenvPython = launcher.getParent().resolve("python");
        if (Files.isExecutable(virtualenvPython) && isPythonLauncher(launcher)) {
            command.add(virtualenvPython.toString());
        }
        command.add(launcher.toString());
        command.add("-m");
        command.add(model.toString());
        command.add("-f");
        command.add(output.toString());
        command.add("--length-scale");
        command.add(Double.toString(lengthScale));
        command.add("--noise-scale");
        command.add(Double.toString(noiseScale));
        command.add("--noise-w-scale");
        command.add(Double.toString(noiseWidthScale));
        command.add("--sentence-silence");
        command.add(Double.toString(sentenceSilence));
        return List.copyOf(command);
    }

    static boolean isPythonLauncher(Path launcher) {
        try (BufferedReader reader = Files.newBufferedReader(launcher, StandardCharsets.UTF_8)) {
            String firstLine = reader.readLine();
            return firstLine != null && firstLine.startsWith("#!") && firstLine.contains("python");
        } catch (IOException ignored) {
            return false;
        }
    }
}
