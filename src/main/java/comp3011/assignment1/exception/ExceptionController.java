package comp3011.assignment1.exception;

import comp3011.assignment1.models.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ExceptionController {
    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(produces = MediaType.APPLICATION_JSON_VALUE)
    public ErrorResponse handleMultipleShutdown(MultipleShutdownException ex) {
        return new ErrorResponse(
                Instant.now().toString(),
                409,
                "Conflict",
                ex.getMessage(),
                "/api/v1/admin/shutdown"
        );
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value=Exception.class,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ErrorResponse handleInternalServerError(Exception ex, HttpServletRequest httpServlet) {
        return new ErrorResponse(
                Instant.now().toString(),
                500,
                "Internal Server Error",
                "An Unexpected Error Occurred.",
                httpServlet.getRequestURI()
        );
    }

}
