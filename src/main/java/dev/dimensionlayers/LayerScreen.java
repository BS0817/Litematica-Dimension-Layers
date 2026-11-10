package dev.dimensionlayers;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

final class LayerScreen extends Screen {
    private final LayerSession session;
    private final Screen parent;
    private String dimension;
    private UUID selected;
    private int page;
    private int groupIndex;
    private int left;
    private int span;
    private Component status = Component.empty();

    LayerScreen(LayerSession session) { this(session, null); }
    LayerScreen(LayerSession session, Screen parent) {
        super(t("title"));
        this.session = session;
        this.parent = parent;
    }

    static Component t(String name, Object... args) { return Component.translatable("ldl." + name, args); }

    @Override
    protected void init() {
        span = Math.min(560, width - 16);
        left = (width - span) / 2;
        if (dimension == null) dimension = LayerSession.dimension(minecraft);
        var project = session.project;
        row(30, new String[]{"new", "open", "save", "save_as", "close"},
            this::newProject, this::openProject, () -> save(false), () -> save(true), this::closeProject);
        if (project == null) return;
        row(53, new String[]{"metadata", project.visible ? "hide_all" : "show_all", "capture", "import", "loaded"},
            () -> metadata(false), () -> { project.visible = !project.visible; session.edited(); refresh(); },
            () -> capture(false), this::importFile, () -> capture(true));
        var dimensions = new LinkedHashSet<>(List.of(Project.OVERWORLD, Project.NETHER, Project.END));
        dimensions.add(LayerSession.dimension(minecraft));
        project.entries.forEach(entry -> dimensions.add(entry.dimension()));
        var dimensionList = new ArrayList<>(dimensions);
        if (!dimensions.contains(dimension)) dimension = dimensionList.getFirst();
        var entries = project.entries.stream().filter(entry -> entry.dimension().equals(dimension)).toList();
        int perPage = Math.max(1, (height - 158) / 23);
        int lastPage = Math.max(0, (entries.size() - 1) / perPage);
        page = Math.min(page, lastPage);
        int column = (span - 8) / 4;
        button(left, 76, column, t("prev_dim"), () -> cycleDimension(dimensionList, -1));
        button(left + column + 2, 76, span - 2 * column - 4, dimensionLabel(dimension),
            () -> cycleDimension(dimensionList, 1));
        button(left + span - column, 76, column, t("groups"), this::groups);
        for (int index = 0; index < perPage && page * perPage + index < entries.size(); index++) {
            var entry = entries.get(page * perPage + index);
            String mark = (entry.id().equals(selected) ? "▶ " : "") + (entry.visible() ? "● " : "○ ");
            String label = mark + entry.name() + "  ·  " + session.pose(entry).origin().display();
            button(left, 100 + 23 * index, span, Component.literal(font.plainSubstrByWidth(label, span - 10)),
                () -> { selected = entry.id(); refresh(); });
        }
        int controls = height - 56;
        button(left, controls, column, t("prev_page"), () -> { page = Math.max(0, page - 1); refresh(); }).active = page > 0;
        button(left + column + 2, controls, column, t("next_page"), () -> { page++; refresh(); }).active = page < lastPage;
        button(left + 2 * (column + 2), controls, column, t("up"), () -> reorder(-1)).active = current() != null;
        button(left + span - column, controls, column, t("down"), () -> reorder(1)).active = current() != null;
        row(height - 34, new String[]{"edit", "placement", "replace", "export", "delete"},
            () -> withEntry(this::entryForm), () -> withEntry(this::poseForm),
            () -> withEntry(this::replaceFile), () -> withEntry(this::exportFile), this::deleteEntry);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xd5181c24);
        graphics.centeredText(font, title, width / 2, 6, 0xfff1c574);
        if (session.project != null) {
            graphics.centeredText(font, Component.literal(font.plainSubstrByWidth(
                session.project.name + (session.dirty ? " *" : "") + "  ·  " + session.project.description, span)),
                width / 2, 18, 0xffe1e7ee);
        }
        var message = session.runtimeError == null ? status : Component.translatable(session.runtimeError);
        graphics.text(font, font.plainSubstrByWidth(message.getString(), span), left, height - 11, 0xffe1e7ee);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    private void refresh() { clearWidgets(); init(); }
    private void say(String key, Object... args) { status = t(key, args); }

