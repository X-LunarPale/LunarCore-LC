package com.lunarboreal.api.web;

public class HttpException extends RuntimeException {
    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static HttpException badRequest(String msg) { return new HttpException(400, msg); }

    public static HttpException notFound(String msg) { return new HttpException(404, msg); }

    public static HttpException forbidden(String msg) { return new HttpException(403, msg); }
}
