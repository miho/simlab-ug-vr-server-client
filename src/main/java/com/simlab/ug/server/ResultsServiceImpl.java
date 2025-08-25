package com.simlab.ug.server;

import com.google.protobuf.ByteString;
import com.simlab.ug.grpc.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ResultsServiceImpl extends ResultsServiceGrpc.ResultsServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(ResultsServiceImpl.class);

    private final GltfGroupManager groupManager = new GltfGroupManager();
    private final ExecutorService watcherExecutor = Executors.newCachedThreadPool();

    private volatile String defaultRootDirectory;

    public ResultsServiceImpl(String defaultRootDirectory) {
        this.defaultRootDirectory = defaultRootDirectory;
    }

    public void setDefaultRootDirectory(String dir) {
        this.defaultRootDirectory = dir;
    }

    @Override
    public void listGltfGroups(ListGltfGroupsRequest request, StreamObserver<ListGltfGroupsResponse> responseObserver) {
        try {
            String root = request.getRootDirectory().isEmpty() ? defaultRootDirectory : request.getRootDirectory();
            Path rootPath = Paths.get(root);
            List<GltfGroup> groups = groupManager.scanForGroups(rootPath);
            ListGltfGroupsResponse resp = ListGltfGroupsResponse.newBuilder().addAllGroups(groups).build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error listing GLTF groups", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void getGroupGltfFiles(GetGroupGltfFilesRequest request, StreamObserver<FileData> responseObserver) {
        try {
            Optional<GltfGroup> maybeGroup = groupManager.getGroupById(request.getGroupId());
            if (maybeGroup.isEmpty()) {
                responseObserver.onCompleted();
                return;
            }
            GltfGroup group = maybeGroup.get();

            List<GroupFile> filesToSend = new ArrayList<>();
            if (request.hasTimeStep()) {
                int step = request.getTimeStep();
                for (GroupFile f : group.getFilesList()) {
                    if (f.getTimeStep() == step) filesToSend.add(f);
                }
            } else {
                filesToSend.addAll(group.getFilesList());
            }

            for (GroupFile f : filesToSend) {
                Path path = Paths.get(f.getFullPath());
                if (!Files.exists(path)) continue;
                byte[] bytes = Files.readAllBytes(path);
                String mime = Files.probeContentType(path);
                if (mime == null) mime = "model/gltf+json";
                responseObserver.onNext(FileData.newBuilder()
                        .setFilename(f.getFilename())
                        .setContent(ByteString.copyFrom(bytes))
                        .setMimeType(mime)
                        .build());
            }
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Error serving GLTF files", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void subscribeGltfFileEvents(SubscribeGltfFileEventsRequest request, StreamObserver<GltfFileEvent> responseObserver) {
        String root = request.getRootDirectory().isEmpty() ? defaultRootDirectory : request.getRootDirectory();
        Path rootPath = Paths.get(root);

        WatchService watchService;
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            responseObserver.onError(e);
            return;
        }

        // initial snapshot
        try {
            List<GltfGroup> groups = groupManager.scanForGroups(rootPath);
            for (GltfGroup g : groups) {
                responseObserver.onNext(GltfFileEvent.newBuilder()
                        .setType(GltfFileEventType.GROUP_CREATED)
                        .setGroupId(g.getGroupId())
                        .setGroupName(g.getGroupName())
                        .setGroup(g)
                        .build());
            }
        } catch (IOException e) {
            logger.warn("Initial scan failed", e);
        }

        // Register directories recursively
        try {
            Files.walk(rootPath)
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        try {
                            dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            logger.error("Failed to register watch service", e);
            responseObserver.onError(e);
            return;
        }

        // Track open stream to allow shutdown
        final WatchService ws = watchService;
        watcherExecutor.submit(() -> {
            try {
                while (true) {
                    WatchKey key = ws.poll(500, TimeUnit.MILLISECONDS);
                    if (key == null) {
                        if (Thread.currentThread().isInterrupted()) break;
                        continue;
                    }
                    Path dir = (Path) key.watchable();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (!(event.context() instanceof Path)) continue;
                        Path name = (Path) event.context();
                        Path child = dir.resolve(name);
                        String lower = child.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (!(lower.endsWith(".gltf") || lower.endsWith(".glb"))) continue;

                        try {
                            // Recompute groups for this directory only
                            List<GltfGroup> groups = groupManager.scanForGroups(child.getParent());
                            Map<String, GltfGroup> idToGroup = new HashMap<>();
                            for (GltfGroup g : groups) idToGroup.put(g.getGroupId(), g);

                            // Emit events for any matching group containing this file
                            for (GltfGroup g : groups) {
                                for (GroupFile gf : g.getFilesList()) {
                                    if (gf.getFullPath().equals(child.toAbsolutePath().toString())) {
                                        GltfFileEventType t = event.kind() == StandardWatchEventKinds.ENTRY_CREATE ? GltfFileEventType.FILE_CREATED : GltfFileEventType.FILE_MODIFIED;
                                        responseObserver.onNext(GltfFileEvent.newBuilder()
                                                .setType(t)
                                                .setGroupId(g.getGroupId())
                                                .setGroupName(g.getGroupName())
                                                .setFile(gf)
                                                .build());
                                        // Also emit group updated event
                                        responseObserver.onNext(GltfFileEvent.newBuilder()
                                                .setType(GltfFileEventType.GROUP_UPDATED)
                                                .setGroupId(g.getGroupId())
                                                .setGroupName(g.getGroupName())
                                                .setGroup(g)
                                                .build());
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            logger.warn("Failed handling file event", ex);
                        }
                    }
                    boolean valid = key.reset();
                    if (!valid) break;
                }
            } catch (InterruptedException ignored) {
            } finally {
                try { ws.close(); } catch (IOException ignored) {}
            }
        });
    }
}