    private void row(int y, String[] names, Runnable... actions) {
        int step = span / names.length;
        for (int i = 0; i < names.length; i++) {
            button(left + i * step, y, i == names.length - 1 ? span - i * step : step - 3, t(names[i]), actions[i]);
        }
    }

    private Button button(int x, int y, int w, Component label, Runnable action) {
        return addRenderableWidget(Button.builder(label, ignored -> action.run()).bounds(x, y, w, 20).build());
    }

    private void cycleDimension(List<String> dimensions, int delta) {
        dimension = dimensions.get(Math.floorMod(dimensions.indexOf(dimension) + delta, dimensions.size()));
        page = 0;
        selected = null;
        refresh();
    }

    private static Component dimensionLabel(String dimension) {
        return switch (dimension) {
            case Project.OVERWORLD -> t("overworld");
            case Project.NETHER -> t("nether");
            case Project.END -> t("end");
            default -> Component.literal(dimension);
        };
    }

    private Project.Entry current() {
        return session.project == null ? null : session.project.entries.stream()
            .filter(entry -> entry.id().equals(selected) && entry.dimension().equals(dimension)).findFirst().orElse(null);
    }

    private void withEntry(Consumer<Project.Entry> action) {
        var entry = current();
        if (entry == null) say("error.select");
        else action.accept(entry);
    }

    private void reorder(int shift) {
        withEntry(entry -> {
            var list = session.project.entries;
            int from = list.indexOf(entry);
            for (int target = from + shift; target >= 0 && target < list.size(); target += shift) {
                if (list.get(target).dimension().equals(dimension)) {
                    Collections.swap(list, from, target);
                    session.edited();
                    refresh();
                    return;
                }
            }
        });
    }

    private void newProject() {
        if (session.dirty) { say("error.unsaved"); return; }
        metadata(true);
    }

    private void metadata(boolean create) {
        var form = new Form(create ? "new" : "metadata");
        form.field("name", create ? "" : session.project.name, 96);
        form.field("description", create ? "" : session.project.description, 1024);
        form.finish(() -> {
            if (create) session.create(form.value(0).strip(), form.value(1));
            else { session.project.rename(form.value(0).strip(), form.value(1)); session.edited(); }
            selected = null;
            page = 0;
            say("ready");
        });
    }

    private void openProject() {
        if (session.dirty) { say("error.unsaved"); return; }
        fileForm("open", "project.ldl", ".ldl", path -> {
            session.open(path);
            selected = null;
            page = 0;
            say("ready");
        });
    }

    private void save(boolean copy) {
        if (session.project == null) { say("error.project"); return; }
        if (!copy && session.file != null) attempt(() -> { session.save(session.file); say("saved"); });
        else fileForm("save_as", "project.ldl", ".ldl", path -> { session.save(path); say("saved"); });
    }

    private void closeProject() {
        if (session.project == null) return;
        if (session.dirty) confirm("discard", () -> session.close(true));
        else attempt(() -> session.close(false));
    }

    private void capture(boolean loaded) {
        attempt(() -> {
            var captured = loaded ? session.bridge.selectedPlacement() : session.bridge.capture(minecraft);
            String name = captured.name() == null || captured.name().isBlank() ? "Selection" : captured.name();
            select(session.add(captured.data(), name.substring(0, Math.min(name.length(), 96)),
                LayerSession.dimension(minecraft), captured.pose()));
            say("added");
        });
    }

