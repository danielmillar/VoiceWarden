package dev.voicewarden.core.ipc;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public sealed interface EngineLaunchTarget permits EngineLaunchTarget.Jar, EngineLaunchTarget.MainClass {

    List<String> toJavaArgs();

    record Jar(Path jar) implements EngineLaunchTarget {
        public Jar {
            Objects.requireNonNull(jar, "jar");
        }

        @Override
        public List<String> toJavaArgs() {
            return List.of("-jar", jar.toAbsolutePath().toString());
        }
    }

    record MainClass(String classpath, String mainClass) implements EngineLaunchTarget {
        public MainClass {
            Objects.requireNonNull(classpath, "classpath");
            Objects.requireNonNull(mainClass, "mainClass");
        }

        @Override
        public List<String> toJavaArgs() {
            return List.of("-cp", classpath, mainClass);
        }
    }
}
