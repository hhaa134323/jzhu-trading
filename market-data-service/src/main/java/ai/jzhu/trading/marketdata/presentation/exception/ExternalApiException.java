package ai.jzhu.trading.marketdata.presentation.exception;

import org.springframework.http.HttpStatus;

public class ExternalApiException extends RuntimeException {

    private final int status;

    public ExternalApiException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public ExternalApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ExternalApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = HttpStatus.SERVICE_UNAVAILABLE.value();
    }

    public ExternalApiException(String message) {
        super(message);
        this.status = HttpStatus.SERVICE_UNAVAILABLE.value();
    }

    public int getStatus() {
        return status;
    }
}
