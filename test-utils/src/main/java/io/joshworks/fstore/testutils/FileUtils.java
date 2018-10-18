package io.joshworks.fstore.testutils;

import io.joshworks.fstore.core.properties.AppProperties;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;

public class FileUtils {

    //terrible work around for waiting the mapped data to release file lock
    public static void tryDelete(File file) {
        int maxTries = 2;
        int counter = 0;
        while (counter++ < maxTries) {
            try {
                if (file.isDirectory()) {
                    String[] list = file.list();
                    if (list != null) {
                        for (String f : list) {
                            File item = new File(file, f);
                            if (item.isDirectory()) {
                                tryDelete(item);
                            }
                            System.out.println("Deleting " + item);
                            Files.deleteIfExists(item.toPath());
                        }
                    }
                }
                System.out.println("Deleting " + file);
                Files.deleteIfExists(file.toPath());
                break;
            } catch (Exception e) {
                System.err.println(":: FAILED TO DELETE FILE :: " + e.getMessage());
                e.printStackTrace();
                sleep(2000);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }
    }


    private static File TEST_DIR = AppProperties.create().get("test.dir")
            .map(File::new)
            .orElse(tempFolder());

    private static File tempFolder() {
        try {
            return Files.createTempDirectory("fstore-test").toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static File testFile() {
        return testFile(UUID.randomUUID().toString().substring(0, 8));
    }

    private static File testFile(String name) {
        return new File(testFolder(), name);
    }

    public static File testFolder() {
        return testFolder(UUID.randomUUID().toString().substring(0, 8));
    }

    private static File testFolder(String name) {
        try {
            File file = new File(TEST_DIR, name);
            Files.createDirectories(file.toPath());
            return file;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
