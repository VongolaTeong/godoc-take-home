package com.godoc.consult.common.web;

import com.godoc.consult.booking.IdempotencyKeyMismatchException;
import com.godoc.consult.booking.InvalidTransitionException;
import com.godoc.consult.booking.SlotInPastException;
import com.godoc.consult.booking.SlotUnavailableException;
import com.godoc.consult.common.NotFoundException;
import com.godoc.consult.common.UnknownPatientException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps domain exceptions to RFC 7807 problem+json responses.
 *
 * Status conventions: 409 for any conflict (slot taken, invalid transition, lost
 * optimistic-lock race), 422 for requests that are well-formed but not satisfiable,
 * 401 for the missing/unknown patient identity stub.
 *
 * Ordered above Spring's built-in problem-details advice (enabled in application.yml),
 * which would otherwise claim binding exceptions like MissingRequestHeaderException
 * before this class is consulted — advice precedence is per-class, not per-method.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(UnknownPatientException.class)
    ProblemDetail unknownPatient(UnknownPatientException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Unknown patient", e.getMessage());
    }

    @ExceptionHandler(SlotUnavailableException.class)
    ProblemDetail slotUnavailable(SlotUnavailableException e) {
        return problem(HttpStatus.CONFLICT, "Slot already booked", e.getMessage());
    }

    @ExceptionHandler(InvalidTransitionException.class)
    ProblemDetail invalidTransition(InvalidTransitionException e) {
        return problem(HttpStatus.CONFLICT, "Invalid state transition", e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail optimisticLockLost(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The booking was changed by another request at the same time; reload and retry.");
    }

    @ExceptionHandler(SlotInPastException.class)
    ProblemDetail slotInPast(SlotInPastException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Slot no longer bookable", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    ProblemDetail idempotencyKeyMismatch(IdempotencyKeyMismatchException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reuse", e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException e) {
        if ("X-Patient-Id".equalsIgnoreCase(e.getHeaderName())) {
            return problem(HttpStatus.UNAUTHORIZED, "Missing patient identity",
                    "The X-Patient-Id header is required (auth stand-in for this take-home).");
        }
        return problem(HttpStatus.BAD_REQUEST, "Missing header", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", detail);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail invalidParams(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid parameter",
                "Parameter '" + e.getName() + "' has an invalid value");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    /**
     * Safety net: BookingService classifies the constraint violations it expects; anything
     * reaching here is still a data conflict, just one we did not anticipate explicitly.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail dataConflict(DataIntegrityViolationException e) {
        return problem(HttpStatus.CONFLICT, "Data conflict",
                "The request conflicts with the current state of the data.");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
