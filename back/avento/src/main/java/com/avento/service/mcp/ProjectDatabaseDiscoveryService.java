package com.avento.service.mcp;

import com.avento.service.dto.*;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Service
public class ProjectDatabaseDiscoveryService {

    static final String PROJECT_DSN_ENV = "AVENTO_PROJECT_DATABASE_DSN";

    private static final Logger logger = LoggerFactory.getLogger(ProjectDatabaseDiscoveryService.class);
    private static final long MAX_CONFIG_BYTES = 2 * 1024 * 1024;
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");
    private static final List<String> DOTENV_FILES =
            List.of(".env", ".env.local", ".env.development", ".env.development.local");
    private static final List<String> COMPOSE_FILES =
            List.of("compose.yml", "compose.yaml", "docker-compose.yml", "docker-compose.yaml");
    private static final List<String> SQLITE_NAMES =
            List.of("dev.db", "database.db", "app.db", "data.db", "db.sqlite3", "database.sqlite");
    private static final List<String> SQLITE_DIRECTORIES = List.of("", "data", "db", "prisma", "storage");

    private final Path configDirectory;

    public ProjectDatabaseDiscoveryService() {
        this(Path.of(System.getProperty("user.home"), ".avento", "dbhub"));
    }

    ProjectDatabaseDiscoveryService(Path configDirectory) {
        this.configDirectory = configDirectory;
    }

    public Optional<DatabaseConfiguration> discover(List<String> workspaceRoots) {
        for (String workspaceRoot : workspaceRoots == null ? List.<String>of() : workspaceRoots) {
            try {
                Path root = Path.of(workspaceRoot).toRealPath();
                if (!Files.isDirectory(root)) {
                    continue;
                }
                Optional<DatabaseConfiguration> discovered = discover(root);
                if (discovered.isPresent()) {
                    return discovered;
                }
            } catch (IOException | RuntimeException exception) {
                logger.debug("Nao foi possivel analisar configuracao de banco em {}", workspaceRoot, exception);
            }
        }
        return Optional.empty();
    }

    public Optional<DatabaseConfiguration> fromGlobalDsn(String dsn) {
        String normalized = normalizeNetworkDsn(dsn, "", "");
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return createGeneratedConfiguration("global", "AVENTO_MCP_DBHUB_DSN", normalized);
    }

    private Optional<DatabaseConfiguration> discover(Path root) {
        Optional<DatabaseConfiguration> explicit = explicitConfig(root);
        if (explicit.isPresent()) {
            return explicit;
        }

        Map<String, String> environment = loadDotenv(root);
        Optional<DetectedDsn> detected = fromCompose(root, environment)
                .or(() -> fromDirectEnvironment(root, environment))
                .or(() -> fromSpringConfiguration(root, environment))
                .or(() -> fromEnvironmentParts(environment))
                .or(() -> fromSqliteFile(root));
        return detected.flatMap(value -> createGeneratedConfiguration(root.toString(), value.source(), value.dsn()));
    }

    private Optional<DatabaseConfiguration> explicitConfig(Path root) {
        for (Path candidate : List.of(root.resolve("dbhub.toml"), root.resolve(".avento/dbhub.toml"))) {
            try {
                if (!isReadableConfig(candidate)) {
                    continue;
                }
                Path real = candidate.toRealPath();
                if (real.startsWith(root)) {
                    return Optional.of(new DatabaseConfiguration(
                            real, Map.of(), root.relativize(real).toString()));
                }
            } catch (IOException exception) {
                logger.debug("DBHub config ignorado em {}", candidate, exception);
            }
        }
        return Optional.empty();
    }

