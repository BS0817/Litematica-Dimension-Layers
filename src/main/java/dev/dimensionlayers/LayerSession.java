package dev.dimensionlayers;

import com.google.gson.reflect.TypeToken;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

final class LayerSession {
    private record Active(Project.Entry entry, Project.Pose pose, SchematicPlacement placement) {}

    final Path directory;
    final LitematicaBridge bridge;
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<UUID, Project.Pose> worldPoses = new HashMap<>();
    private final Map<String, Map<UUID, Project.Pose>> unsavedPoses = new HashMap<>();
    Project project;
    Path file;
    boolean dirty;
    String runtimeError;
    private String world;
    private ClientLevel level;
    private int settle;
    private boolean update = true;

    LayerSession(Path directory) {
        this.directory = directory;
        bridge = new LitematicaBridge(directory.resolve("scratch"));
    }

    void create(String name, String description) throws IOException {
        requireClean();
        Project replacement = new Project(UUID.randomUUID(), name, description);
        install(replacement, null, Map.of(), true);
    }

    void open(Path file) throws IOException {
        requireClean();
        var replacement = ProjectFiles.open(file);
        for (var entry : replacement.entries) bridge.load(entry.data());
        Path canonical = file.toRealPath();
        Map<UUID, Project.Pose> poses = readPoses(replacement, canonical, world);
        install(replacement, canonical, poses, false);
    }

    void close(boolean discard) throws IOException {
        if (!discard) requireClean();
        install(null, null, Map.of(), false);
    }

    private void requireClean() throws IOException {
        if (dirty) throw new IOException("ldl.error.unsaved");
    }

    private void install(Project replacement, Path path, Map<UUID, Project.Pose> poses, boolean unsaved) {
        removeOwn();
        project = replacement;
        file = path;
        dirty = unsaved;
        worldPoses.clear();
        worldPoses.putAll(poses);
        unsavedPoses.clear();
        changed();
    }

    void save(Path destination) throws IOException {
        if (project == null) throw new IOException("ldl.error.project");
        Path target = destination.toAbsolutePath().normalize();
        if (file != null && file.equals(target)) {
            ProjectFiles.save(target, project, true);
            dirty = false;
            return;
        }
        // An explicitly saved copy receives a fresh ID and a separate local placement store.
        var copy = new Project(file == null ? project.id : UUID.randomUUID(), project.name, project.description);
        copy.visible = project.visible;
        copy.entries.addAll(project.entries);
        copy.links.addAll(project.links);
        ProjectFiles.save(target, copy, false);
        project = copy;
        file = target.toRealPath();
        dirty = false;
        try {
            persistPoses(worldPoses);
        } finally {
            unsavedPoses.clear();
        }
        changed();
    }

    Project.Entry add(byte[] bytes, String name, String dimension, Project.Pose pose) throws IOException {
        if (project.entries.size() >= Project.MAX_ENTRIES) throw new IOException("ldl.error.limit");
        checkTotal(bytes, null);
        bridge.load(bytes);
        var entry = new Project.Entry(UUID.randomUUID(), name, dimension, pose, true, false, null, false,
            new Project.Point(0, 0, 0), bytes);
        project.entries.add(entry);
        edited();
        return entry;
    }

    void replace(Project.Entry entry, byte[] bytes) throws IOException {
        checkTotal(bytes, entry);
        bridge.load(bytes);
        project.entries.set(project.entries.indexOf(entry), entry.withData(bytes));
        edited();
    }

    private void checkTotal(byte[] bytes, Project.Entry replacing) throws IOException {
        long used = bytes.length;
        for (var entry : project.entries) if (entry != replacing) used += entry.data().length;
        if (used > ProjectFiles.MAX_TOTAL) throw new IOException("ldl.error.limit");
    }

    void remove(Project.Entry entry) {
        project.entries.remove(entry);
        edited();
    }

