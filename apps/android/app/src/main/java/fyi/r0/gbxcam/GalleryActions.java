package fyi.r0.gbxcam;

import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;

/**
 * The action-button state machine: given the current gallery selection, busy and
 * connection state, it sets each toolbar button's enabled/visible/availability and
 * shows or hides the album-tools row. The buttons are created and wired by
 * {@link MainScreen}; this class only drives their state via {@link #update}.
 */
final class GalleryActions {
    private final UiStyle.Palette colors;
    private final Button loadButton;
    private final Button selectAllButton;
    private final Button selectModeButton;
    private final Button deselectAllButton;
    private final Button saveButton;
    private final Button shareButton;
    private final Button settingsButton;
    private final Button deleteButton;
    private final Button recoverButton;
    private final Button moveFirstButton;
    private final Button compactButton;
    private final Button clearAlbumButton;
    private final Button mergeButton;
    private final FrameLayout paletteField;
    private final HorizontalScrollView albumActionsWrapper;

    GalleryActions(UiStyle.Palette colors,
            Button loadButton, Button selectAllButton, Button selectModeButton, Button deselectAllButton,
            Button saveButton, Button shareButton, Button settingsButton, Button deleteButton,
            Button recoverButton, Button moveFirstButton, Button compactButton, Button clearAlbumButton,
            Button mergeButton, FrameLayout paletteField, HorizontalScrollView albumActionsWrapper) {
        this.colors = colors;
        this.loadButton = loadButton;
        this.selectAllButton = selectAllButton;
        this.selectModeButton = selectModeButton;
        this.deselectAllButton = deselectAllButton;
        this.saveButton = saveButton;
        this.shareButton = shareButton;
        this.settingsButton = settingsButton;
        this.deleteButton = deleteButton;
        this.recoverButton = recoverButton;
        this.moveFirstButton = moveFirstButton;
        this.compactButton = compactButton;
        this.clearAlbumButton = clearAlbumButton;
        this.mergeButton = mergeButton;
        this.paletteField = paletteField;
        this.albumActionsWrapper = albumActionsWrapper;
    }

    void update(GalleryState gallery, boolean busy, boolean selectMode, boolean deviceConnected) {
        int selected = gallery == null ? 0 : gallery.selectedCount();
        int selectedActive = gallery == null ? 0 : gallery.selectedActiveCount();
        int selectedDeleted = gallery == null ? 0 : gallery.selectedDeletedCount();
        int selectedManualMerges = gallery == null ? 0 : gallery.selectedManualMergeCount();
        boolean showDelete = selectedActive > 0 || selectedManualMerges > 0;
        boolean showRecover = selectedDeleted > 0;
        int total = gallery == null ? 0 : gallery.photos.size();

        loadButton.setEnabled(!busy);
        selectAllButton.setEnabled(!busy && total > 0 && selected < total);
        selectAllButton.setVisibility(total > 0 && selected < total ? View.VISIBLE : View.GONE);
        selectModeButton.setEnabled(!busy && total > 0);
        selectModeButton.setVisibility(!selectMode && total > 0 ? View.VISIBLE : View.GONE);
        deselectAllButton.setEnabled(!busy && selected > 0);
        deselectAllButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy && selected > 0);
        saveButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        shareButton.setEnabled(!busy && selected > 0);
        shareButton.setVisibility(selected > 0 ? View.VISIBLE : View.GONE);
        settingsButton.setEnabled(!busy);
        deleteButton.setEnabled(!busy && showDelete);
        deleteButton.setVisibility(showDelete ? View.VISIBLE : View.GONE);
        recoverButton.setEnabled(!busy && showRecover);
        recoverButton.setVisibility(showRecover ? View.VISIBLE : View.GONE);
        moveFirstButton.setEnabled(!busy && deviceConnected && selectedActive > 0);
        moveFirstButton.setVisibility(deviceConnected && selectedActive > 0 ? View.VISIBLE : View.GONE);
        compactButton.setEnabled(false);
        compactButton.setVisibility(View.GONE);
        clearAlbumButton.setEnabled(!busy && deviceConnected && total > 0);
        clearAlbumButton.setVisibility(deviceConnected && total > 0 ? View.VISIBLE : View.GONE);
        int selectedMergeable = gallery == null ? 0 : gallery.selectedMergeableCount();
        boolean canMerge = !busy && (selectedMergeable == 3 || selectedMergeable == 4);
        mergeButton.setEnabled(canMerge);
        mergeButton.setVisibility(canMerge ? View.VISIBLE : View.GONE);
        mergeButton.setText(selectedMergeable == 4 ? "Merge to CRGB" : "Merge to RGB");
        paletteField.setEnabled(!busy);
        paletteField.setAlpha(busy ? 0.42f : 1.0f);
        setButtonAvailability(loadButton, !busy);
        setButtonAvailability(selectAllButton, !busy && total > 0 && selected < total);
        setButtonAvailability(selectModeButton, !busy && total > 0);
        setButtonAvailability(deselectAllButton, !busy && selected > 0);
        setButtonAvailability(saveButton, !busy && selected > 0);
        setButtonAvailability(shareButton, !busy && selected > 0);
        setButtonAvailability(settingsButton, !busy);
        setButtonAvailability(recoverButton, !busy && showRecover);
        setButtonAvailability(moveFirstButton, !busy && deviceConnected && selectedActive > 0);
        setButtonAvailability(compactButton, false);
        setButtonAvailability(clearAlbumButton, !busy && deviceConnected && total > 0);
        setButtonAvailability(mergeButton, canMerge);
        deleteButton.setTextColor(!busy && showDelete ? Color.WHITE : colors.disabledText);
        boolean anyAlbumAction = moveFirstButton.getVisibility() == View.VISIBLE
                || recoverButton.getVisibility() == View.VISIBLE
                || mergeButton.getVisibility() == View.VISIBLE
                || clearAlbumButton.getVisibility() == View.VISIBLE;
        if (albumActionsWrapper != null) albumActionsWrapper.setVisibility(anyAlbumAction ? View.VISIBLE : View.GONE);
    }

    private static void setButtonAvailability(Button button, boolean enabled) {
        button.setAlpha(enabled ? 1.0f : 0.42f);
    }
}