    private Optional<DetectedDsn> fromCompose(Path root, Map<String, String> projectEnvironment) {
        for (String filename : COMPOSE_FILES) {
            Path composeFile = root.resolve(filename);
            if (!isReadableConfig(composeFile)) {
                continue;
            }
            try (Reader reader = Files.newBufferedReader(composeFile, StandardCharsets.UTF_8)) {
                Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
                Map<String, Object> services = asMap(asMap(document).get("services"));
                for (Map.Entry<String, Object> serviceEntry : services.entrySet()) {
                    Map<String, Object> service = asMap(serviceEntry.getValue());
                    DatabaseType type = databaseType(stringValue(service.get("image")), service);
                    if (type == null) {
                        continue;
                    }
                    Optional<Integer> publishedPort =
                            publishedPort(service.get("ports"), type.port(), projectEnvironment);
                    if (publishedPort.isEmpty()) {
                        continue;
                    }
                    Map<String, String> serviceEnvironment = new LinkedHashMap<>(projectEnvironment);
                    serviceEnvironment.putAll(environmentMap(service.get("environment"), projectEnvironment));
                    serviceEnvironment.put(type.hostKeys().getFirst(), "127.0.0.1");
                    String dsn = buildDsn(type, serviceEnvironment, publishedPort.get());
                    if (!dsn.isBlank()) {
                        return Optional.of(new DetectedDsn(dsn, filename + " (servico " + serviceEntry.getKey() + ")"));
                    }
                }
            } catch (IOException | RuntimeException exception) {
                logger.debug("Compose ignorado durante descoberta de banco: {}", composeFile, exception);
            }
        }
        return Optional.empty();
    }

    private Optional<DetectedDsn> fromDirectEnvironment(Path root, Map<String, String> environment) {
        for (String key : List.of("AVENTO_DBHUB_DSN", "DATABASE_URL", "DB_URL", "DATABASE_DSN")) {
            String value = interpolate(environment.getOrDefault(key, ""), environment);
            String normalized = normalizeDsn(root, value, "", "");
            if (!normalized.isBlank()) {
                return Optional.of(new DetectedDsn(normalized, key));
            }
        }
        return Optional.empty();
    }

    private Optional<DetectedDsn> fromSpringConfiguration(Path root, Map<String, String> environment) {
        List<Path> propertyFiles = List.of(
                root.resolve("src/main/resources/application.properties"), root.resolve("application.properties"));
        for (Path file : propertyFiles) {
            if (!isReadableConfig(file)) {
                continue;
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
                String url = interpolate(properties.getProperty("spring.datasource.url", ""), environment);
                String username = interpolate(properties.getProperty("spring.datasource.username", ""), environment);
                String password = interpolate(properties.getProperty("spring.datasource.password", ""), environment);
                String normalized = normalizeDsn(root, url, username, password);
                if (!normalized.isBlank()) {
                    return Optional.of(
                            new DetectedDsn(normalized, root.relativize(file).toString()));
                }
            } catch (IOException exception) {
                logger.debug("Properties ignorado durante descoberta de banco: {}", file, exception);
            }
        }

        List<Path> yamlFiles = List.of(
                root.resolve("src/main/resources/application.yml"),
                root.resolve("src/main/resources/application.yaml"),
                root.resolve("application.yml"),
                root.resolve("application.yaml"));
        for (Path file : yamlFiles) {
            if (!isReadableConfig(file)) {
                continue;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
                Map<String, String> flattened = new LinkedHashMap<>();
                flatten("", asMap(document), flattened);
                String url = interpolate(flattened.getOrDefault("spring.datasource.url", ""), environment);
                String username = interpolate(flattened.getOrDefault("spring.datasource.username", ""), environment);
                String password = interpolate(flattened.getOrDefault("spring.datasource.password", ""), environment);
                String normalized = normalizeDsn(root, url, username, password);
                if (!normalized.isBlank()) {
                    return Optional.of(
                            new DetectedDsn(normalized, root.relativize(file).toString()));
                }
            } catch (IOException | RuntimeException exception) {
                logger.debug("YAML ignorado durante descoberta de banco: {}", file, exception);
            }
        }
        return Optional.empty();
    }

    private Optional<DetectedDsn> fromEnvironmentParts(Map<String, String> environment) {
        DatabaseType type = typeFromEnvironment(environment);
        if (type == null) {
            return Optional.empty();
        }
        String dsn = buildDsn(type, environment, integerValue(first(environment, type.portKeys()), type.port()));
        return dsn.isBlank() ? Optional.empty() : Optional.of(new DetectedDsn(dsn, ".env"));
    }

