package dev.dimensionlayers;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class LitematicaBridge {
    private static final long MAX_CELLS = 16_777_216;
    private static final long MAX_NBT = 64L * 1024 * 1024;
    private final Path scratch;

    LitematicaBridge(Path scratch) { this.scratch = scratch; }

    LitematicaSchematic load(byte[] bytes) throws IOException {
        checkBounds(bytes);
        Files.createDirectories(scratch);
        Path file = Files.createTempFile(scratch, "layer-", ".litematic");
        try {
            Files.write(file, bytes);
            var schematic = LitematicaSchematic.createFromFile(scratch, file.getFileName().toString());
            if (schematic == null || schematic.getSubRegionCount() == 0) throw new IOException("ldl.error.schematic");
            return schematic;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void checkBounds(byte[] bytes) throws IOException {
        ProjectFiles.checkMagic(bytes);
        try {
            var root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(MAX_NBT));
            int version = root.getIntOr("Version", 0);
            if (version < 1 || version > 7) throw new IOException("ldl.error.schematic");
            var regions = root.getCompoundOrEmpty("Regions");
            if (regions.isEmpty() || regions.keySet().size() > 2048) throw new IOException("ldl.error.limit");
            long used = 0;
            for (String name : regions.keySet()) {
                var region = regions.getCompoundOrEmpty(name);
                var size = region.getCompoundOrEmpty("Size");
                var position = region.getCompoundOrEmpty("Position");
                used += boundedVolume(size, position);
                if (used > MAX_CELLS) throw new IOException("ldl.error.limit");
            }
        } catch (RuntimeException ex) {
            throw new IOException("ldl.error.schematic", ex);
        }
    }

    private static long boundedVolume(CompoundTag size, CompoundTag position) throws IOException {
        long volume = 1;
        for (String axis : new String[]{"x", "y", "z"}) {
            if (!(size.get(axis) instanceof NumericTag) || !(position.get(axis) instanceof NumericTag)) {
                throw new IOException("ldl.error.schematic");
            }
            int extent = size.getIntOr(axis, 0);
            int start = position.getIntOr(axis, 0);
            int cap = axis.equals("y") ? 4096 : 32768;
            if (extent == 0 || Math.abs((long) extent) > cap || Math.abs((long) start) > 32768) {
                throw new IOException("ldl.error.limit");
            }
            volume *= Math.abs((long) extent);
        }
        return volume;
    }

    record Capture(byte[] data, Project.Pose pose, String name) {}

    Capture capture(Minecraft client) throws IOException {
        var selection = DataManager.getSelectionManager().getCurrentSelection();
        if (client.level == null || selection == null || selection.getAllSubRegionBoxes().isEmpty()) {
            throw new IOException("ldl.error.selection");
        }
        return capture(client, selection);
    }

    Capture capture(Minecraft client, AreaSelection selection) throws IOException {
        long cells = 0;
        for (var box : selection.getAllSubRegionBoxes()) {
            var a = box.getPos1();
            var b = box.getPos2();
            if (a == null || b == null) throw new IOException("ldl.error.selection");
            var size = box.getSize();
            int x = size.getX(), y = size.getY(), z = size.getZ();
            if (x <= 0 || y <= 0 || z <= 0 || x > 32768 || y > 4096 || z > 32768) {
                throw new IOException("ldl.error.limit");
            }
            cells += (long) x * y * z;
            if (cells > MAX_CELLS) throw new IOException("ldl.error.limit");
            for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++) {
                for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                    if (!client.level.hasChunk(cx, cz)) throw new IOException("ldl.error.unloaded");
                }
            }
        }
        var schematic = LitematicaSchematic.createFromWorld(client.level, selection,
            new LitematicaSchematic.SchematicSaveInfo(false, true), "", ignored -> {});
        if (schematic == null) throw new IOException("ldl.error.selection");
        // The portable container does not need a client account name in newly captured files.
        schematic.getMetadata().setAuthor("");
        return new Capture(serialize(schematic), LayerSession.at(selection.getEffectiveOrigin()), selection.getName());
    }

    Capture selectedPlacement() throws IOException {
        var selection = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        if (selection == null) throw new IOException("ldl.error.no_placement");
        var schematic = selection.getSchematic();
        var path = schematic.getFile();
        var bytes = path != null && Files.isRegularFile(path) ? ProjectFiles.readSchematic(path) : serialize(schematic);
        var origin = LayerSession.at(selection.getOrigin()).origin();
        var pose = new Project.Pose(origin, Project.Turn.valueOf(selection.getRotation().name()),
            Project.Flip.valueOf(selection.getMirror().name()));
        return new Capture(bytes, pose, selection.getName());
    }

    private byte[] serialize(LitematicaSchematic schematic) throws IOException {
        Files.createDirectories(scratch);
        Path temporary = Files.createTempFile(scratch, "capture-", ".litematic");
        try {
            if (!schematic.writeToFile(scratch, temporary.getFileName().toString(), true)) {
                throw new IOException("ldl.error.schematic");
            }
            return ProjectFiles.readSchematic(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    SchematicPlacement create(Project.Entry entry, Project.Pose pose) throws IOException {
        var schematic = load(entry.data());
        var origin = pose.origin();
        var placement = SchematicPlacement.createFor(schematic, new BlockPos(origin.x(), origin.y(), origin.z()),
            "[LDL] " + entry.name(), true, true);
        placement.setShouldBeSaved(false);
        placement.setRotation(Rotation.valueOf(pose.rotation().name()), null);
        placement.setMirror(Mirror.valueOf(pose.mirror().name()), null);
        if (entry.locked()) placement.toggleLocked();
        return placement;
    }
}
