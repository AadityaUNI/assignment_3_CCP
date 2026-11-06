/*
 * Author: Aaditya Singh, A1946894
 */
package comp3011.assignment1.exception;

import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnprocessableEntityException;
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

    // OpenAI failures: 503 = retry later, 502/422 = retrying won't help. Generic messages so nothing leaks.

    // No internet, a busy OpenAI (429) or an outage (5xx): all temporary, so 503.
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(value = {OpenAIIoException.class, OpenAIRetryableException.class,
            RateLimitException.class, InternalServerException.class},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ErrorResponse handleUpstreamUnavailable(Exception ex, HttpServletRequest httpServlet) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Speech service is temporarily unavailable.", httpServlet);
    }

    // Our key was rejected or the reply was unreadable. Retrying won't fix it, so 502 (bad gateway) rather than 503.
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    @ExceptionHandler(value = {UnauthorizedException.class, PermissionDeniedException.class,
            OpenAIInvalidDataException.class},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ErrorResponse handleUpstreamRejected(Exception ex, HttpServletRequest httpServlet) {
        return error(HttpStatus.BAD_GATEWAY, "Speech service could not process the request.", httpServlet);
    }

    // OpenAI refused this audio (bad format, too short, unprocessable). The same bytes will fail again: 422.
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    @ExceptionHandler(value = {BadRequestException.class, UnprocessableEntityException.class},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ErrorResponse handleAudioRejected(Exception ex, HttpServletRequest httpServlet) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "The audio could not be transcribed.", httpServlet);
    }

    private ErrorResponse error(HttpStatus status, String message, HttpServletRequest httpServlet) {
        return new ErrorResponse(Instant.now().toString(), status.value(), status.getReasonPhrase(), message,
                httpServlet.getRequestURI());
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
