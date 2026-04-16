package net.mysterria.translator.storage.impl;

import net.mysterria.translator.storage.PlayerLangStorage;

import java.sql.*;
import java.util.*;

public class SQLitePlayerLangStorage implements PlayerLangStorage {
    private final Connection connection;

    public SQLitePlayerLangStorage(String dbPath) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS player_langs (uuid TEXT PRIMARY KEY, lang TEXT)");
            try {
                st.executeUpdate("ALTER TABLE player_langs ADD COLUMN enabled INTEGER DEFAULT 1");
            } catch (SQLException ignored) {} 
        }
    }

    @Override
    public void savePlayerLang(UUID uuid, String lang) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_langs (uuid, lang) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET lang = EXCLUDED.lang")) {
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

    @Override
    public void setTranslationEnabled(UUID uuid, boolean enabled) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_langs (uuid, enabled) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET enabled = EXCLUDED.enabled")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, enabled ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    @Override
    public boolean isTranslationEnabled(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT enabled FROM player_langs WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("enabled") == 1;
        } catch (SQLException ignored) {}
        return true;
    }

    @Override
    public Map<UUID, Boolean> loadAllEnabledStatus() {
        Map<UUID, Boolean> map = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, enabled FROM player_langs")) {
            while (rs.next()) {
                map.put(UUID.fromString(rs.getString("uuid")), rs.getInt("enabled") == 1);
            }
        } catch (SQLException ignored) {}
        return map;
    }
}