    private Optional<DetectedDsn> fromSqliteFile(Path root) {
        for (String directory : SQLITE_DIRECTORIES) {
            for (String filename : SQLITE_NAMES) {
                Path candidate = directory.isBlank()
                        ? root.resolve(filename)
                        : root.resolve(directory).resolve(filename);
                try {
                    if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                            && candidate.toRealPath().startsWith(root)) {
                        return Optional.of(new DetectedDsn(
                                sqliteDsn(candidate.toRealPath()),
                                root.relativize(candidate).toString()));
                    }
                } catch (IOException exception) {
                    logger.debug("SQLite candidato ignorado: {}", candidate, exception);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<DatabaseConfiguration> createGeneratedConfiguration(String scope, String source, String dsn) {
        try {
            Path directory = configDirectory;
            Files.createDirectories(directory);
            setPermissions(
                    directory,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));

            Path configFile = directory.resolve(hash(scope) + ".toml");
            String content = "[[sources]]\n"
                    + "id = \"project\"\n"
                    + "description = \"Banco do workspace ativo (" + escapeToml(source) + ")\"\n"
                    + "dsn = \"${" + PROJECT_DSN_ENV + "}\"\n"
                    + "connection_timeout = 15\n"
                    + "query_timeout = 30\n\n"
                    + "[[tools]]\n"
                    + "name = \"execute_sql\"\n"
                    + "source = \"project\"\n"
                    + "readonly = false\n"
                    + "max_rows = 1000\n";
            if (!Files.exists(configFile)
                    || !Files.readString(configFile, StandardCharsets.UTF_8).equals(content)) {
                Files.writeString(
                        configFile,
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            }
            setPermissions(configFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return Optional.of(new DatabaseConfiguration(configFile, Map.of(PROJECT_DSN_ENV, dsn), source));
        } catch (IOException | RuntimeException exception) {
            logger.warn("Nao foi possivel preparar a configuracao local do DBHub", exception);
            return Optional.empty();
        }
    }

    private Map<String, String> loadDotenv(Path root) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String filename : DOTENV_FILES) {
            Path file = root.resolve(filename);
            if (!isReadableConfig(file)) {
                continue;
            }
            try {
                for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String line = rawLine.trim();
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    if (line.startsWith("export ")) {
                        line = line.substring("export ".length()).trim();
                    }
                    int separator = line.indexOf('=');
                    if (separator <= 0) {
                        continue;
                    }
                    String key = line.substring(0, separator).trim();
                    if (!key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        continue;
                    }
                    String value = stripQuotes(line.substring(separator + 1).trim());
                    values.put(key, interpolate(value, values));
                }
            } catch (IOException exception) {
                logger.debug("Dotenv ignorado durante descoberta de banco: {}", file, exception);
            }
        }
        values.replaceAll((key, value) -> interpolate(value, values));
        return values;
    }

    private String normalizeDsn(Path root, String raw, String username, String password) {
        if (raw == null || raw.isBlank() || raw.contains("${")) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("jdbc:sqlite:")) {
            return normalizeSqlite(root, value.substring("jdbc:sqlite:".length()));
        }
        if (value.startsWith("sqlite:")) {
            return normalizeSqlite(root, value.substring("sqlite:".length()));
        }
        if (value.startsWith("file:")) {
            return normalizeSqlite(root, value.substring("file:".length()));
        }
        return normalizeNetworkDsn(value, username, password);
    }

