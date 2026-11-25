package dev.voicewarden.engine;

import java.util.Locale;

public enum NativePlatform {
    LINUX_X64("linux-x64"),
    LINUX_AARCH64("linux-aarch64"),
    OSX_AARCH64("osx-aarch64"),
    OSX_X64("osx-x64"),
    WIN_X64("win-x64"),
    WIN_ARM64("win-arm64");

    private final String id;

    NativePlatform(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String artifactName() {
        return "sherpa-onnx-native-lib-" + id;
    }

    public String resourceDirectory() {
        return "sherpa-onnx/native/" + id + "/";
    }

    public static NativePlatform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static NativePlatform detect(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean arm = arch.startsWith("aarch64") || arch.startsWith("arm64");
        boolean x64 = arch.startsWith("amd64") || arch.startsWith("x86_64") || arch.equals("x64");
        if (!arm && !x64) {
            throw new IllegalStateException("sherpa-onnx has no native build for CPU architecture '" + osArch
                    + "'. Supported: x86_64/amd64 and aarch64/arm64.");
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arm ? OSX_AARCH64 : OSX_X64;
        }
        if (os.startsWith("windows")) {
            return arm ? WIN_ARM64 : WIN_X64;
        }
        if (os.contains("linux")) {
            return arm ? LINUX_AARCH64 : LINUX_X64;
        }
        throw new IllegalStateException("sherpa-onnx has no native build for operating system '" + osName
                + "'. Supported: Linux, macOS and Windows.");
    }
}
