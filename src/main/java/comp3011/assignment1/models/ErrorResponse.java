package comp3011.assignment1.models;

import org.springframework.http.HttpStatus;

public record ErrorResponse(String timestamp, int status, String error, String message, String path) {
}
