# 🌍 MysterriaTranslator

> **A comprehensive multilingual plugin for Minecraft servers**  
> Seamlessly manage translations with advanced caching, multiple storage backends, and PlaceholderAPI support.

## 🚀 Key Highlights

**🔥 Performance First**  
High-speed LRU caching system ensures translations are delivered instantly without compromising server performance.

**📦 Multiple Storage Options**  
Choose from YAML files, SQLite, or MySQL based on your server's needs - with automatic migration support.

**🎯 Developer Friendly**  
Full PlaceholderAPI integration and customizable actions make it perfect for other plugins to utilize.

**⚙️ Smart Configuration**  
Intuitive setup through `messageConfig.yml` with sensible defaults and extensive customization options.

---

## 📋 Feature Overview

| Feature | Description |
|---------|-------------|
| **Multi-language Support** | YAML-based language files for easy translation management |
| **LRU Translation Cache** | Configurable cache size for optimal memory usage |
| **Storage Flexibility** | Support for YAML, SQLite, and MySQL backends |
| **PlaceholderAPI** | Seamless integration with placeholder expansions |
| **Custom Actions** | Configurable join and language-set actions |
| **Auto Migration** | Smooth transition from YAML to database storage |

---

## ⚡ Quick Setup

### Configuration (`messageConfig.yml`)

```yaml
# Core Settings
defaultLang: "pt_br"              # Fallback language
translationCacheSize: 500         # Cache entry limit

# Storage Backend
storage: "yaml"                   # Options: yaml, sqlite, mysql
```

---

## 🔧 Placeholder Reference

| Placeholder | Function |
|-------------|----------|
| `%lang_(path)%` | Retrieve translation for current player |
| `%lang_player%` | Get player's active language |
| `%lang_player_(nick)%` | Get specific player's language |

---

## 📖 Commands & Permissions

**Primary Command:** `/lang`  
**Admin Permission:** `mtranslator.admin`

---

## 💾 Storage Systems

### YAML Storage
- **Best for:** Small servers, simple setups
- **Benefits:** Human-readable, version-control friendly

### SQLite Storage  
- **Best for:** Medium servers, no database setup
- **Benefits:** Local database, zero configuration

### MySQL Storage
- **Best for:** Large servers, shared hosting
- **Benefits:** Remote access, advanced features, custom connection properties

> **Note:** Automatic migration handles the transition between storage types seamlessly.

---

## ⚡ Performance Architecture

- **Startup:** All translations loaded into memory for instant access
- **Runtime:** LRU cache minimizes memory footprint while maintaining speed  
- **Database:** Minimal queries (login/language changes only)
- **Scalability:** Designed to handle high-traffic servers efficiently

---

## 🙏 Acknowledgments

**Developed by:** [Mysterria](https://github.com/Mysterria)

**Special Thanks:** This project draws inspiration from [IceGames23/IGLanguages](https://github.com/IceGames23/IGLanguages) - thank you for the foundation and ideas that helped shape this plugin.

---

<div align="center">

**Built with ❤️ for the Minecraft community**

[Report Issues](../../issues) • [Contribute](../../pulls) • [Wiki](../../wiki)

</div>
