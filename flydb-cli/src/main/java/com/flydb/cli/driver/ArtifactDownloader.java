package com.flydb.cli.driver;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

/** 使用 Maven settings 中的认证和代理，将单个制品原子下载到 Flydb 缓存。 */
final class ArtifactDownloader {

    void download(MavenSettings.Repository repository, String artifactPath, Path target)
            throws Exception {
        URL url = new URL(repository.artifactUrl(artifactPath));
        URLConnection connection = repository.proxy == null
                ? url.openConnection() : url.openConnection(repository.proxy.proxyFor(url.getHost()));
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Flydb/2");
        addBasicAuthentication(connection, "Authorization", repository.credentials);
        if (repository.proxy != null) {
            addBasicAuthentication(connection, "Proxy-Authorization",
                    repository.proxy.credentials);
        }
        if (connection instanceof HttpURLConnection) {
            int status = ((HttpURLConnection) connection).getResponseCode();
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("HTTP " + status);
            }
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(),
                ".part");
        try (InputStream input = connection.getInputStream()) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
            if (connection instanceof HttpURLConnection) {
                ((HttpURLConnection) connection).disconnect();
            }
        }
    }

    private static void addBasicAuthentication(URLConnection connection, String header,
                                               MavenSettings.Credentials credentials) {
        if (credentials == null || credentials.username == null) return;
        String password = credentials.password == null ? "" : credentials.password;
        String token = credentials.username + ":" + password;
        connection.setRequestProperty(header, "Basic " + Base64.getEncoder().encodeToString(
                token.getBytes(StandardCharsets.UTF_8)));
    }
}
