/*
 * Author: Aaditya Singh, A1946894
 */
package comp3011.assignment1.exception;

public class MultipleShutdownException extends RuntimeException{
    public MultipleShutdownException() {
        super("Shutdown is already in progress.");
    }
}
