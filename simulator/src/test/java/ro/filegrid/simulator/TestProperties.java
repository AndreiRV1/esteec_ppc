package ro.filegrid.simulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import ro.filegrid.simulator.config.SimulatorProperties;

/**
 * Binds application.yml (plus application-&lt;profile&gt;.yml when
 * {@code spring.profiles.active} is in the overrides) exactly like Spring does.
 */
public final class TestProperties {

    private TestProperties() {}

    public static SimulatorProperties load(Map<String, Object> overrides) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("overrides", Map.copyOf(overrides)));
        Object profile = overrides.get("spring.profiles.active");
        if (profile != null) addYaml(env, "application-" + profile + ".yml");
        addYaml(env, "application.yml");
        return Binder.get(env).bind("simulator", SimulatorProperties.class).get();
    }

    public static SimulatorProperties defaults() {
        return load(Map.of());
    }

    private static void addYaml(StandardEnvironment env, String name) {
        try {
            new YamlPropertySourceLoader().load(name, new ClassPathResource(name)).forEach(env.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
