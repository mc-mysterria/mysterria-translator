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
