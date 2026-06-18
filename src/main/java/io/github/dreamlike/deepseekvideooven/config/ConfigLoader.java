package io.github.dreamlike.deepseekvideooven.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public final class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private ConfigLoader() {}

    public static OvenConfig load(Path configPath) throws IOException {
        if (!Files.exists(configPath)) {
            return OvenConfig.empty();
        }
        var raw = Files.readString(configPath);
        var resolved = resolveEnv(raw);
        return MAPPER.readValue(resolved, OvenConfig.class);
    }

    static String resolveEnv(String input) {
        var matcher = ENV_PATTERN.matcher(input);
        var sb = new StringBuilder();
        while (matcher.find()) {
            var envName = matcher.group(1);
            var envValue = System.getenv(envName);
            matcher.appendReplacement(sb, envValue != null ? envValue : matcher.group());
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
