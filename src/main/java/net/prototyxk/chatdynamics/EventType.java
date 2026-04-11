package net.prototyxk.chatdynamics;

/**
 * Types d'événements disponibles.
 * Remplace les magic numbers 0-5 du switch original.
 */
public enum EventType {
    CALCUL,
    CULTURE_G,
    MOT_DESORDRE,
    HOVER,
    DIVISION,
    CALCUL_EXPERT;

    private static final EventType[] VALUES = values();

    public static EventType random(java.util.Random rng) {
        return VALUES[rng.nextInt(VALUES.length)];
    }
}