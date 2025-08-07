package net.mysterria.translator.storage.impl;

import net.mysterria.translator.storage.PlayerLangStorage;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.*;
import java.util.*;

import static net.mysterria.translator.MysterriaTranslator.plugin;

public class MySQLPlayerLangStorage implements PlayerLangStorage {
    private final Connection connection;

    public MySQLPlayerLangStorage(String host, int port, String database, String user, String pass) throws SQLException {
        ConfigurationSection propsSection = plugin.getConfig().getConfigurationSection("storage.mysql.properties");
        StringBuilder props = new StringBuilder();
        if (propsSection != null) {
            for (String key : propsSection.getKeys(false)) {
                props.append("&").append(key).append("=").append(propsSection.get(key));
            }
        }
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + props.substring(1);
        this.connection = DriverManager.getConnection(url, user, pass);
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS player_langs (uuid VARCHAR(36) PRIMARY KEY, lang VARCHAR(16))");
        }
    }

    @Override
    public void savePlayerLang(UUID uuid, String lang) {
        try (PreparedStatement ps = connection.prepareStatement(
                "REPLACE INTO player_langs (uuid, lang) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, lang);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    @Override
    public String getPlayerLang(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT lang FROM player_langs WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("lang");
        } catch (SQLException ignored) {}
        return null;
    }

    @Override
    public boolean hasPlayerLang(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM player_langs WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException ignored) {}
        return false;
    }

    @Override
    public Map<UUID, String> loadAll() {
        Map<UUID, String> map = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, lang FROM player_langs")) {
            while (rs.next()) {
                map.put(UUID.fromString(rs.getString("uuid")), rs.getString("lang"));
            }
        } catch (SQLException ignored) {}
        return map;
    }

    @Override
    public void removePlayerLang(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM player_langs WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }
}
