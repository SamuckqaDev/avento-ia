package com.avento.controller;

import com.avento.api.ApiResponses;
import com.avento.api.dto.BaseResponse;
import com.avento.api.dto.OperationResponse;
import com.avento.api.exception.ApiServiceException;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {

    private static final String CSV_FILE = "data/metrics.csv";

    @PostMapping("/api/metrics")
    public ResponseEntity<BaseResponse<OperationResponse>> saveMetrics(@RequestBody Map<String, Object> metrics) {
        try {
            File dir = new File("data");
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File file = new File(CSV_FILE);
            boolean isNew = !file.exists();

            try (FileWriter fw = new FileWriter(file, true);
                    PrintWriter pw = new PrintWriter(fw)) {

                if (isNew) {
                    pw.println("Timestamp,PromptTokens,CompletionTokens,TotalTokens,DurationSecs");
                }

                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                Object prompt = metrics.getOrDefault("promptTokens", 0);
                Object comp = metrics.getOrDefault("completionTokens", 0);
                Object total = metrics.getOrDefault("totalTokens", 0);
                Object duration = metrics.getOrDefault("durationSecs", "0.0");

                pw.printf("%s,%s,%s,%s,%s%n", timestamp, prompt, comp, total, duration);
            }

            return ApiResponses.ok(new OperationResponse("Métricas salvas com sucesso."));
        } catch (Exception e) {
            throw new ApiServiceException("Erro ao salvar métricas.", e);
        }
    }

    @org.springframework.web.bind.annotation.GetMapping("/api/metrics")
    public ResponseEntity<BaseResponse<Map<String, Object>>> getMetrics() {
        try {
            File file = new File(CSV_FILE);
            long totalPrompt = 0;
            long totalComp = 0;
            long total = 0;
            double sumDuration = 0.0;
            int count = 0;
            if (file.exists()) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file))) {
                    br.readLine(); // skip header line
                    String line;
                    while ((line = br.readLine()) != null) {
                        String[] parts = line.split(",");
                        if (parts.length >= 5) {
                            try {
                                totalPrompt += Long.parseLong(parts[1].trim());
                                totalComp += Long.parseLong(parts[2].trim());
                                total += Long.parseLong(parts[3].trim());
                                sumDuration += Double.parseDouble(parts[4].trim());
                                count++;
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
            double avgDuration = count > 0 ? Math.round((sumDuration / count) * 10.0) / 10.0 : 0.0;
            Map<String, Object> summary = Map.of(
                    "totalPromptTokens", totalPrompt,
                    "totalCompletionTokens", totalComp,
                    "totalTokens", total,
                    "avgDurationSecs", avgDuration,
                    "runsCount", count);
            return ApiResponses.ok(summary);
        } catch (Exception e) {
            return ApiResponses.ok(Map.of(
                    "totalPromptTokens", 0,
                    "totalCompletionTokens", 0,
                    "totalTokens", 0,
                    "avgDurationSecs", 0.0,
                    "runsCount", 0));
        }
    }
}
