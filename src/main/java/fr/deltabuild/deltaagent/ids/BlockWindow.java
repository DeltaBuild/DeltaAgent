package fr.deltabuild.deltaagent.ids;

import fr.deltabuild.deltaagent.core.Log;

/**
 * Where Forge's Configuration picks a block ID when a mod's default is taken: the same window as
 * NGClient (16337-19475, from the top down), then the vanilla range below 4096. IDs between 4096
 * and the window are never picked.
 */
public final class BlockWindow {

    static final int FLOOR = 4096;
    static final int DEFAULT_MIN = 16337;
    static final int DEFAULT_MAX = 19475;

    private static volatile int min = DEFAULT_MIN;
    private static volatile int max = DEFAULT_MAX;

    /** "min-max", checked like NGClient's ng.blocs.fenetre; null keeps the default. */
    static void configure(String value) {
        min = DEFAULT_MIN;
        max = DEFAULT_MAX;
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String text = value.trim();
        String problem;
        int dash = text.indexOf('-');
        if (dash <= 0 || dash == text.length() - 1) {
            problem = "expected min-max";
        } else {
            try {
                int newMin = Integer.parseInt(text.substring(0, dash).trim());
                int newMax = Integer.parseInt(text.substring(dash + 1).trim());
                if (newMin < FLOOR) {
                    problem = "min below " + FLOOR;
                } else if (newMax < newMin) {
                    problem = "max below min";
                } else if (newMax > IdCapacity.MAX_ID) {
                    problem = "max above " + IdCapacity.MAX_ID;
                } else {
                    min = newMin;
                    max = newMax;
                    Log.info("ids", "Forge picks free block IDs in " + min + "-" + max + ".");
                    return;
                }
            } catch (NumberFormatException e) {
                problem = "not a number";
            }
        }
        Log.warn("ids", "Ignoring -D" + IdsModule.WINDOW + "=" + value + " (" + problem + "), keeping " + min + "-" + max + ".");
    }

    static int min() {
        return min;
    }

    static int max() {
        return max;
    }

    /** Upper bound of Forge's block search, which starts one below it. */
    public static int searchTop() {
        return max + 1;
    }

    /** The ID Forge tries after {@code id}, going down: the window, then 4095 and below. */
    public static int next(int id) {
        return id == min && min > FLOOR ? FLOOR - 1 : id - 1;
    }

    private BlockWindow() {
    }
}
