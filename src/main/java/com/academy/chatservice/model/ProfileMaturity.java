package com.academy.chatservice.model;

public enum ProfileMaturity {
    PERFIL_INSUFICIENTE(0),
    PERFIL_INICIAL(1),
    PERFIL_CONFIABLE(2),
    PERFIL_AVANZADO(3);

    private final int rank;

    ProfileMaturity(int rank) {
        this.rank = rank;
    }

    public boolean atLeast(ProfileMaturity other) {
        return this.rank >= other.rank;
    }
}
