package io.github.empireage.civilizations.domain;

public enum WarState {
    PENDING,
    ACTIVE,
    RESOLVING,
    TRUCE,
    CANCELLED,
    COMPLETED;

    public boolean locksMembership() {
        return this == PENDING || this == ACTIVE || this == RESOLVING;
    }

    public boolean current() {
        return locksMembership();
    }
}
