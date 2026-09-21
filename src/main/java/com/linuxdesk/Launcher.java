package com.linuxdesk;

/**
 * Separate entry point for the packaged app (jpackage/`java -jar`). The JVM refuses to launch a
 * JavaFX app whose main class directly extends {@link javafx.application.Application} unless it's
 * run from the module path with a module-info — it throws "JavaFX runtime components are missing"
 * instead. Pointing the packaged launcher at this indirection class (which doesn't extend
 * Application) skips that check. `mvn javafx:run` is unaffected — the javafx-maven-plugin launches
 * {@link App} directly through its own mechanism.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        App.main(args);
    }
}
