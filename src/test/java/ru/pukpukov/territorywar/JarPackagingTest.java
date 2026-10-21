package ru.pukpukov.territorywar;

import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Run after package with -Dpackaged.jar=target/territory-war-VERSION.jar. */
@EnabledIfSystemProperty(named = "packaged.jar", matches = ".+")
class JarPackagingTest {
    @Test void primaryJarIsExecutableAndSelfContained() throws Exception {
        try (JarFile jar = new JarFile(System.getProperty("packaged.jar"))) {
            assertEquals("ru.pukpukov.territorywar.Launcher", jar.getManifest().getMainAttributes().getValue("Main-Class"));
            for (String entry : new String[]{"ru/pukpukov/territorywar/Launcher.class", "javafx/application/Application.class", "org/joor/Reflect.class"}) {
                assertNotNull(jar.getJarEntry(entry), "Missing runtime class: " + entry);
            }
        }
    }
}
