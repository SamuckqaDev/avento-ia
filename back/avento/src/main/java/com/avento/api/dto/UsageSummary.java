package com.avento.api.dto;

import com.avento.service.dto.DayTotal;
import com.avento.service.dto.ModelTotal;
import java.util.List;

public record UsageSummary(long total, List<ModelTotal> byModel, List<DayTotal> byDay) {}
