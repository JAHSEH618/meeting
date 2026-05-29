package com.meeting.api.adapter.person;

import com.meeting.api.adapter.meeting.TenantContextHolder;
import com.meeting.api.client.common.ApiResponse;
import com.meeting.api.client.common.ErrorCode;
import com.meeting.api.client.common.ErrorInfo;
import com.meeting.api.client.person.CreatePersonCommand;
import com.meeting.api.client.person.PersonDTO;
import com.meeting.api.client.person.PersonDuplicateException;
import com.meeting.api.client.person.PersonFacade;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/persons")
public class PersonController {
    private final PersonFacade personFacade;

    public PersonController(PersonFacade personFacade) {
        this.personFacade = personFacade;
    }

    @GetMapping
    public ApiResponse<List<PersonDTO>> search(
        @RequestParam(value = "q", required = false) String q,
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId
    ) {
        return ApiResponse.ok(
            personFacade.search(TenantContextHolder.currentTenantId(), q, 20),
            requestId,
            traceId
        );
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PersonDTO>> create(
        @RequestHeader("X-Request-Id") String requestId,
        @RequestHeader("X-Trace-Id") String traceId,
        @RequestHeader("Idempotency-Key") String idempotencyKey,
        @RequestBody CreatePersonRequest request
    ) {
        try {
            PersonDTO dto = personFacade.create(new CreatePersonCommand(
                TenantContextHolder.currentTenantId(),
                request.displayName(),
                request.email(),
                request.externalId(),
                Boolean.TRUE.equals(request.forceCreate()),
                TenantContextHolder.currentUserId(),
                idempotencyKey,
                requestId,
                traceId
            ));
            return ResponseEntity.ok(ApiResponse.ok(dto, requestId, traceId));
        } catch (PersonDuplicateException ex) {
            ErrorInfo error = new ErrorInfo(
                ErrorCode.PERSON_DUPLICATE,
                "same displayName already exists",
                false,
                Map.of("matches", ex.matches())
            );
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.failed(error, requestId, traceId));
        }
    }

    public record CreatePersonRequest(
        String displayName,
        String email,
        String externalId,
        Boolean forceCreate
    ) {
    }
}
