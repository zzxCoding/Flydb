package com.flydb.core.command;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.flydb.core.api.FlydbConfiguration;
import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** 从已配置 locations 读取单个 SQL 脚本。 */
final class ScriptLoader {

    private ScriptLoader() {
    }

    static String load(FlydbConfiguration configuration, String script) {
        String value = loadIfExists(configuration, script);
        if (value == null) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "无法在 locations 中读取脚本: " + script);
        }
        return value;
    }

    static String loadIfExists(FlydbConfiguration configuration, String script) {
        for (String location : configuration.locations()) {
            byte[] bytes = read(location, script, configuration);
            if (bytes != null) {
                return new String(bytes, configuration.encoding());
            }
        }
        return null;
    }

    private static byte[] read(String location, String script,
                               FlydbConfiguration configuration) {
        try {
            if (location.startsWith("filesystem:")) {
                Path path = Paths.get(location.substring("filesystem:".length())).resolve(script);
                return Files.exists(path) ? Files.readAllBytes(path) : null;
            }
            if (location.startsWith("classpath:")) {
                String base = trimSlashes(location.substring("classpath:".length()));
                String resource = base.isEmpty() ? script : base + "/" + script;
                URL url = configuration.classLoader().getResource(resource);
                return url == null ? null : readUrl(url);
            }
            return null;
        } catch (IOException e) {
            throw new FlydbException(ErrorCode.MIGRATION_EXECUTION_FAILED,
                    "读取脚本 " + script + " 失败: " + e.getMessage(), e);
        }
    }

    private static byte[] readUrl(URL url) throws IOException {
        InputStream input = url.openStream();
        try {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String trimSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) result = result.substring(1);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