    private void importFile() {
        fileForm("import", "schematic.litematic", ".litematic", path -> {
            var origin = minecraft.player == null ? net.minecraft.core.BlockPos.ZERO : minecraft.player.blockPosition();
            String name = path.getFileName().toString();
            name = name.substring(0, name.length() - ".litematic".length());
            select(session.add(ProjectFiles.readSchematic(path), name.substring(0, Math.min(96, name.length())),
                dimension, LayerSession.at(origin)));
            say("added");
        });
    }

    private void select(Project.Entry entry) {
        selected = entry.id();
        dimension = entry.dimension();
        long index = session.project.entries.stream().filter(e -> e.dimension().equals(dimension)).count() - 1;
        page = (int) index / Math.max(1, (height - 158) / 23);
    }

    private void replaceFile(Project.Entry entry) {
        fileForm("replace", "schematic.litematic", ".litematic",
            path -> { session.replace(entry, ProjectFiles.readSchematic(path)); say("added"); });
    }

    private void exportFile(Project.Entry entry) {
        fileForm("export", "export.litematic", ".litematic",
            path -> { ProjectFiles.exportSchematic(path, entry.data()); say("exported"); });
    }

    private void deleteEntry() {
        withEntry(entry -> confirm("delete_confirm", () -> { session.remove(entry); selected = null; }));
    }

    private void entryForm(Project.Entry before) {
        var form = new Form("edit");
        form.field("name", before.name(), 96);
        form.field("dimension", before.dimension(), 128);
        form.toggle("visible", before.visible());
        form.toggle("locked", before.locked());
        var groups = new ArrayList<String>();
        groups.add("");
        session.project.links.forEach(group -> groups.add(group.name()));
        form.choice("group", groups, before.group() == null ? "" : before.group(), value -> value.isEmpty() ? t("none") : Component.literal(value));
        form.toggle("sync", before.sync());
        form.field("offset", before.offset().display(), 80);
        form.finish(() -> {
            String group = form.value(4).isEmpty() ? null : form.value(4);
            var entry = new Project.Entry(before.id(), form.value(0).strip(), form.value(1).strip(), before.design(),
                form.enabled(2), form.enabled(3), group, group != null && form.enabled(5), point(form.value(6)), before.data());
            session.updateEntry(before, entry, null, false);
            dimension = entry.dimension();
            page = 0;
            say("ready");
        });
    }

    private void poseForm(Project.Entry entry) {
        var form = new Form("placement");
        var pose = session.pose(entry);
        form.choice("scope", List.of("world", "portable", "reset"), session.hasWorld() ? "world" : "portable",
            value -> t("scope_" + value));
        form.field("origin", pose.origin().display(), 80);
        form.choice("rotation", names(Project.Turn.values()), pose.rotation().name(), value -> t("rotation." + value));
        form.choice("mirror", names(Project.Flip.values()), pose.mirror().name(), value -> t("mirror." + value));
        form.field("default_origin", entry.design().origin().display(), 80);
        form.info = () -> {
            try {
                var link = session.project.link(entry.group());
                if (link == null) return Component.empty();
                return t("preview", link.target(entry.dimension(), point(form.value(4)).y(), entry.offset()).display());
            } catch (RuntimeException ignored) { return t("error.coordinates"); }
        };
        form.finish(() -> {
            String scope = form.value(0);
            if (scope.equals("reset")) { session.updateEntry(entry, entry, null, true); return; }
            var rotation = Project.Turn.valueOf(form.value(2));
            var mirror = Project.Flip.valueOf(form.value(3));
            var design = scope.equals("portable") ? new Project.Pose(point(form.value(4)), rotation, mirror) : entry.design();
            var changed = new Project.Entry(entry.id(), entry.name(), entry.dimension(), design, entry.visible(),
                entry.locked(), entry.group(), entry.sync(), entry.offset(), entry.data());
            var local = scope.equals("world") ? new Project.Pose(point(form.value(1)), rotation, mirror) : null;
            session.updateEntry(entry, changed, local, scope.equals("portable"));
            say("ready");
        });
    }

