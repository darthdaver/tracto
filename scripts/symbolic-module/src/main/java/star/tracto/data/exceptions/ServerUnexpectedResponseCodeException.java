package star.tracto.data.exceptions;

public class ServerUnexpectedResponseCodeException extends RuntimeException {
    public ServerUnexpectedResponseCodeException(String message) {
        super(message);
    }
}
