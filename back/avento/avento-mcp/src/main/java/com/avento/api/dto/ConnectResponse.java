package com.avento.api.dto;

import com.avento.service.dto.ConnectionResult;
import java.util.List;

public record ConnectResponse(boolean connected, List<ConnectionResult> results) {}
