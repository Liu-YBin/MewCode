package com.mewcode.util;

import java.nio.file.Path;

public final class UserHome {
    private UserHome() {
    }

    public static Path path() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome == null || userHome.isBlank() ? "." : userHome).toAbsolutePath().normalize();

//        String configured = firstUsable(System.getProperty("mewcode.user.home"), System.getenv("MEWCODE_USER_HOME"));
//        if (configured != null) {
//            return Path.of(configured).toAbsolutePath().normalize();
//        }
//
//        String userHome = System.getProperty("user.home");
//        if (isUsableHome(userHome)) {
//            return Path.of(userHome).toAbsolutePath().normalize();
//        }
//
//        configured = firstUsable(System.getenv("USERPROFILE"), System.getenv("HOME"));
//        if (configured != null) {
//            return Path.of(configured).toAbsolutePath().normalize();
//        }
//
//        return Path.of(userHome == null || userHome.isBlank() ? "." : userHome).toAbsolutePath().normalize();
    }

    private static String firstUsable(String first, String second) {
        if (isUsableHome(first)) {
            return first.trim();
        }
        if (isUsableHome(second)) {
            return second.trim();
        }
        return null;
    }

    private static boolean isUsableHome(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        Path path;
        try {
            path = Path.of(raw.trim()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return false;
        }
        return path.getParent() != null;
    }
}
