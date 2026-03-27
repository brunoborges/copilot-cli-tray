package com.github.copilot.tray.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.util.Optional;

/**
 * Reusable warning-styled delete confirmation dialog.
 */
public final class DeleteConfirmDialog {

    private DeleteConfirmDialog() {}

    /**
     * Show a delete confirmation dialog and return true if the user confirms.
     *
     * @param message     the confirmation message body
     * @param owner       optional owner window (may be null)
     * @param themeManager optional theme manager for styling (may be null)
     * @return true if the user clicked Yes
     */
    public static boolean confirm(String message, Window owner, ThemeManager themeManager) {
        var alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.YES, ButtonType.NO);
        alert.setTitle("Delete Session");
        alert.setHeaderText(null);
        if (owner != null) alert.initOwner(owner);
        if (themeManager != null) themeManager.register(alert.getDialogPane().getScene());

        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.YES;
    }
}
