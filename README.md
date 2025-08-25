## simlab-ug-vr-server-client

Java 17 + JavaFX 21 gRPC server and client for running UG4 simulations from a GUI. The server exposes gRPC endpoints to analyze Lua scripts and execute simulations; the client connects to the server to configure parameters, launch runs, monitor progress, and fetch output files.

### Features
- **Server UI**: Configure port, UG4 executable path, and working directory; start/stop a gRPC server.
- **Client UI**: Connect to server, analyze a Lua script to auto-detect parameters, edit values, run/stop simulations, view logs/progress, and fetch output files.
- **Protocol**: Defined in `src/main/proto/simulation.proto` and compiled via the Gradle Protobuf plugin.

## Prerequisites
- **JDK 17** (JAVA_HOME should point to JDK 17)
- **UG4 executable** installed locally (path needed by server or provided from the client)
- Internet access for Gradle to fetch dependencies on first build

Notes:
- JavaFX is pulled via the OpenJFX Gradle plugin; no manual JavaFX install is required when running via Gradle tasks below.
- Default gRPC port is **50051**.

## Build
Use the Gradle wrapper in this project (no system Gradle needed):

```bash
./gradlew clean build
```

This will:
- Generate gRPC/protobuf sources under `build/generated/source/proto/...`
- Compile Java sources targeting Java 17

## Run

### Start the server (JavaFX UI)
```bash
./gradlew run
```
Or run the main class `com.simlab.ug.server.ServerApplication` from your IDE.

At startup, set:
- **Server Port** (default `50051`)
- **UG4 Executable** (required)
- **Working Directory** (defaults to current directory)

Click “Start Server”. The status and log panels will update; active simulations are listed.

### Start the client (JavaFX UI)
```bash
./gradlew runClient
```
Or run the main class `com.simlab.ug.client.ClientApplication` from your IDE.

In the client:
1. On the Connection tab, enter host/port (e.g., `localhost:50051`) and click “Connect”.
2. On the Simulation tab:
   - Choose a Lua script.
   - Provide the UG4 executable path (auto-filled when connected to a server that reports it).
   - Optionally set an output directory for results.
   - Click “Analyze Script” to detect parameters, then adjust values as needed.
   - Click “Run Simulation” to start; use “Stop Simulation” to cancel.
3. Use the Results tab to browse output files and download them.

## Project structure
- `com.simlab.ug.server` — JavaFX server app and gRPC service (`SimulationServiceImpl`).
- `com.simlab.ug.client` — JavaFX client app and UI controllers.
- `com.simlab.ug.common` — Shared utilities (e.g., `LuaScriptParser`, `SimulationExecutor`).
- `src/main/proto/simulation.proto` — gRPC service and messages; Java stubs are generated at build.

Gradle highlights:
- OpenJFX plugin (`org.openjfx.javafxplugin`) with modules: `javafx.controls`, `javafx.fxml`.
- Protobuf plugin compiles `.proto` and generates Java + gRPC stubs.
- Application plugin sets the default main class to the server app.
- A `runClient` task is provided to launch the client with the correct JavaFX modules.

## Distribution
Create runnable distributions:
```bash
./gradlew installDist    # installs under build/install/<projectName>
./gradlew distZip        # creates a zip distribution under build/distributions
```

## Development
- Edit `src/main/proto/simulation.proto` to evolve the API; stubs regenerate on build.
- Generated sources are automatically added to the main source set (see `build.gradle`).
- If you change protobuf/gRPC versions, update them in `build.gradle`.

## Troubleshooting
- **JavaFX runtime errors**: Run via the provided Gradle tasks (`run`, `runClient`) so module-paths are set. Ensure JDK 17 is in use.
- **Connection issues**: Verify host/port, server is running, and firewall allows traffic on the configured port.
- **UG4 not found**: Provide a valid UG4 executable path in the server UI or client field.

## License
TBD


