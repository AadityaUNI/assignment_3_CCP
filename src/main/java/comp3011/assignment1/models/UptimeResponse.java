/*
 * Author: Aaditya Singh, A1946894
 */
package comp3011.assignment1.models;

public record UptimeResponse(String utcServerStart, String utcNow, double serverUptimeSeconds) {
    public UptimeResponse{
        if (serverUptimeSeconds < 0) {
            throw new IllegalArgumentException("Invalid uptime second count");
        }
    }
}