/*
 * Author: Aaditya Singh, A1946894
 */
package comp3011.assignment1.models;

// Serialises to exactly { "usage": s }, the total Whisper seconds used.
public record UsageResponse(double usage) {
    public UsageResponse {
        if (usage < 0) {
            throw new IllegalArgumentException("Invalid usage second count");
        }
    }
}
