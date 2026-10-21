package ru.pukpukov.territorywar;

import java.util.jar.JarFile;
import java.net.URLClassLoader;
import java.net.URL;
import java.nio.file.Path;
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
    @Test void allPackagedShaderLoadersAreLoadable() throws Exception {
        Path file = Path.of(System.getProperty("packaged.jar"));
        try (JarFile jar = new JarFile(file.toFile());
             URLClassLoader loader = new URLClassLoader(new URL[]{file.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var shaders = jar.stream().filter(entry -> entry.getName().startsWith("com/sun/prism/shader/") && entry.getName().endsWith("_Loader.class")).toList();
            assertFalse(shaders.isEmpty());
            assertNotNull(jar.getJarEntry("com/sun/prism/shader/FillRoundRect_Color_Loader.class"));
            assertNotNull(jar.getJarEntry("com/sun/prism/es2/glsl/FillRoundRect_Color.frag"));
            for (var shader : shaders) Class.forName(shader.getName().replace('/', '.').replace(".class", ""), false, loader);
        }
    }

}