    private void groups() {
        var links = session.project.links;
        int count = links.size() + 1;
        groupIndex = Math.floorMod(groupIndex, count);
        var link = groupIndex == links.size() ? null : links.get(groupIndex);
        var form = new Form("groups");
        form.field("group", link == null ? "" : link.name(), 96);
        form.choice("basis", List.of(Project.OVERWORLD, Project.NETHER),
            link == null ? Project.OVERWORLD : link.basis(), LayerScreen::dimensionLabel);
        form.field("anchor_x", link == null ? "0" : link.x().toPlainString(), 40);
        form.field("anchor_z", link == null ? "0" : link.z().toPlainString(), 40);
        form.toggle("auto", link == null || link.enabled());
        form.info = () -> {
            try {
                var draft = group(form);
                var zero = new Project.Point(0, 0, 0);
                var over = draft.target(Project.OVERWORLD, 0, zero);
                var nether = draft.target(Project.NETHER, 0, zero);
                return t("link_preview", over.x(), over.z(), nether.x(), nether.z());
            } catch (RuntimeException ignored) { return t("error.coordinates"); }
        };
        form.extra("prev_group", () -> { groupIndex--; groups(); });
        form.extra("next_group", () -> { groupIndex++; groups(); });
        if (link != null) {
            form.extra("resync", () -> form.execute(() -> say("resynced", session.resync(link.name()))));
            form.extra("delete", () -> form.execute(() -> session.deleteLink(link.name())));
        }
        form.finish(() -> { session.saveLink(group(form)); say("ready"); });
    }

    private Project.Link group(Form form) {
        String name = form.value(0).strip();
        return new Project.Link(name, form.value(1), decimal(form.value(2)), decimal(form.value(3)), form.enabled(4));
    }

    private static List<String> names(Enum<?>[] choices) {
        return java.util.Arrays.stream(choices).map(Enum::name).toList();
    }

    private static BigDecimal decimal(String text) {
        try { return new BigDecimal(text.strip()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("ldl.error.coordinates", invalid); }
    }

    private static Project.Point point(String text) {
        String[] pieces = text.strip().split("\\s*,\\s*|\\s+");
        if (pieces.length != 3) throw new IllegalArgumentException("ldl.error.coordinates");
        try { return new Project.Point(Integer.parseInt(pieces[0]), Integer.parseInt(pieces[1]), Integer.parseInt(pieces[2])); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("ldl.error.coordinates", invalid); }
    }

    private void fileForm(String action, String name, String suffix, FileAction callback) {
        var base = suffix.equals(".ldl") ? session.directory.resolve("projects")
            : fi.dy.masa.litematica.data.DataManager.getSchematicsBaseDirectory();
        var form = new Form(action);
        form.field("path", base.resolve(name).toString(), 4096);
        form.info = () -> t("path_hint");
        form.finish(() -> {
            Path path = Path.of(form.value(0).strip());
            if (!path.isAbsolute()) path = base.resolve(path);
            if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
                throw new IllegalArgumentException("ldl.error.extension");
            }
            Files.createDirectories(base);
            callback.run(path.normalize());
        });
    }

