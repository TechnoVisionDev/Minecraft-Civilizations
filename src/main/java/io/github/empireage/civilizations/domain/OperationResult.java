package io.github.empireage.civilizations.domain;

public record OperationResult(boolean success, String message) {
    public static OperationResult ok(String message) {
        return new OperationResult(true, message);
    }

    public static OperationResult denied(String message) {
        return new OperationResult(false, message);
    }
}
