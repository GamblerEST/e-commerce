package ee.shop.common.error;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {
    private final HttpStatus status;
    public AppException(String msg, HttpStatus status) { super(msg); this.status = status; }
    public HttpStatus getStatus() { return status; }

    public static AppException unauthorized(String m) { return new AppException(m, HttpStatus.UNAUTHORIZED); }
    public static AppException forbidden(String m)    { return new AppException(m, HttpStatus.FORBIDDEN); }
    public static AppException notFound(String m)     { return new AppException(m, HttpStatus.NOT_FOUND); }
    public static AppException conflict(String m)     { return new AppException(m, HttpStatus.CONFLICT); }
    public static AppException badRequest(String m)   { return new AppException(m, HttpStatus.BAD_REQUEST); }
    public static AppException tooManyRequests(String m) { return new AppException(m, HttpStatus.TOO_MANY_REQUESTS); }
    /** A dependency we need is down — the request was fine, we could not serve it. */
    public static AppException unavailable(String m)  { return new AppException(m, HttpStatus.SERVICE_UNAVAILABLE); }
}
