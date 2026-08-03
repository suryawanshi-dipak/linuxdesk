package com.linuxdesk.ui;

import javafx.animation.FadeTransition;
import javafx.scene.Parent;
import javafx.util.Duration;

/** Small shared UI polish helpers (window-open feedback) so every window feels consistent. */
public final class WindowFx {

    private static final Duration FADE_IN_DURATION = Duration.millis(160);

    private WindowFx() {
    }

    /** Fades a window's root content in from transparent, called right before the owning stage is shown. */
    public static void fadeIn(Parent root) {
        root.setOpacity(0);
        FadeTransition fade = new FadeTransition(FADE_IN_DURATION, root);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }
}
