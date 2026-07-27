package com.avento.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.api.dto.ApiErrorData;
import com.avento.api.dto.BaseResponse;
import com.avento.config.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiResponseContractTest {

    @Test
    void successKeepsEmptyCollectionsAsEmptyArrays() {
        ResponseEntity<BaseResponse<List<String>>> response = ApiResponses.ok(List.of());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(200);
        assertThat(response.getBody().getCode()).isEqualTo(ApiCodes.SUCCESS);
        assertThat(response.getBody().getData()).isEmpty();
    }

    @Test
    void errorsAreReturnedInsideDataWithRequestTrace() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/projects/analyze");
        request.setAttribute(RequestTraceFilter.TRACE_ID_ATTRIBUTE, "trace-test");

        ResponseEntity<BaseResponse<ApiErrorData>> response = new ApiExceptionHandler()
                .handleIllegalArgumentException(new IllegalArgumentException("path is required"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(400);
        assertThat(response.getBody().getCode()).isEqualTo(ApiCodes.INVALID_REQUEST);
        assertThat(response.getBody().getData().getMessage()).isEqualTo("path is required");
        assertThat(response.getBody().getData().getPath()).isEqualTo("/api/projects/analyze");
        assertThat(response.getBody().getData().getTraceId()).isEqualTo("trace-test");
        assertThat(response.getBody().getData().getErrors()).isEmpty();
    }
}
