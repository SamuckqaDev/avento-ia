package com.avento.dto;

import java.util.List;

public record ConnectResponse(boolean connected, List<ConnectionResult> results) {}
