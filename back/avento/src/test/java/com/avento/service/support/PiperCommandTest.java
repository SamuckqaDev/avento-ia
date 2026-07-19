package com.avento.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PiperCommandTest {

    @Test
    void usesVirtualenvPythonWhenLauncherShebangPointsToPython(@TempDir Path tempDir) throws IOException {
        Path binDirectory = Files.createDirectories(tempDir.resolve(".venv/bin"));
        Path python = Files.writeString(binDirectory.resolve("python"), "python");
        Path launcher =
                Files.writeString(binDirectory.resolve("piper"), "#!/old/location/.venv/bin/python3\nprint('piper')\n");
        python.toFile().setExecutable(true);
        launcher.toFile().setExecutable(true);
        Path model = tempDir.resolve("voice.onnx");
        Path output = tempDir.resolve("voice.wav");

        assertEquals(
                List.of(
                        python.toString(),
                        launcher.toString(),
                        "-m",
                        model.toString(),
                        "-f",
                        output.toString(),
                        "--length-scale",
                        "1.0",
                        "--noise-scale",
                        "0.667",
                        "--noise-w-scale",
                        "0.8",
                        "--sentence-silence",
                        "0.2"),
                PiperCommand.create(launcher, model, output));
    }

    @Test
    void executesNativeLauncherDirectly(@TempDir Path tempDir) throws IOException {
        Path launcher = Files.writeString(tempDir.resolve("piper"), "native executable");
        launcher.toFile().setExecutable(true);
        Path model = tempDir.resolve("voice.onnx");
        Path output = tempDir.resolve("voice.wav");

        assertEquals(
                List.of(
                        launcher.toString(),
                        "-m",
                        model.toString(),
                        "-f",
                        output.toString(),
                        "--length-scale",
                        "1.0",
                        "--noise-scale",
                        "0.667",
                        "--noise-w-scale",
                        "0.8",
                        "--sentence-silence",
                        "0.2"),
                PiperCommand.create(launcher, model, output));
    }
}
