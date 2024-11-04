package pro.deta.orion.util;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class FileUtils {
    public static void deleteFileIfExists(Path path) {
        try {
            // remove previous identity
            if (path.toFile().exists())
                org.apache.commons.io.FileUtils.delete(path.toFile());
        } catch (IOException e) {
            log.error("Error while deleting {}", path, e);
        }
    }

    public static void clearLocalDirectory(Path path) {
        try {
            org.apache.commons.io.FileUtils.cleanDirectory(path.toFile());
        } catch (Exception e) {
            log.warn("Failed to delete directory {}", path, e);
        }
        mkdirs(path);
    }

    public static void saveIntoFile(String contents, Path target) {
        try {
            Files.writeString(target, contents);
        } catch (IOException e) {
            log.error("Error while saving into file {}", target, e);
        }
    }

    public static void mkdirs(Path path) {
        try {
            path.toFile().mkdirs();
        } catch (Exception e) {
            log.warn("Failed to create directory {}", path, e);
        }
    }

    public static void forceDelete(File file) {
        if (file.exists()) {
            try {
                org.apache.commons.io.FileUtils.forceDelete(file);
            } catch (IOException e) {
                log.error("Can't delete file: {}", file);
            }
        }
    }

    /**
     * removes and create new empty directory
     *
     * @param path
     * @throws IOException
     */
    public static void wipeDirectory(Path path) {
        forceDelete(path.toFile());
        mkdirs(path);
    }
}

