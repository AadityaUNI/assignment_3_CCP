/*
 * Adelaide University COMP3011 Assignment 3.
 *
 * Authors:
 *   1. Aaditya Singh, A1946894
 */
package comp3011.frameprocessors;

/** A drawn subtitle strip (video width x height, ARGB pixels); never changed after creation, so any thread can read. */
public record SubtitleOverlay(String text, int height, int[] argb) {
}
