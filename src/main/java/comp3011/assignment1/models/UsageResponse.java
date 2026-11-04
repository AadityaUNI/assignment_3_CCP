/*
 * Author: Aaditya Singh, A1946894
 */
package comp3011.assignment1.models;

// Body of GET /api/v1/global/usage. The spec requires exactly { "usage": s }, so the record has one component
// named "usage": Spring turns it into that JSON key. s is the cumulative Whisper usage in seconds.
public record UsageResponse(double usage) {
    public UsageResponse {
        if (usage < 0) {
            throw new IllegalArgumentException("Invalid usage second count");
        }
    }
}
