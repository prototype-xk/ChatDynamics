package net.prototyxk.chatdynamics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pioche sans remise : tire les éléments un par un dans un ordre aléatoire.
 * Quand la pioche est vide elle se réinitialise automatiquement depuis la source.
 * Remplace les paires pioche/source manuelles de l'original.
 */
public class Pioche<T> {

    private final List<T> source;
    private final List<T> pioche = new ArrayList<>();

    public Pioche(List<T> source) {
        this.source = new ArrayList<>(source);
        refill();
    }

    /** Réinitialise la source et reshufle. Utile après un reload de config. */
    public void reset(List<T> newSource) {
        source.clear();
        source.addAll(newSource);
        pioche.clear();
        refill();
    }

    /**
     * Tire le prochain élément.
     * @return null si la source est vide
     */
    public T tirer() {
        if (source.isEmpty()) return null;
        if (pioche.isEmpty()) refill();
        return pioche.remove(pioche.size() - 1);
    }

    public boolean isEmpty() {
        return source.isEmpty();
    }

    private void refill() {
        if (pioche instanceof ArrayList<T> list) {
            list.ensureCapacity(source.size());
        }
        pioche.addAll(source);
        Collections.shuffle(pioche);
    }
}