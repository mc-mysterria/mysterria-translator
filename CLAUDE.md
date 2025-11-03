# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MysterriaTranslator is a Minecraft plugin (Paper/Spigot) that provides comprehensive multilingual support with real-time chat translation. It integrates with PlaceholderAPI and supports multiple translation engines (Ollama, LibreTranslate, Gemini) with automatic fallback.

**Language:** Java 21
**Build System:** Gradle (Kotlin DSL)
**Framework:** Paper API 1.21.8

## Build Commands

```bash
# Build the plugin JAR
./gradlew build

# Build without tests (faster)
./gradlew build -x test

# Clean build directory
./gradlew clean

# Compile only (no JAR packaging)
./gradlew compileJava
```

The compiled plugin JAR will be located in `build/libs/`.

## Architecture Overview

### Core Components

1. **MysterriaTranslator** (Main plugin class)
   - Initializes all managers, storage, and translation clients
   - Handles plugin lifecycle (enable/disable/reload)
   - Entry point: `MysterriaTranslator.java:46` (onEnable)

2. **LangManager** (`manager/LangManager.java`)
   - Manages static translations from YAML language files
   - Handles player language preferences
   - Implements LRU cache for translation lookups
   - Loads translations from `langs/<lang_code>/*.yml` files at startup

3. **TranslationManager** (`translation/TranslationManager.java`)
   - Handles dynamic real-time chat message translation
   - Multi-provider system with automatic fallback (Ollama → LibreTranslate → Gemini)
   - Rate limiting and caching for translation requests
   - Batch translation optimization for multiple players

4. **Storage Layer** (`storage/`)
   - Abstract interface: `PlayerLangStorage`
   - Implementations: YAML, SQLite, MySQL
   - Automatic migration from YAML to database storage
   - Stores player language preferences persistently

### Translation Flow

**Static Translations** (UI elements, messages):
1. Player requests translation via PlaceholderAPI (`%lang_<key>%`)
2. LangManager looks up key in player's language
3. Falls back to default language if key not found
4. Returns cached result if available

**Dynamic Chat Translation**:
1. Player sends chat message
2. ChatControlListener/ChatListener intercepts message
3. TranslationManager detects source language
4. Batch translates message for all recipients who need it
5. Translation providers tried in order with retry logic
6. Results cached for 30 seconds (configurable)
7. Rate limiting prevents spam (2 messages per 10 seconds default)

### Translation Engines

The plugin supports three translation providers configured in `config.yml`:

- **Ollama**: Local AI models (e.g., gemma2:2b, qwen2.5:3b) - Fast, private, no external API needed
- **LibreTranslate**: Open-source translation API - Good for offline/self-hosted setups
- **Gemini**: Google's AI translation - High quality, auto language detection

Providers can be chained (e.g., `"gemini, ollama"`) for automatic fallback.

## Project Structure

```
src/main/java/net/mysterria/translator/
├── MysterriaTranslator.java          # Main plugin class
├── manager/
│   └── LangManager.java              # Static translation management
├── translation/
│   ├── TranslationManager.java       # Dynamic translation orchestration
│   └── TranslationResult.java        # Translation response wrapper
├── storage/
│   ├── PlayerLangStorage.java        # Storage interface
│   └── impl/                         # YAML, SQLite, MySQL implementations
├── engine/
│   ├── ollama/OllamaClient.java      # Ollama API client
│   ├── libretranslate/LibreTranslateClient.java
│   └── gemini/GeminiClient.java      # Google Gemini API client
├── listener/
│   ├── ChatControlListener.java      # ChatControl integration (preferred)
│   ├── ChatListener.java             # Bukkit events fallback
│   └── PlayerJoinListener.java       # Player join/language setup
├── command/
│   └── LangCommand.java              # /lang command handler
├── placeholder/
│   └── LangExpansion.java            # PlaceholderAPI integration
└── util/
    ├── MessageSerializer.java        # Adventure Component serialization
    └── LanguageDetector.java         # Language detection utilities

src/main/resources/
├── config.yml                        # Main plugin configuration
├── messages.yml                      # System messages
├── plugin.yml                        # Plugin metadata
└── langs/                            # Translation files
    ├── en_us/
    │   └── example.yml
    └── uk_ua/
        └── example.yml
```

## Configuration System

- **config.yml**: Translation engine settings, storage config, rate limiting, cache settings
- **messages.yml**: Plugin system messages
- **langs/<lang_code>/*.yml**: Static translations organized by language

Language codes must match `storage/model/LangEnum.java` valid codes.

## Key Design Patterns

1. **Multi-provider Fallback**: TranslationManager tries providers in order (line 241-276), automatically falling back on failure with cooldown-based notifications to avoid spam.

2. **Batch Translation Optimization**: `translateForMultiplePlayers()` (line 136-226) groups players by target language to minimize API calls.

3. **LRU Caching**: Both managers use LinkedHashMap-based LRU caches to balance memory usage with performance.

4. **Rate Limiting**: Per-player rate limits (PlayerRateLimit class) with sliding window implementation prevent abuse.

5. **Async Execution**: All translation API calls use CompletableFuture for non-blocking operations.

## Integration Points

- **PlaceholderAPI** (Required): Provides `%lang_*%` placeholders for other plugins
- **ChatControl** (Optional): Preferred for chat event handling; falls back to Bukkit AsyncPlayerChatEvent
- **LuckPerms** (Optional): Used in display formats for prefix/suffix placeholders

## Important Implementation Notes

- All translations are loaded into memory at startup for performance
- Database queries only occur on player login/language changes
- Translation cache expires after 30 seconds (configurable)
- Provider fallback notifications have 15-minute cooldown to prevent log spam
- Chat messages under 3 characters (configurable) are not translated
- Language detection is bypassed when using Gemini (uses auto-detection)

## Dependencies

Located in `build.gradle.kts`:
- Paper API 1.21.8 (compileOnly)
- PlaceholderAPI 2.11.6 (compileOnly)
- ChatControl 11.5.3 (compileOnly, local JAR in libs/)
