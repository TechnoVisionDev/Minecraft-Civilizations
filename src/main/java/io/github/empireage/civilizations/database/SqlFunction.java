package io.github.empireage.civilizations.database;

@FunctionalInterface
public interface SqlFunction<T, R> {
    R apply(T value) throws Exception;
}
