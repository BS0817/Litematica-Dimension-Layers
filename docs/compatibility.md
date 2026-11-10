# Compatibility and limits

## Integration

Minecraft 26.3 uses Java 25 and non-obfuscated Fabric Loom. The 26.3 releases of Litematica and MaLiLib are pinned because the methods used here are public Java methods in their released artifacts, with no separate stable integration API assumed. This add-on uses Fabric client tick, screen, and key mapping events. No production mixins or access wideners are installed. See [Fabric Loom](https://docs.fabricmc.net/develop/loom/) and [Litematica 26.3 capture source](https://github.com/sakura-ryoko/litematica/blob/26.3/src/main/java/fi/dy/masa/litematica/schematic/LitematicaSchematic.java), and [Litematica 26.3 placement manager](https://github.com/sakura-ryoko/litematica/blob/26.3/src/main/java/fi/dy/masa/litematica/schematic/placement/SchematicPlacementManager.java).

The add-on delegates current selection capture and .litematic loading to Litematica. It registers ordinary Litematica placements marked non-persistent. A short settling period allows Litematica to restore its own state on dimension changes. The add-on removes only placement objects that it registered. Managed placement edits belong in Dimension Layers. Native changes to transient managed placements are not persisted as project overrides.

The add-on registers no networking or world block writes. Litematica's existing Easy Place, Material List, and other tools continue through Litematica; server rules still apply. Area capture reads loaded client blocks and uses a capture profile without entities. Client-unavailable NBT cannot be recovered by the add-on. Existing imported .litematic files retain their bytes.

## Limits

- One open project per client. The user opens the archive again after restarting Minecraft.
- Singleplayer identity uses the local world folder; multiplayer uses the server address supplied by Minecraft. Different logical worlds behind one server address can share the local override identity. When Minecraft provides no stable identity, portable settings display and world-local edits are unavailable.
- Positions are checked for supported numerical range. A custom dimension may be unavailable or have a different height range. Its layer is retained and shows only when that ID is visited.
- Large imports or captures can pause the client briefly. Size limits prevent unbounded archives. Project saving needs a writable filesystem with atomic replacement support.
- Coordinate synchronization updates origins only. The End and custom dimensions have no implicit conversion.
