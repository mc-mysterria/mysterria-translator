package net.mysterria.translator.domain.preference.type;

import net.mysterria.translator.domain.preference.model.LanguagePreference;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SqlPreferenceStorage implements PreferenceStorage {

    private static final String SELECT_ALL = "SELECT uuid, lang, enabled, chat_lang, hint_shown FROM player_langs";
    private static final String MYSQL_UPSERT = "INSERT INTO player_langs (uuid, lang, enabled, chat_lang, hint_shown) VALUES (?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE lang = VALUES(lang), enabled = VALUES(enabled), chat_lang = VALUES(chat_lang), hint_shown = VALUES(hint_shown)";
    private static final String SQLITE_UPSERT = "INSERT INTO player_langs (uuid, lang, enabled, chat_lang, hint_shown) VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT(uuid) DO UPDATE SET lang = excluded.lang, enabled = excluded.enabled, chat_lang = excluded.chat_lang, hint_shown = excluded.hint_shown";

    private final String url;
    private final String user;
    private final String password;
    private final boolean mysql;
    private final Logger logger;
    private Connection connection;

    public SqlPreferenceStorage(String url, String user, String password, boolean mysql, Logger logger) throws SQLException {
        this.url = url;
        this.user = user;
        this.password = password;
        this.mysql = mysql;
        this.logger = logger;
        this.connection = connect();
        createSchema();
    }

    @Override
    public synchronized Map<UUID, LanguagePreference> loadAll() {
        Map<UUID, LanguagePreference> preferences = new HashMap<>();
        try (Statement statement = connection().createStatement(); ResultSet rows = statement.executeQuery(SELECT_ALL)) {
            while (rows.next()) {
                preferences.put(UUID.fromString(rows.getString("uuid")), new LanguagePreference(
                        rows.getString("lang"),
                        rows.getString("chat_lang"),
                        rows.getInt("enabled") != 0,
                        rows.getInt("hint_shown") != 0));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load language preferences", e);
        }
        return preferences;
    }

    @Override
    public synchronized void save(UUID uuid, LanguagePreference preference) {
        try (PreparedStatement statement = connection().prepareStatement(mysql ? MYSQL_UPSERT : SQLITE_UPSERT)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, preference.getLanguage());
            statement.setInt(3, preference.isTranslationsEnabled() ? 1 : 0);
            statement.setString(4, preference.getChatLanguage());
            statement.setInt(5, preference.isHintShown() ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException e) {
            logger.error("Could not save language preference of {}", uuid, e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            logger.warn("Could not close the preference database cleanly", e);
        }
    }

    private Connection connect() throws SQLException {
        return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
    }

    private Connection connection() throws SQLException {
        if (!connection.isValid(2)) {
            connection.close();
            connection = connect();
        }
        return connection;
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(mysql
                    ? "CREATE TABLE IF NOT EXISTS player_langs (uuid VARCHAR(36) PRIMARY KEY, lang VARCHAR(16))"
                    : "CREATE TABLE IF NOT EXISTS player_langs (uuid TEXT PRIMARY KEY, lang TEXT)");
            addColumnIfMissing(statement, "enabled", mysql ? "TINYINT(1) DEFAULT 1" : "INTEGER DEFAULT 1");
            addColumnIfMissing(statement, "chat_lang", mysql ? "VARCHAR(16)" : "TEXT");
            addColumnIfMissing(statement, "hint_shown", mysql ? "TINYINT(1) DEFAULT 0" : "INTEGER DEFAULT 0");
        }
    }

    private void addColumnIfMissing(Statement statement, String column, String definition) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "player_langs", column)) {
            if (columns.next()) {
                return;
            }
        }
        statement.executeUpdate("ALTER TABLE player_langs ADD COLUMN " + column + " " + definition);
    }
}