    void updateEntry(Project.Entry before, Project.Entry entry, Project.Pose local, boolean clearLocal) throws IOException {
        if (entry.group() != null && project.link(entry.group()) == null) throw new IOException("ldl.error.group");
        var next = new HashMap<>(worldPoses);
        if (before.locked() && entry.locked() && (clearLocal || !entry.design().equals(before.design()))) {
            throw new IOException("ldl.error.locked");
        }
        if (clearLocal) next.remove(entry.id());
        if (local != null) {
            requireWorld();
            if (before.locked() && entry.locked() && !local.equals(pose(before))) throw new IOException("ldl.error.locked");
            next.put(entry.id(), local);
        } else if (!clearLocal && hasWorld() && (!java.util.Objects.equals(before.group(), entry.group())
                || before.sync() != entry.sync() || !before.offset().equals(entry.offset())
                || before.design().origin().y() != entry.design().origin().y())) {
            var link = project.link(entry.group());
            if (link != null && link.enabled()) applyLink(next, entry, link);
        }
        store(next);
        project.entries.set(project.entries.indexOf(before), entry);
        if (before.equals(entry)) changed();
        else edited();
    }

    void saveLink(Project.Link link) throws IOException {
        var old = project.link(link.name());
        if (old == null && project.links.size() >= Project.MAX_ENTRIES) throw new IOException("ldl.error.limit");
        if (hasWorld() && link.enabled() && !link.equals(old)) {
            var next = new HashMap<>(worldPoses);
            for (var entry : project.entries) applyLink(next, entry, link);
            store(next);
        }
        if (old == null) project.links.add(link);
        else project.links.set(project.links.indexOf(old), link);
        edited();
    }

    void deleteLink(String name) throws IOException {
        if (project.entries.stream().anyMatch(entry -> name.equals(entry.group()))) throw new IOException("ldl.error.group_used");
        var link = project.link(name);
        if (link != null) {
            project.links.remove(link);
            edited();
        }
    }

    int resync(String group) throws IOException {
        requireWorld();
        Project.Link link = project.link(group);
        if (link == null || !link.enabled()) throw new IOException("ldl.error.group_disabled");
        var next = new HashMap<>(worldPoses);
        int count = 0;
        for (var entry : project.entries) if (applyLink(next, entry, link)) count++;
        store(next);
        changed();
        return count;
    }

    private boolean applyLink(Map<UUID, Project.Pose> poses, Project.Entry entry, Project.Link link) {
        if (!link.name().equals(entry.group()) || !entry.sync() || entry.locked()) return false;
        var old = poses.getOrDefault(entry.id(), entry.design());
        var position = link.target(entry.dimension(), entry.design().origin().y(), entry.offset());
        poses.put(entry.id(), new Project.Pose(position, old.rotation(), old.mirror()));
        return true;
    }

    Project.Pose pose(Project.Entry entry) { return worldPoses.getOrDefault(entry.id(), entry.design()); }
    boolean hasWorld() { return level != null && world != null; }

    private void requireWorld() throws IOException {
        if (!hasWorld()) throw new IOException("ldl.error.world");
    }

    void edited() { dirty = true; changed(); }
    private void changed() { update = true; runtimeError = null; }

    void tick(Minecraft client) {
        if (level != client.level) {
            removeOwn();
            level = client.level;
            settle = 8;
            String identity = identity(client);
            if (!java.util.Objects.equals(identity, world)) {
                world = identity;
                worldPoses.clear();
                if (project != null) {
                    try {
                        worldPoses.putAll(file == null
                            ? unsavedPoses.getOrDefault(world, Map.of()) : readPoses(project, file, world));
                    }
                    catch (IOException failure) {
                        DimensionLayers.LOG.warn("Unable to read local placement state", failure);
                        runtimeError = "ldl.error.world_state";
                    }
                }
            }
            update = true;
        }
        if (level == null || (settle > 0 && --settle > 0) || !update) return;
        update = false;
        try {
            reconcile(dimension(client));
        } catch (IOException | RuntimeException failure) {
            runtimeError = "ldl.error.placement";
            DimensionLayers.LOG.warn("Unable to update Dimension Layers placements", failure);
        }
    }