    private String normalizeNetworkDsn(String raw, String username, String password) {
        if (raw == null || raw.isBlank() || raw.contains("${")) {
            return "";
        }
        String value = raw.trim()
                .replaceFirst("^jdbc:postgresql:", "postgres:")
                .replaceFirst("^jdbc:mysql:", "mysql:")
                .replaceFirst("^jdbc:mariadb:", "mariadb:")
                .replaceFirst("^postgresql:", "postgres:");
        if (!value.matches("^(postgres|mysql|mariadb|sqlserver)://.+")) {
            return "";
        }
        if (username.isBlank()) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null) {
                return value;
            }
            String userInfo = password.isBlank() ? username : username + ":" + password;
            return new URI(
                            uri.getScheme(),
                            userInfo,
                            uri.getHost(),
                            uri.getPort(),
                            uri.getPath(),
                            uri.getQuery(),
                            uri.getFragment())
                    .toASCIIString();
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return "";
        }
    }

    private String normalizeSqlite(Path root, String rawPath) {
        String value = rawPath.trim();
        while (value.startsWith("//")) {
            value = value.substring(1);
        }
        Path path = Path.of(value);
        Path resolved =
                path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return "";
        }
        return sqliteDsn(resolved.toAbsolutePath());
    }

    private String buildDsn(DatabaseType type, Map<String, String> environment, int port) {
        String user = "";
        String password = "";
        String database = "";
        switch (type) {
            case POSTGRES -> {
                user = defaultValue(first(environment, List.of("POSTGRES_USER", "DB_USERNAME", "DB_USER")), "postgres");
                password = first(environment, List.of("POSTGRES_PASSWORD", "DB_PASSWORD"));
                database = defaultValue(first(environment, List.of("POSTGRES_DB", "DB_NAME", "DATABASE_NAME")), user);
            }
            case MYSQL -> {
                user = defaultValue(first(environment, List.of("MYSQL_USER", "DB_USERNAME", "DB_USER")), "root");
                password = first(environment, List.of("MYSQL_PASSWORD", "MYSQL_ROOT_PASSWORD", "DB_PASSWORD"));
                database = defaultValue(
                        first(environment, List.of("MYSQL_DATABASE", "DB_NAME", "DATABASE_NAME")), "mysql");
            }
            case MARIADB -> {
                user = defaultValue(first(environment, List.of("MARIADB_USER", "MYSQL_USER", "DB_USERNAME")), "root");
                password = first(
                        environment,
                        List.of("MARIADB_PASSWORD", "MARIADB_ROOT_PASSWORD", "MYSQL_PASSWORD", "DB_PASSWORD"));
                database = defaultValue(
                        first(environment, List.of("MARIADB_DATABASE", "MYSQL_DATABASE", "DB_NAME")), "mysql");
            }
            case SQLSERVER -> {
                user = defaultValue(first(environment, List.of("MSSQL_USER", "DB_USERNAME", "DB_USER")), "sa");
                password = first(environment, List.of("MSSQL_SA_PASSWORD", "SA_PASSWORD", "DB_PASSWORD"));
                database = defaultValue(
                        first(environment, List.of("MSSQL_DATABASE", "DB_NAME", "DATABASE_NAME")), "master");
            }
        }
        String host = defaultValue(first(environment, type.hostKeys()), "127.0.0.1");
        if (host.equalsIgnoreCase("localhost")) {
            host = "127.0.0.1";
        }
        String credentials = encode(user) + (password.isBlank() ? "" : ":" + encode(password)) + "@";
        String ssl = type == DatabaseType.POSTGRES ? "?sslmode=disable" : "";
        return type.scheme() + "://" + credentials + host + ":" + port + "/" + encode(database) + ssl;
    }

    private DatabaseType databaseType(String image, Map<String, Object> service) {
        String normalized = image == null ? "" : image.toLowerCase(Locale.ROOT);
        if (normalized.contains("postgres") || normalized.contains("timescale")) {
            return DatabaseType.POSTGRES;
        }
        if (normalized.contains("mariadb")) {
            return DatabaseType.MARIADB;
        }
        if (normalized.contains("mysql")) {
            return DatabaseType.MYSQL;
        }
        if (normalized.contains("mssql") || normalized.contains("sqlserver")) {
            return DatabaseType.SQLSERVER;
        }
        Map<String, String> environment = environmentMap(service.get("environment"), Map.of());
        return typeFromEnvironment(environment);
    }

    private DatabaseType typeFromEnvironment(Map<String, String> environment) {
        String explicit =
                first(environment, List.of("DB_TYPE", "DATABASE_TYPE")).toLowerCase(Locale.ROOT);
        if (explicit.contains("postgres")) return DatabaseType.POSTGRES;
        if (explicit.contains("maria")) return DatabaseType.MARIADB;
        if (explicit.contains("mysql")) return DatabaseType.MYSQL;
        if (explicit.contains("mssql") || explicit.contains("sqlserver")) return DatabaseType.SQLSERVER;
        if (hasAny(environment, "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")) return DatabaseType.POSTGRES;
        if (hasAny(environment, "MARIADB_DATABASE", "MARIADB_USER", "MARIADB_PASSWORD")) return DatabaseType.MARIADB;
        if (hasAny(environment, "MYSQL_DATABASE", "MYSQL_USER", "MYSQL_ROOT_PASSWORD")) return DatabaseType.MYSQL;
        if (hasAny(environment, "MSSQL_SA_PASSWORD", "SA_PASSWORD")) return DatabaseType.SQLSERVER;
        return null;
    }

    private Optional<Integer> publishedPort(Object portsValue, int target, Map<String, String> environment) {
        if (!(portsValue instanceof List<?> ports)) {
            return Optional.empty();
        }
        for (Object port : ports) {
            if (port instanceof Map<?, ?> mapping) {
                int mappedTarget = integerValue(stringValue(mapping.get("target")), -1);
                int published = integerValue(interpolate(stringValue(mapping.get("published")), environment), -1);
                if (mappedTarget == target && published > 0) {
                    return Optional.of(published);
                }
                continue;
            }
            String value = interpolate(stringValue(port), environment).replaceFirst("/(tcp|udp)$", "");
            String[] parts = value.split(":");
            if (parts.length < 2) {
                continue;
            }
            int mappedTarget = integerValue(parts[parts.length - 1], -1);
            int published = integerValue(parts[parts.length - 2], -1);
            if (mappedTarget == target && published > 0) {
                return Optional.of(published);
            }
        }
        return Optional.empty();
    }

    private Map<String, String> environmentMap(Object value, Map<String, String> fallback) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entryValue) -> {
                String name = stringValue(key);
                String raw = entryValue == null ? fallback.getOrDefault(name, "") : stringValue(entryValue);
                result.put(name, interpolate(raw, fallback));
            });
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                String entry = stringValue(item);
                int separator = entry.indexOf('=');
                if (separator > 0) {
                    result.put(entry.substring(0, separator), interpolate(entry.substring(separator + 1), fallback));
                } else if (!entry.isBlank() && fallback.containsKey(entry)) {
                    result.put(entry, fallback.get(entry));
                }
            }
        }
        return result;
    }

    private void flatten(String prefix, Map<String, Object> source, Map<String, String> target) {
        source.forEach((key, value) -> {
            String path = prefix.isBlank() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?>) {
                flatten(path, asMap(value), target);
            } else if (value != null) {
                target.put(path, stringValue(value));
            }
        });
    }

    private String interpolate(String input, Map<String, String> environment) {
        String current = input == null ? "" : input;
        for (int pass = 0; pass < 5; pass++) {
            Matcher matcher = VARIABLE.matcher(current);
            StringBuffer resolved = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = environment.get(matcher.group(1));
                if ((replacement == null || replacement.isBlank()) && matcher.group(2) != null) {
                    replacement = matcher.group(2);
                }
                if (replacement == null) {
                    replacement = matcher.group();
                } else {
                    changed = true;
                }
                matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(resolved);
            current = resolved.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    private boolean isReadableConfig(Path path) {
        try {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && Files.isReadable(path)
                    && Files.size(path) <= MAX_CONFIG_BYTES;
        } catch (IOException exception) {
            return false;
        }
    }

    private void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Plataformas sem POSIX ainda recebem o arquivo sem credenciais embutidas.
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, entryValue) -> result.put(stringValue(key), entryValue));
        return result;
    }

    private String first(Map<String, String> values, List<String> keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean hasAny(Map<String, String> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && !values.getOrDefault(key, "").isBlank()) {
                return true;
            }
        }
        return false;
    }

    private int integerValue(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sqliteDsn(Path path) {
        return "sqlite://" + path.toUri().getPath();
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponivel", exception);
        }
    }

    private String escapeToml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private enum DatabaseType {
        POSTGRES("postgres", 5432, List.of("POSTGRES_HOST", "DB_HOST"), List.of("POSTGRES_PORT", "DB_PORT")),
        MYSQL("mysql", 3306, List.of("MYSQL_HOST", "DB_HOST"), List.of("MYSQL_PORT", "DB_PORT")),
        MARIADB(
                "mariadb",
                3306,
                List.of("MARIADB_HOST", "MYSQL_HOST", "DB_HOST"),
                List.of("MARIADB_PORT", "MYSQL_PORT", "DB_PORT")),
        SQLSERVER("sqlserver", 1433, List.of("MSSQL_HOST", "DB_HOST"), List.of("MSSQL_PORT", "DB_PORT"));

        private final String scheme;
        private final int port;
        private final List<String> hostKeys;
        private final List<String> portKeys;

        DatabaseType(String scheme, int port, List<String> hostKeys, List<String> portKeys) {
            this.scheme = scheme;
            this.port = port;
            this.hostKeys = hostKeys;
            this.portKeys = portKeys;
        }

        String scheme() {
            return scheme;
        }

        int port() {
            return port;
        }

        List<String> hostKeys() {
            return hostKeys;
        }

        List<String> portKeys() {
            return portKeys;
        }
    }
}
