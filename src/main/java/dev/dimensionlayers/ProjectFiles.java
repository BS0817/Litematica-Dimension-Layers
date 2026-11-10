package dev.dimensionlayers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class ProjectFiles {
    static final int FORMAT = 1;
    static final int MAX_SCHEMATIC = 16 * 1024 * 1024;
    static final int MAX_TOTAL = 64 * 1024 * 1024;
    static final int MAX_JSON = 1024 * 1024;
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private record Manifest(int format, UUID id, String name, String description, boolean visible,
                            List<Layer> layers, List<Project.Link> groups) {}

    private record Layer(UUID id, String name, String dimension, Project.Pose design, boolean visible,
                         boolean locked, String group, boolean sync, Project.Point offset) {
        Layer(Project.Entry entry) {
            this(entry.id(), entry.name(), entry.dimension(), entry.design(), entry.visible(), entry.locked(),
                 entry.group(), entry.sync(), entry.offset());
        }

        Project.Entry materialize(byte[] data) {
            return new Project.Entry(id, name, dimension, design, visible, locked, group, sync, offset, data);
        }
    }

    static Project open(Path file) throws IOException {
        if (Files.size(file) > MAX_TOTAL + 2L * MAX_JSON) throw failure("limit");
        try (var zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            if (zip.size() > Project.MAX_ENTRIES + 1) throw failure("limit");
            var names = new HashSet<String>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (!names.add(entries.nextElement().getName())) throw failure("duplicate");
            }
            try {
                var json = JsonParser.parseString(new String(read(zip, "manifest.json", MAX_JSON),
                                                             StandardCharsets.UTF_8)).getAsJsonObject();
                requireKeys(json, "format", "id", "name", "description", "visible", "layers", "groups");
                if (json.get("format").getAsBigDecimal().intValueExact() != FORMAT) throw failure("version");
                for (var layer : json.getAsJsonArray("layers")) {
                    var item = layer.getAsJsonObject();
                    requireKeys(item, "id", "name", "dimension", "design", "visible", "locked", "group", "sync", "offset");
                    var pose = item.getAsJsonObject("design");
                    requireKeys(pose, "origin", "rotation", "mirror");
                    checkPoint(pose.getAsJsonObject("origin"));
                    checkPoint(item.getAsJsonObject("offset"));
                }
                for (var group : json.getAsJsonArray("groups")) {
                    requireKeys(group.getAsJsonObject(), "name", "basis", "x", "z", "enabled");
                }
                Manifest manifest = JSON.fromJson(json, Manifest.class);
                var project = new Project(manifest.id, manifest.name, manifest.description);
                project.visible = manifest.visible;
                project.links.addAll(manifest.groups);
                int used = 0;
                names.remove("manifest.json");
                for (Layer layer : manifest.layers) {
                    String entry = schematicPath(layer.id);
                    if (!names.remove(entry)) throw failure("missing");
                    byte[] data = read(zip, entry, Math.min(MAX_SCHEMATIC, MAX_TOTAL - used));
                    checkMagic(data);
                    used += data.length;
                    project.entries.add(layer.materialize(data));
                }
                if (!names.isEmpty()) throw failure("archive");
                project.validate();
                return project;
            } catch (RuntimeException invalid) {
                throw new IOException("ldl.error.archive", invalid);
            }
        }
    }

    static void save(Path path, Project project, boolean replace) throws IOException {
        project.validate();
        long total = 0;
        for (Project.Entry entry : project.entries) {
            checkMagic(entry.data());
            total += entry.data().length;
        }
        if (total > MAX_TOTAL) throw failure("limit");
        var manifest = new Manifest(FORMAT, project.id, project.name, project.description, project.visible,
            project.entries.stream().map(Layer::new).toList(), project.links);
        byte[] json = JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_JSON) throw failure("limit");
        atomic(path, replace, replace, temp -> {
            try (var zip = new ZipOutputStream(Files.newOutputStream(temp), StandardCharsets.UTF_8)) {
                put(zip, "manifest.json", json);
                for (Project.Entry entry : project.entries) put(zip, schematicPath(entry.id()), entry.data());
            }
        });
    }

    static byte[] readSchematic(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] data = bounded(stream, MAX_SCHEMATIC);
            checkMagic(data);
            return data;
        }
    }

    static void exportSchematic(Path path, byte[] data) throws IOException {
        atomic(path, false, false, temp -> Files.write(temp, data));
    }

    static void checkMagic(byte[] data) throws IOException {
        if (data.length > MAX_SCHEMATIC) throw failure("limit");
        if (data.length < 18 || data[0] != 31 || (data[1] & 255) != 139) throw failure("schematic");
    }

    private static void checkPoint(JsonObject object) throws IOException {
        requireKeys(object, "x", "y", "z");
        for (String axis : List.of("x", "y", "z")) object.get(axis).getAsBigDecimal().intValueExact();
    }

    private static void requireKeys(JsonObject object, String... keys) throws IOException {
        if (object == null) throw failure("archive");
        for (String key : keys) {
            if (!object.has(key) || (object.get(key).isJsonNull() && !key.equals("group"))) throw failure("archive");
        }
    }

    private static byte[] read(ZipFile zip, String name, int limit) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw failure("missing");
        if (entry.getSize() > limit) throw failure("limit");
        try (var stream = zip.getInputStream(entry)) {
            byte[] bytes = bounded(stream, limit);
            var crc = new java.util.zip.CRC32();
            crc.update(bytes);
            if (entry.getSize() != bytes.length || entry.getCrc() != crc.getValue()) throw failure("archive");
            return bytes;
        }
    }

    private static byte[] bounded(InputStream stream, int limit) throws IOException {
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) throw failure("limit");
        return bytes;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String schematicPath(UUID id) { return "schematics/" + id + ".litematic"; }
    private static IOException failure(String reason) { return new IOException("ldl.error." + reason); }

    @FunctionalInterface
    interface Writer { void write(Path temporary) throws IOException; }

    static void atomic(Path path, boolean replace, boolean backup, Writer writer) throws IOException {
        path = path.toAbsolutePath().normalize();
        Path parent = path.getParent();
        Path temporary = Files.createTempFile(parent, ".dimension-layers-", ".tmp");
        try {
            writer.write(temporary);
            force(temporary);
            if (replace) {
                if (backup && Files.isRegularFile(path)) {
                    Path previous = path.resolveSibling(path.getFileName() + ".bak");
                    Path original = path;
                    atomic(previous, true, false, temp -> Files.copy(original, temp, StandardCopyOption.REPLACE_EXISTING));
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // No REPLACE_EXISTING. In particular, an existing source schematic is never overwritten.
                Files.move(temporary, path);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void force(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }
}