    private void confirm(String prompt, Action action) {
        minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.gui.setScreen(this);
            if (confirmed) attempt(action);
        }, t(prompt), t("confirm_detail")));
    }

    private void attempt(Action action) {
        try { action.run(); refresh(); }
        catch (Exception failure) { status = error(failure); }
    }

    private static Component error(Exception failure) {
        String message = failure.getMessage();
        if (message != null && message.startsWith("ldl.error.")) return Component.translatable(message);
        if (failure instanceof java.nio.file.NoSuchFileException) return t("error.file_missing");
        if (failure instanceof java.nio.file.FileAlreadyExistsException) return t("error.exists");
        if (failure instanceof java.util.zip.ZipException) return t("error.archive");
        DimensionLayers.LOG.warn("Dimension Layers operation failed", failure);
        return t("error.io");
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface FileAction { void run(Path path) throws Exception; }

    private final class Form extends Screen {
        private final List<Field> fields = new ArrayList<>();
        private final List<String> extras = new ArrayList<>();
        private final List<Runnable> extraActions = new ArrayList<>();
        private Action apply;
        private java.util.function.Supplier<Component> info = Component::empty;
        private Component formStatus = Component.empty();
        private int start;
        private int extent;
        private int step;

        Form(String title) { super(t(title)); }

        void field(String label, String value, int length) { fields.add(new Field(label, value, length, null, null)); }
        void toggle(String label, boolean value) {
            choice(label, List.of("true", "false"), Boolean.toString(value), v -> t(v.equals("true") ? "on" : "off"));
        }
        void choice(String label, List<String> options, String value, java.util.function.Function<String, Component> display) {
            fields.add(new Field(label, value, 0, options, display));
        }
        void extra(String name, Runnable action) { extras.add(name); extraActions.add(action); }
        String value(int index) { return fields.get(index).value; }
        boolean enabled(int index) { return Boolean.parseBoolean(value(index)); }
        void finish(Action apply) { this.apply = apply; LayerScreen.this.minecraft.gui.setScreen(this); }

        void execute(Action action) {
            try { action.run(); onClose(); }
            catch (Exception failure) { formStatus = error(failure); }
        }

        @Override public void onClose() { minecraft.gui.setScreen(LayerScreen.this); }

        @Override protected void init() {
            extent = Math.min(540, width - 16);
            start = (width - extent) / 2;
            step = Math.min(25, Math.max(16, (height - 80) / Math.max(1, fields.size())));
            int label = Math.min(142, extent / 3);
            for (int i = 0; i < fields.size(); i++) {
                var field = fields.get(i);
                int y = 28 + step * i;
                if (field.options == null) {
                    var widget = new EditBox(font, start + label, y, extent - label, step - 2, t(field.label));
                    widget.setMaxLength(field.length);
                    widget.setValue(field.value);
                    widget.setResponder(value -> field.value = value);
                    addRenderableWidget(widget);
                } else {
                    addRenderableWidget(Button.builder(field.display.apply(field.value), button -> {
                        int index = field.options.indexOf(field.value);
                        field.value = field.options.get((index + 1) % field.options.size());
                        button.setMessage(field.display.apply(field.value));
                    }).bounds(start + label, y, extent - label, step - 2).build());
                }
            }
            int count = extras.size() + 2;
            int column = extent / count;
            formButton(start, height - 34, column - 2, "apply", () -> execute(apply));
            formButton(start + column, height - 34, column - 2, "back", this::onClose);
            for (int i = 0; i < extras.size(); i++) {
                formButton(start + (i + 2) * column, height - 34, column - 2, extras.get(i), extraActions.get(i));
            }
        }

        private void formButton(int x, int y, int width, String label, Runnable action) {
            addRenderableWidget(Button.builder(t(label), ignored -> action.run()).bounds(x, y, width, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            graphics.fill(0, 0, width, height, 0xe5181c24);
            graphics.centeredText(font, title, width / 2, 8, 0xfff1c574);
            for (int i = 0; i < fields.size(); i++) {
                graphics.text(font, t(fields.get(i).label), start, 30 + step * i, 0xffe1e7ee);
            }
            graphics.text(font, font.plainSubstrByWidth(info.get().getString(), extent), start, height - 46, 0xffe1e7ee);
            graphics.text(font, font.plainSubstrByWidth(formStatus.getString(), extent), start, height - 11, 0xfffac275);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        }
    }

    private static final class Field {
        final String label;
        String value;
        final int length;
        final List<String> options;
        final java.util.function.Function<String, Component> display;

        Field(String label, String value, int length, List<String> options,
              java.util.function.Function<String, Component> display) {
            this.label = label;
            this.value = value;
            this.length = length;
            this.options = options;
            this.display = display;
        }
    }
}
