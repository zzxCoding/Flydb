package com.flydb.cli.driver;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.flydb.core.exception.ErrorCode;
import com.flydb.core.exception.FlydbException;

/** Maven settings.xml 中与制品解析相关的有效配置。 */
final class MavenSettings {
    private final Path localRepository;
    private final List<Repository> repositories;
    private final Map<String, Credentials> servers;
    private final List<Mirror> mirrors;
    private final ProxyConfiguration proxy;
    private final Path source;

    private MavenSettings(Path localRepository, List<Repository> repositories,
                          Map<String, Credentials> servers, List<Mirror> mirrors,
                          ProxyConfiguration proxy, Path source) {
        this.localRepository = localRepository;
        this.repositories = repositories;
        this.servers = servers;
        this.mirrors = mirrors;
        this.proxy = proxy;
        this.source = source;
    }

    static MavenSettings load(String configuredPath) {
        Path file = configuredPath == null || configuredPath.trim().isEmpty()
                ? Paths.get(System.getProperty("user.home"), ".m2", "settings.xml")
                : Paths.get(configuredPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            if (configuredPath != null && !configuredPath.trim().isEmpty()) {
                throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                        "Maven settings 文件不存在: " + file);
            }
            return empty(file);
        }
        try (InputStream input = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(input);
            Element root = document.getDocumentElement();
            Path local = path(text(root, "localRepository"));
            Map<String, Credentials> servers = readServers(child(root, "servers"));
            List<Mirror> mirrors = readMirrors(child(root, "mirrors"));
            Set<String> activeProfiles = readValues(child(root, "activeProfiles"), "activeProfile");
            List<Repository> repositories = readRepositories(child(root, "profiles"), activeProfiles);
            ProxyConfiguration proxy = readProxy(child(root, "proxies"));
            return new MavenSettings(local, repositories, servers, mirrors, proxy, file);
        } catch (FlydbException e) {
            throw e;
        } catch (Exception e) {
            throw new FlydbException(ErrorCode.MISSING_REQUIRED_CONFIG,
                    "读取 Maven settings 失败: " + file + ": " + e.getMessage(), e);
        }
    }

    private static MavenSettings empty(Path source) {
        return new MavenSettings(null, Collections.<Repository>emptyList(),
                Collections.<String, Credentials>emptyMap(), Collections.<Mirror>emptyList(),
                null, source);
    }

    Path localRepository() { return localRepository; }
    Path source() { return source; }

    List<Repository> effectiveRepositories() {
        List<Repository> declared = new ArrayList<Repository>(repositories);
        declared.add(new Repository("central", "https://repo.maven.apache.org/maven2"));
        Map<String, Repository> effective = new LinkedHashMap<String, Repository>();
        for (Repository repository : declared) {
            Mirror mirror = mirrorFor(repository);
            Repository selected = mirror == null ? repository
                    : new Repository(mirror.id, mirror.url);
            Credentials credentials = servers.get(selected.id);
            selected = selected.with(credentials, proxy);
            effective.put(selected.id + "|" + selected.url, selected);
        }
        return new ArrayList<Repository>(effective.values());
    }

    private Mirror mirrorFor(Repository repository) {
        for (Mirror mirror : mirrors) {
            if (mirror.matches(repository)) return mirror;
        }
        return null;
    }

    private static Map<String, Credentials> readServers(Element servers) {
        Map<String, Credentials> result = new LinkedHashMap<String, Credentials>();
        for (Element server : children(servers, "server")) {
            String id = text(server, "id");
            if (id != null) result.put(id, new Credentials(
                    interpolate(text(server, "username")), interpolate(text(server, "password"))));
        }
        return result;
    }

    private static List<Mirror> readMirrors(Element mirrors) {
        List<Mirror> result = new ArrayList<Mirror>();
        for (Element mirror : children(mirrors, "mirror")) {
            String id = text(mirror, "id");
            String url = text(mirror, "url");
            String mirrorOf = text(mirror, "mirrorOf");
            if (id != null && url != null && mirrorOf != null) {
                result.add(new Mirror(id, interpolate(url), mirrorOf));
            }
        }
        return result;
    }

    private static List<Repository> readRepositories(Element profiles, Set<String> active) {
        List<Repository> result = new ArrayList<Repository>();
        for (Element profile : children(profiles, "profile")) {
            String id = text(profile, "id");
            Element activation = child(profile, "activation");
            boolean activeByDefault = activation != null
                    && "true".equalsIgnoreCase(text(activation, "activeByDefault"));
            if (!active.contains(id) && !activeByDefault) continue;
            for (Element repository : children(child(profile, "repositories"), "repository")) {
                String repositoryId = text(repository, "id");
                String url = text(repository, "url");
                if (repositoryId != null && url != null) {
                    result.add(new Repository(repositoryId, interpolate(url)));
                }
            }
        }
        return result;
    }

    private static ProxyConfiguration readProxy(Element proxies) {
        for (Element proxy : children(proxies, "proxy")) {
            if ("false".equalsIgnoreCase(text(proxy, "active"))) continue;
            String host = text(proxy, "host");
            if (host == null) continue;
            int port = integer(text(proxy, "port"), 8080);
            return new ProxyConfiguration(text(proxy, "protocol"), host, port,
                    interpolate(text(proxy, "username")), interpolate(text(proxy, "password")),
                    text(proxy, "nonProxyHosts"));
        }
        return null;
    }

    private static Set<String> readValues(Element parent, String name) {
        Set<String> values = new LinkedHashSet<String>();
        for (Element element : children(parent, name)) {
            String value = trim(element.getTextContent());
            if (value != null) values.add(value);
        }
        return values;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && name.equals(localName(node))) return (Element) node;
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        if (parent == null) return Collections.emptyList();
        List<Element> result = new ArrayList<Element>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element && name.equals(localName(node))) result.add((Element) node);
        }
        return result;
    }

    private static String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static String text(Element parent, String name) {
        Element element = child(parent, name);
        return element == null ? null : trim(element.getTextContent());
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Path path(String value) {
        return value == null ? null : Paths.get(interpolate(value)).toAbsolutePath().normalize();
    }

    private static String interpolate(String value) {
        if (value == null) return null;
        String result = value.replace("${user.home}", System.getProperty("user.home"));
        int start;
        while ((start = result.indexOf("${env.")) >= 0) {
            int end = result.indexOf('}', start);
            if (end < 0) break;
            String name = result.substring(start + 6, end);
            String replacement = System.getenv(name);
            if (replacement == null) break;
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result;
    }

    private static int integer(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException e) { return fallback; }
    }

    static final class Repository {
        final String id;
        final String url;
        final Credentials credentials;
        final ProxyConfiguration proxy;

        Repository(String id, String url) { this(id, url, null, null); }
        Repository(String id, String url, Credentials credentials, ProxyConfiguration proxy) {
            this.id = id;
            this.url = url.replaceFirst("/+$", "");
            this.credentials = credentials;
            this.proxy = proxy;
        }
        Repository with(Credentials credentials, ProxyConfiguration proxy) {
            return new Repository(id, url, credentials, proxy);
        }
        String artifactUrl(String path) { return url + "/" + path; }
    }

    private static final class Mirror {
        final String id;
        final String url;
        final String mirrorOf;
        Mirror(String id, String url, String mirrorOf) {
            this.id = id; this.url = url; this.mirrorOf = mirrorOf;
        }
        boolean matches(Repository repository) {
            boolean included = false;
            for (String token : mirrorOf.split(",")) {
                token = token.trim();
                if (("!" + repository.id).equals(token)) return false;
                if (repository.id.equals(token) || "*".equals(token)
                        || ("external:*".equals(token) && !repository.url.startsWith("file:"))) {
                    included = true;
                }
            }
            return included;
        }
    }

    static final class Credentials {
        final String username;
        final String password;
        Credentials(String username, String password) {
            this.username = username; this.password = password;
        }
    }

    static final class ProxyConfiguration {
        final String protocol;
        final String host;
        final int port;
        final Credentials credentials;
        final String nonProxyHosts;
        ProxyConfiguration(String protocol, String host, int port, String username,
                           String password, String nonProxyHosts) {
            this.protocol = protocol == null ? "http" : protocol;
            this.host = host; this.port = port;
            this.credentials = new Credentials(username, password);
            this.nonProxyHosts = nonProxyHosts;
        }
        Proxy proxyFor(String targetHost) {
            if (targetHost != null && nonProxyHosts != null) {
                for (String pattern : nonProxyHosts.split("\\|")) {
                    String regex = pattern.replace(".", "\\.").replace("*", ".*");
                    if (targetHost.matches(regex)) return Proxy.NO_PROXY;
                }
            }
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
        }
    }
}
