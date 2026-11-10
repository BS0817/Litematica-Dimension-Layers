package dev.dimensionlayers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ProjectChecks {
    private ProjectChecks() {}

    public static void main(String[] args) throws Exception {
        coordinates();
        Path directory = Files.createTempDirectory("dimension-layers-checks");
        try {
            archive(directory);
            localization();
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
        System.out.println("PASS: coordinate conversion, archive/backup, corruption handling, localization.");
    }

    private static void coordinates() throws Exception {
        var zero = new Project.Point(0, 0, 0);
        var over = new Project.Link("Road", Project.OVERWORLD, new BigDecimal("-9"), new BigDecimal("16.75"), true);
        check(over.target(Project.NETHER, 73, zero).equals(new Project.Point(-2, 73, 2)), "negative floor");
        check(over.target(Project.OVERWORLD, 73, zero).equals(new Project.Point(-9, 73, 16)), "fractional floor");
        var nether = new Project.Link("Road", Project.NETHER, new BigDecimal("-1.125"), new BigDecimal("2.5"), true);
        check(nether.target(Project.OVERWORLD, 64, new Project.Point(1, -4, -1))
            .equals(new Project.Point(-8, 60, 19)), "reverse and offset");
        expect(IllegalArgumentException.class, () -> nether.target(Project.END, 64, zero));
        expect(IllegalArgumentException.class, () -> new Project.Point(30_000_001, 0, 0));
        expect(IllegalArgumentException.class, () -> new Project.Link("bad", Project.OVERWORLD, new BigDecimal("1e-90"), BigDecimal.ZERO, true));
    }

    private static void archive(Path directory) throws Exception {
        // Archive-level test payloads use the gzip signature. Minecraft integration tests decode real NBT.
        byte[] data = new byte[20];
        data[0] = 31;
        data[1] = (byte) 139;
        data[19] = 76;
        var project = new Project(UUID.randomUUID(), "차원 교통망", "Portable description");
        project.links.add(new Project.Link("Road", Project.OVERWORLD, new BigDecimal("-9.25"), BigDecimal.TEN, true));
        for (String dimension : new String[]{Project.OVERWORLD, Project.OVERWORLD, Project.NETHER, Project.END, "example:moon"}) {
            boolean sync = Project.vanillaPair(dimension);
            project.entries.add(new Project.Entry(UUID.randomUUID(), "Layer", dimension,
                new Project.Pose(new Project.Point(-8, 70, 4), Project.Turn.CLOCKWISE_90, Project.Flip.LEFT_RIGHT),
                true, false, sync ? "Road" : null, sync, new Project.Point(0, 0, 0), data));
        }
        Path archive = directory.resolve("project.ldl");
        ProjectFiles.save(archive, project, false);
        var opened = ProjectFiles.open(archive);
        check(opened.id.equals(project.id) && opened.name.equals(project.name), "identity and unicode");
        check(opened.entries.size() == 5 && opened.links.equals(project.links), "archive dimensions and groups");
        for (int i = 0; i < opened.entries.size(); i++) {
            var loaded = opened.entries.get(i);
            var original = project.entries.get(i);
            check(loaded.id().equals(original.id()) && loaded.design().equals(original.design())
                && loaded.dimension().equals(original.dimension()) && Arrays.equals(loaded.data(), data), "layer order and data");
        }
        Path exported = directory.resolve("export.litematic");
        ProjectFiles.exportSchematic(exported, opened.entries.getFirst().data());
        check(Arrays.equals(data, Files.readAllBytes(exported)), "exported raw bytes");
        expect(IOException.class, () -> ProjectFiles.exportSchematic(exported, new byte[]{1}));
        check(Arrays.equals(data, Files.readAllBytes(exported)), "export must not overwrite source");
        byte[] initial = Files.readAllBytes(archive);
        project.entries.removeLast();
        ProjectFiles.save(archive, project, true);
        check(ProjectFiles.open(archive).entries.size() == 4, "delete then save");
        check(Arrays.equals(initial, Files.readAllBytes(directory.resolve("project.ldl.bak"))), "exact previous generation");
        expect(IOException.class, () -> ProjectFiles.atomic(archive, true, true, tmp -> {
            Files.writeString(tmp, "partial");
            throw new IOException("interrupted");
        }));
        check(ProjectFiles.open(archive).entries.size() == 4, "interrupted write kept previous project");
        var corrupt = directory.resolve("corrupt.ldl");
        Files.writeString(corrupt, "broken ZIP");
        expect(IOException.class, () -> ProjectFiles.open(corrupt));
        rewrite(archive, corrupt, "missing");
        expect(IOException.class, () -> ProjectFiles.open(corrupt));
        rewrite(archive, corrupt, "version");
        expect(IOException.class, () -> ProjectFiles.open(corrupt));
        rewrite(archive, corrupt, "coordinates");
        expect(IOException.class, () -> ProjectFiles.open(corrupt));
        project.entries.add(project.entries.getFirst());
        expect(IllegalArgumentException.class, () -> ProjectFiles.save(archive, project, true));
        check(ProjectFiles.open(archive).entries.size() == 4, "rejected duplicate cannot replace original");
    }

    private static void rewrite(Path source, Path destination, String change) throws IOException {
        try (var input = new ZipFile(source.toFile()); var output = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (var entry : java.util.Collections.list(input.entries())) {
                if (change.equals("missing") && entry.getName().startsWith("schematics/")) continue;
                output.putNextEntry(new ZipEntry(entry.getName()));
                byte[] bytes;
                try (var stream = input.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                if (entry.getName().equals("manifest.json")) {
                    String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    if (change.equals("version")) json = json.replace("\"format\": 1", "\"format\": 0");
                    if (change.equals("coordinates")) json = json.replace("\"x\": -8", "\"x\": -8.25");
                    bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                output.write(bytes);
                output.closeEntry();
            }
        }
    }

    private static void localization() throws Exception {
        var resource = "assets/litematica_dimension_layers/lang/";
        var loader = ProjectChecks.class.getClassLoader();
        try (var en = loader.getResourceAsStream(resource + "en_us.json");
             var ko = loader.getResourceAsStream(resource + "ko_kr.json")) {
            check(en != null && ko != null, "language files");
            var eng = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(en, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var kor = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(ko, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            check(eng.keySet().equals(kor.keySet()) && eng.size() > 70, "language key parity");
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void expect(Class<? extends Exception> type, Checked action) throws Exception {
        try { action.run(); }
        catch (Exception found) {
            if (type.isInstance(found)) return;
            throw found;
        }
        throw new AssertionError("Expected " + type);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