    private void reconcile(String dimension) throws IOException {
        var manager = DataManager.getSchematicPlacementManager();
        var needed = new HashMap<UUID, Project.Entry>();
        if (project != null && project.visible) {
            for (var entry : project.entries) {
                if (entry.visible() && entry.dimension().equals(dimension)) needed.put(entry.id(), entry);
            }
        }
        for (UUID id : new ArrayList<>(active.keySet())) {
            var existing = active.get(id);
            var entry = needed.get(id);
            if (entry != existing.entry || !pose(entry).equals(existing.pose)
                    || !manager.getAllSchematicsPlacements().contains(existing.placement)) {
                manager.removeSchematicPlacement(existing.placement);
                active.remove(id);
            }
        }
        if (project == null) return;
        for (var entry : project.entries) {
            if (!needed.containsKey(entry.id()) || active.containsKey(entry.id())) continue;
            var pose = pose(entry);
            var placement = bridge.create(entry, pose);
            manager.addSchematicPlacement(placement, false);
            active.put(entry.id(), new Active(entry, pose, placement));
        }
    }

    private void removeOwn() {
        if (active.isEmpty()) return;
        var manager = DataManager.getSchematicPlacementManager();
        for (var placement : active.values()) manager.removeSchematicPlacement(placement.placement);
        active.clear();
    }

    private void store(Map<UUID, Project.Pose> poses) throws IOException {
        if (poses.equals(worldPoses)) return;
        if (file == null && world != null) unsavedPoses.put(world, Map.copyOf(poses));
        else persistPoses(poses);
        worldPoses.clear();
        worldPoses.putAll(poses);
    }

    private void persistPoses(Map<UUID, Project.Pose> poses) throws IOException {
        var target = statePath(project, file, world);
        if (target == null) return;
        Files.createDirectories(target.getParent());
        ProjectFiles.atomic(target, true, false, temp -> Files.writeString(temp, ProjectFiles.JSON.toJson(poses)));
    }

    private Map<UUID, Project.Pose> readPoses(Project source, Path path, String worldKey) throws IOException {
        Path target = statePath(source, path, worldKey);
        if (target == null || !Files.exists(target)) return Map.of();
        if (Files.size(target) > ProjectFiles.MAX_JSON) throw new IOException("ldl.error.world_state");
        try {
            var type = new TypeToken<Map<UUID, Project.Pose>>() {};
            Map<UUID, Project.Pose> poses = ProjectFiles.JSON.fromJson(Files.readString(target), type);
            if (poses == null || poses.size() > Project.MAX_ENTRIES || poses.containsValue(null)) {
                throw new IOException("ldl.error.world_state");
            }
            poses.keySet().retainAll(source.entries.stream().map(Project.Entry::id).toList());
            return poses;
        } catch (RuntimeException failure) {
            throw new IOException("ldl.error.world_state", failure);
        }
    }

    private Path statePath(Project source, Path path, String worldKey) {
        if (source == null || path == null || worldKey == null) return null;
        // The digest also separates copies of the same project that retain the same manifest UUID.
        return directory.resolve("worlds").resolve(digest(worldKey + "\n" + source.id + "\n" + path) + ".json");
    }

    static String dimension(Minecraft client) {
        return client.level == null ? Project.OVERWORLD : client.level.dimension().identifier().toString();
    }

    static Project.Pose at(BlockPos pos) {
        return new Project.Pose(new Project.Point(pos.getX(), pos.getY(), pos.getZ()), Project.Turn.NONE, Project.Flip.NONE);
    }

    private static String identity(Minecraft client) {
        if (client.level == null) return null;
        var local = client.getSingleplayerServer();
        if (local != null) return "local:" + local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var server = client.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) return "server:" + server.ip.toLowerCase(java.util.Locale.ROOT);
        return null;
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
