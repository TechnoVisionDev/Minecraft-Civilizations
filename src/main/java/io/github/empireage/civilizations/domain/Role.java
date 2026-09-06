package io.github.empireage.civilizations.domain;

public enum Role {
    LEADER(3), ADVISOR(2), CITIZEN(1);

    private final int authority;

    Role(int authority) {
        this.authority = authority;
    }

    public boolean atLeast(Role required) {
        return authority >= required.authority;
    }
}
