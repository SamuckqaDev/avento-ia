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
}
