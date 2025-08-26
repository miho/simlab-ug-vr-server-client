package com.simlab.ug.server;

import com.simlab.ug.grpc.GltfGroup;
import com.simlab.ug.grpc.GroupFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups .gltf/.glb files by common base name and detects time-series semantics.
 */
public class GltfGroupManager {
    private static final Logger logger = LoggerFactory.getLogger(GltfGroupManager.class);

    private static final Pattern TIME_STEP_PATTERN = Pattern.compile(".*[_\\.]?t(\\d+)\\.(gltf|glb)$", Pattern.CASE_INSENSITIVE);

    private final Map<String, GltfGroup> groupIdToGroup = new ConcurrentHashMap<>();

    public List<GltfGroup> scanForGroups(Path root) throws IOException {
        Map<String, List<Path>> keyToFiles = new HashMap<>();

        if (!Files.exists(root)) {
            return Collections.emptyList();
        }

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fileName.endsWith(".gltf") || fileName.endsWith(".glb")) {
                    String key = buildGroupingKey(file.getParent(), file.getFileName().toString());
                    keyToFiles.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<GltfGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<Path>> entry : keyToFiles.entrySet()) {
            List<Path> files = entry.getValue();
            files.sort(Comparator.comparing(Path::toString));
            Path any = files.get(0);
            String groupId = hash(entry.getKey());
            String directory = any.getParent().toString();
            String groupName = deriveGroupName(files);
            boolean isTimeSeries = files.stream().allMatch(GltfGroupManager::hasTimeStep);
            Set<Integer> steps = new TreeSet<>();
            List<GroupFile> groupFiles = new ArrayList<>();
            for (Path f : files) {
                int step = extractTimeStep(f.getFileName().toString());
                if (step >= 0) {
                    steps.add(step);
                }
                groupFiles.add(buildGroupFile(f, step));
            }

            GltfGroup.Builder b = GltfGroup.newBuilder()
                    .setGroupId(groupId)
                    .setGroupName(groupName)
                    .setDirectory(directory)
                    .setPattern(buildPattern(files))
                    .setIsTimeSeries(isTimeSeries)
                    .setFileCount(groupFiles.size());
            for (Integer s : steps) {
                b.addTimeSteps(s);
            }
            b.addAllFiles(groupFiles);
            GltfGroup group = b.build();
            groupIdToGroup.put(groupId, group);
            groups.add(group);
        }

        groups.sort(Comparator.comparing(GltfGroup::getGroupName));
        return groups;
    }

    public Optional<GltfGroup> getGroupById(String groupId) {
        return Optional.ofNullable(groupIdToGroup.get(groupId));
    }

    private static String deriveGroupName(List<Path> files) {
        String base = stripTimeStep(files.get(0).getFileName().toString());
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base;
    }

    private static String stripTimeStep(String filename) {
        Matcher m = TIME_STEP_PATTERN.matcher(filename);
        if (m.matches()) {
            return filename.replaceAll("[_\\.]?t\\d+(\\.(gltf|glb))$", "$1");
        }
        return filename;
    }

    private static boolean hasTimeStep(Path file) {
        return extractTimeStep(file.getFileName().toString()) >= 0;
    }

    private static int extractTimeStep(String filename) {
        Matcher m = TIME_STEP_PATTERN.matcher(filename);
        if (m.matches()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static GroupFile buildGroupFile(Path file, int timeStep) throws IOException {
        long size = Files.size(file);
        long mod = Files.getLastModifiedTime(file).toMillis();
        return GroupFile.newBuilder()
                .setFilename(file.getFileName().toString())
                .setFullPath(file.toAbsolutePath().toString())
                .setFileSize(size)
                .setLastModifiedMs(mod)
                .setTimeStep(timeStep >= 0 ? timeStep : 0)
                .build();
    }

    private static String buildPattern(List<Path> files) {
        String example = files.get(0).getFileName().toString();
        if (hasTimeStep(files.get(0))) {
            return example.replaceAll("[_\\.]?t\\d+(\\.(gltf|glb))$", "_t*.gltf");
        }
        return example;
    }

    private static String buildGroupingKey(Path dir, String filename) {
        String base = stripTimeStep(filename).toLowerCase(Locale.ROOT);
        return dir.toAbsolutePath() + "::" + base;
    }

    private static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12 && i < d.length; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}



