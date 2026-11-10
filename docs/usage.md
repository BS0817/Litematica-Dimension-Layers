# Working with projects

## Capture, import, and manage

Create a project and enter its name and description. Pick a dimension with the dimension control. **Import file** uses the displayed dimension. **Add selection** captures the current Litematica area in the dimension you are actually visiting. **Add placement** imports the selected native placement's schematic, origin, global rotation, and mirror. Native per-subregion placement edits are not imported.

Path dialogs accept an absolute path or a name relative to the default folder displayed in the input. Existing files can be opened or imported. **Save As** and **Export** require a new path. Select a layer to edit, reorder, replace, export, or remove it. Replacement and removal affect the project only. Closing the menu keeps the project active. Use **Close project** to remove its transient placements.

## Portable defaults and local placement

In **Placement**, choose **This world only** and edit **World X, Y, Z** for a local override. Choose **Portable defaults** and edit **Default X, Y, Z** to share an origin, rotation and mirror, and reset that layer's local override. **Reset this world to defaults** removes only the local override. Unlock a layer before moving it.

## Link portals across dimensions

1. In **Link groups**, enter a new group name, choose Overworld or Nether as the anchor dimension, enter the portal X/Z and enable synchronization. The preview shows both dimension anchors. The anchor is independent from each schematic origin.
2. In each layer's **Edit layer** screen, select the group, turn on **Follow anchor**, and enter X/Y/Z offsets. A layer with no group or a disabled sync option keeps its position.
3. Applying a changed anchor or offset updates eligible layers in the current world. **Resync** re-applies the saved group settings. To check the offset-adjusted origin, open the layer's **Placement** screen. Manual overrides persist until settings change or you resync. Locked layers are skipped.
4. For Overworld → Nether, divide X/Z by eight. For Nether → Overworld, multiply by eight. Take the mathematical floor after conversion and then add X/Z offsets. For example -9 Overworld becomes -2 Nether before offsets. Y is the layer's independent portable Y plus the offset Y. Blocks are never resized.

## Save and recover

A normal save writes an atomic replacement and retains the previous generation as **.ldl.bak**. For manual recovery, copy the backup to a new path ending in **.ldl** and open that copy. Save As generates a new project ID when copying an existing project. There is no project autosave. World-only placement edits are written locally as they are applied when the archive has a path.
