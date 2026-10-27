# CLAUDE.md

MysterriaTranslator is a Paper 26.3 plugin (Java 25, Gradle Kotlin DSL, Lombok) that serves static translations through PlaceholderAPI (`%lang_<key>%`) and translates player chat in real time through a chain of engines (Ollama, LibreTranslate, Gemini, Google, OpenAI).

## Build

```bash
./gradlew build        # shaded jar in build/libs/
./gradlew runServer    # local 26.3 test server in run/
```

## Layout

```
MysterriaTranslator.java            wiring, validated hot reload (/lang reload), shutdown
command/player/LangCommand          /lang (Paper BasicCommand): status panel, toggle, chat, ui; admin set/get/list/reload/engines
config/TranslatorConfig, ConfigReader   config.yml -> typed models (config/models), collects errors instead of silently defaulting
config/Messages                     messages.yml with jar defaults
domain/language/                    static langs/<code>/*.yml, pre-rendered when placeholder-free; PAPI expansion
domain/preference/                  per-player interface language, pinned chat language, translation on/off, hint flag
                                    storage: YamlPreferenceStorage or SqlPreferenceStorage (sqlite/mysql), async single writer
domain/translation/                 TranslationService (cache, per-sender rate limit, fan-out per target language)
                                    EngineChain (retry, fallback, 429 suspension, admin notices), TranslationThreadPool
                                    type/TranslationEngine + one class per engine, sharing one HttpClient
domain/chat/                        ChatTranslationService (detect once, split audience, deliver grouped by language)
                                    listener/ ZelChat (preferred) > ChatControl > Paper AsyncChatEvent
util/                               text (legacy/hex -> MiniMessage), language detection, identity (disguise/possession), throwables
```

## Rules worth knowing

- Player-typed text is always inserted as a component (`Tag.inserting`), never substituted into a MiniMessage format string.
- Rate limiting is per sender and only counts messages that miss the cache; the sender gets an action-bar notice.
- Chat target = pinned `/lang chat <code>` or the client locale. Detection is script-based (Cyrillic -> uk, Latin -> en).
- Engine status and notices go to `mtranslator.admin` only.
- config.yml keys keep their v2 names so existing server configs keep working; missing keys fall back to the jar defaults.
