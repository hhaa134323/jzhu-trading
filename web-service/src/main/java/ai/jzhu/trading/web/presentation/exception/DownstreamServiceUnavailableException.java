package ai.jzhu.trading.web.presentation.exception;

public class DownstreamServiceUnavailableException extends RuntimeException {

    private final int status;

    public DownstreamServiceUnavailableException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
