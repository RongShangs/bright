# Implementation Plan - Minimalist Dialog UI Redesign

Redesign the application and tile interaction to show a minimalist, Xiaomi-style (HyperOS) dialog instead of a full activity. The dialog will feature a vertical brightness slider, a system restore button, and a status panel.

## User Review Required

- **Dialog as Activity**: The UI will be implemented as a `Translucent` Activity to simplify management from both the launcher and the Quick Settings Tile.
- **Vertical Slider**: A custom `VerticalBrightnessSlider` View will be implemented to achieve the specific visual style shown in the reference.

## Proposed Changes

### UI & Resources

#### [NEW] [dialog_main.xml](file:///D:/bright/app/src/main/res/layout/dialog_main.xml)
- Defines the layout for the minimalist dialog.
- Left panel: Status information (Software takeover, brightness value, root status, developer link).
- Middle panel: Vertical brightness slider.
- Right panel: "Restore System Control" button.

#### [NEW] [bg_dialog.xml](file:///D:/bright/app/src/main/res/drawable/bg_dialog.xml)
- Rounded rectangle background for the main dialog (Xiaomi style).

#### [NEW] [bg_panel.xml](file:///D:/bright/app/src/main/res/drawable/bg_panel.xml)
- Semi-transparent background for the panels within the dialog.

#### [themes.xml](file:///D:/bright/app/src/main/res/values/themes.xml)
- Add `Theme.DialogActivity` with `android:windowIsTranslucent` and `android:windowBackground` set to transparent.

---

### Components

#### [NEW] [VerticalBrightnessSlider.kt](file:///D:/bright/app/src/main/java/brightnesslock/rongshangs/top/ui/VerticalBrightnessSlider.kt)
- Custom View for the vertical brightness slider.
- Handles touch events to change brightness.
- Visual style matching the reference image.

---

### Logic

#### [MainActivity.kt](file:///D:/bright/app/src/main/java/brightnesslock/rongshangs/top/MainActivity.kt)
- Update to use `dialog_main.xml`.
- Implement logic for the slider and the restore button.
- Add "Coolapk @戎Shangs" link behavior.
- Set activity to finish when clicking outside the dialog area.

#### [BrightnessTileService.kt](file:///D:/bright/app/src/main/java/brightnesslock/rongshangs/top/BrightnessTileService.kt)
- Update `onClick` to launch `MainActivity` instead of toggling brightness directly.

#### [AndroidManifest.xml](file:///D:/bright/app/src/main/AndroidManifest.xml)
- Update `MainActivity` theme to `Theme.DialogActivity`.
- Add `android:excludeFromRecents="true"`.

## Verification Plan

### Automated Tests
- N/A (UI focused change).

### Manual Verification
- Launch the app from the launcher: Verify the dialog appears correctly with Xiaomi-style aesthetics.
- Click the Quick Settings Tile: Verify it launches the same dialog.
- Slide the brightness bar: Verify it updates the back screen brightness.
- Click "Restore System Control": Verify it reverts to system control.
- Click developer link: Verify it opens the Coolapk profile.
- Click outside the dialog: Verify the dialog closes.
