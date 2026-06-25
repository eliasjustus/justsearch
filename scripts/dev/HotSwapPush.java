///usr/bin/env java --add-modules jdk.jdi --source 25 "$0" "$@"; exit $?
//
// HotSwapPush — Pushes recompiled .class files to a running JVM via JDWP.
//
// Phase 0 hot-reload trigger for JustSearch dev workflow (tempdoc 305).
// Connects to the Worker's JDWP agent and redefines classes that changed
// since the last push. Uses a marker file to track timestamps.
//
// Usage:
//   java --add-modules jdk.jdi scripts/dev/HotSwapPush.java <port> <classesDir>
//
// Example:
//   java --add-modules jdk.jdi scripts/dev/HotSwapPush.java 5005 \
//       modules/indexer-worker/build/classes/java/main
//
// Prerequisites:
//   - Worker running with JUSTSEARCH_DEV_DEBUG_PORT=<port>
//   - Classes recompiled: ./gradlew :modules:indexer-worker:classes
//
// Limitations (standard HotSwap on Temurin):
//   - Only method body changes are supported
//   - Adding/removing methods, fields, or constructors is rejected
//   - Use Phase 1 (JBR + HotswapAgent) for structural changes

import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class HotSwapPush {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java --add-modules jdk.jdi HotSwapPush.java <port> <classesDir>");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);
        Path classesDir = Path.of(args[1]).toAbsolutePath().normalize();

        if (!Files.isDirectory(classesDir)) {
            System.err.println("Classes directory does not exist: " + classesDir);
            System.err.println("Run: ./gradlew :modules:indexer-worker:classes");
            System.exit(1);
        }

        // Marker file lives outside the classes dir to survive Gradle clean builds.
        Path markerFile = classesDir.getParent().resolve(".hotswap-marker");

        // Determine cutoff time (last successful push)
        long cutoff = Files.exists(markerFile)
            ? Files.getLastModifiedTime(markerFile).toMillis()
            : 0;

        // Find .class files modified since last push
        List<Path> changed = new ArrayList<>();
        try (var stream = Files.walk(classesDir)) {
            stream.filter(p -> p.toString().endsWith(".class"))
                  .filter(p -> {
                      try {
                          return Files.getLastModifiedTime(p).toMillis() > cutoff;
                      } catch (IOException e) {
                          return false;
                      }
                  })
                  .forEach(changed::add);
        }

        if (changed.isEmpty()) {
            System.out.println("No changed classes since last push.");
            return;
        }

        System.out.printf("Found %d changed class file(s), connecting to 127.0.0.1:%d...%n",
            changed.size(), port);

        // Connect to JDWP agent
        AttachingConnector connector = Bootstrap.virtualMachineManager()
            .attachingConnectors().stream()
            .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("SocketAttach connector not found"));

        Map<String, Connector.Argument> connArgs = connector.defaultArguments();
        connArgs.get("hostname").setValue("127.0.0.1");
        connArgs.get("port").setValue(String.valueOf(port));

        VirtualMachine vm;
        try {
            vm = connector.attach(connArgs);
        } catch (IOException e) {
            System.err.println("Failed to connect to JDWP agent on port " + port);
            System.err.println("Is the Worker running with JUSTSEARCH_DEV_DEBUG_PORT=" + port + "?");
            System.exit(1);
            return; // unreachable, satisfies compiler
        }

        try {
            Map<ReferenceType, byte[]> redefinitions = new LinkedHashMap<>();
            List<String> notLoaded = new ArrayList<>();

            for (Path classFile : changed) {
                // Convert file path to class name: io/justsearch/Foo.class -> io.justsearch.Foo
                Path relative = classesDir.relativize(classFile);
                String className = relative.toString()
                    .replace(File.separatorChar, '.')
                    .replace('/', '.')
                    .replaceAll("\\.class$", "");

                List<ReferenceType> types = vm.classesByName(className);
                if (types.isEmpty()) {
                    notLoaded.add(className);
                    continue;
                }

                byte[] bytecode = Files.readAllBytes(classFile);
                redefinitions.put(types.get(0), bytecode);
            }

            if (redefinitions.isEmpty()) {
                System.out.printf("None of the %d changed class(es) are loaded in the target VM.%n",
                    changed.size());
            } else {
                try {
                    vm.redefineClasses(redefinitions);
                    System.out.printf("Redefined %d class(es):%n", redefinitions.size());
                    for (ReferenceType type : redefinitions.keySet()) {
                        System.out.println("  " + type.name());
                    }
                } catch (UnsupportedOperationException e) {
                    System.err.println("HotSwap not supported by target VM: " + e.getMessage());
                    System.exit(1);
                } catch (Exception e) {
                    // Common case: structural change rejected by standard HotSwap
                    System.err.println("HotSwap failed: " + e.getMessage());
                    System.err.println("If you added/removed methods or fields, use Phase 1 (JBR + HotswapAgent).");
                    System.exit(1);
                }
            }

            if (!notLoaded.isEmpty()) {
                System.out.printf("Skipped %d class(es) not loaded in target VM.%n", notLoaded.size());
            }
        } finally {
            vm.dispose();
        }

        // Update marker timestamp on success
        Files.writeString(markerFile, String.valueOf(System.currentTimeMillis()));
    }
}
