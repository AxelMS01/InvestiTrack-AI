package com.example.auditservice.api;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status, String code, String detail) { super(detail); this.status=status; this.code=code; }
    public static void require(boolean condition, int status, String code, String detail) {
        if (!condition) throw new ApiException(status,code,detail);
    }
    public static ApiException unavailable() {
        return new ApiException(503,"DEPENDENCY_UNAVAILABLE","A required service is unavailable or returned an invalid response.");
    }
}
