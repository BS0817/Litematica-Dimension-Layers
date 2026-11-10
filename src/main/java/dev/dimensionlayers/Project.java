package dev.dimensionlayers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class Project {
    static final String OVERWORLD = "minecraft:overworld";
    static final String NETHER = "minecraft:the_nether";
    static final String END = "minecraft:the_end";
    static final int MAX_ENTRIES = 128;
    static final int LIMIT = 30_000_000;

    enum Turn { NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90 }
    enum Flip { NONE, LEFT_RIGHT, FRONT_BACK }

    record Point(int x, int y, int z) {
        Point {
            if (Math.abs((long) x) > LIMIT || Math.abs((long) z) > LIMIT || y < -2048 || y > 2047) {
                throw new IllegalArgumentException("ldl.error.coordinates");
            }
        }

        Point offset(Point offset) {
            return new Point(x + offset.x, y + offset.y, z + offset.z);
        }

        String display() { return x + ", " + y + ", " + z; }
    }

    record Pose(Point origin, Turn rotation, Flip mirror) {
        Pose { Objects.requireNonNull(origin); Objects.requireNonNull(rotation); Objects.requireNonNull(mirror); }
    }

    record Link(String name, String basis, BigDecimal x, BigDecimal z, boolean enabled) {
        Link {
            title(name);
            if (!vanillaPair(basis)) throw new IllegalArgumentException("ldl.error.basis");
            x = anchor(x);
            z = anchor(z);
        }

        Point target(String dimension, int height, Point offset) {
            if (!vanillaPair(dimension)) throw new IllegalArgumentException("ldl.error.basis");
            int power = basis.equals(dimension) ? 0 : basis.equals(OVERWORLD) ? -1 : 1;
            return new Point(convert(x, power), height, convert(z, power)).offset(offset);
        }

        private static BigDecimal anchor(BigDecimal value) {
            Objects.requireNonNull(value);
            if (value.precision() > 32 || Math.abs((long) value.scale()) > 16
                    || value.abs().compareTo(BigDecimal.valueOf(LIMIT)) > 0) {
                throw new IllegalArgumentException("ldl.error.coordinates");
            }
            return value;
        }

        private static int convert(BigDecimal value, int power) {
            BigDecimal scaled = power == -1 ? value.divide(BigDecimal.valueOf(8))
                : power == 1 ? value.multiply(BigDecimal.valueOf(8)) : value;
            return scaled.setScale(0, RoundingMode.FLOOR).intValueExact();
        }
    }

    record Entry(UUID id, String name, String dimension, Pose design, boolean visible,
                 boolean locked, String group, boolean sync, Point offset, byte[] data) {
        Entry {
            Objects.requireNonNull(id);
            title(name);
            if (dimension == null || dimension.length() > 128 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("ldl.error.dimension");
            }
            Objects.requireNonNull(design);
            if (group != null) {
                title(group);
                if (!vanillaPair(dimension)) throw new IllegalArgumentException("ldl.error.basis");
            } else if (sync) {
                throw new IllegalArgumentException("ldl.error.group");
            }
            Objects.requireNonNull(offset);
            Objects.requireNonNull(data);
        }

        Entry withData(byte[] replacement) {
            return new Entry(id, name, dimension, design, visible, locked, group, sync, offset, replacement);
        }
    }

    final UUID id;
    String name;
    String description;
    boolean visible = true;
    final List<Entry> entries = new ArrayList<>();
    final List<Link> links = new ArrayList<>();

    Project(UUID id, String name, String description) {
        this.id = Objects.requireNonNull(id);
        rename(name, description);
    }

    void rename(String name, String description) {
        title(name);
        if (description == null || description.length() > 1024) throw new IllegalArgumentException("ldl.error.description");
        this.name = name;
        this.description = description;
    }

    void validate() {
        if (entries.size() > MAX_ENTRIES || links.size() > MAX_ENTRIES) throw new IllegalArgumentException("ldl.error.limit");
        var ids = new HashSet<UUID>();
        var names = new HashSet<String>();
        for (Link link : links) if (!names.add(link.name)) throw new IllegalArgumentException("ldl.error.group");
        for (Entry entry : entries) {
            if (!ids.add(entry.id)) throw new IllegalArgumentException("ldl.error.duplicate");
            if (entry.group != null && !names.contains(entry.group)) throw new IllegalArgumentException("ldl.error.group");
        }
    }

    Link link(String name) {
        if (name == null) return null;
        return links.stream().filter(link -> link.name.equals(name)).findFirst().orElse(null);
    }

    static boolean vanillaPair(String dimension) {
        return OVERWORLD.equals(dimension) || NETHER.equals(dimension);
    }

    static void title(String name) {
        if (name == null || name.isBlank() || name.length() > 96) throw new IllegalArgumentException("ldl.error.name");
    }
}
