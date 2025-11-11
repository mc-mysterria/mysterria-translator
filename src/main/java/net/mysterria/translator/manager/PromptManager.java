package net.mysterria.translator.manager;

import net.mysterria.translator.MysterriaTranslator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages customizable translation prompts for AI engines.
 * Loads prompts from prompts.yml and provides template variable substitution.
 */
public class PromptManager {
    private final MysterriaTranslator plugin;
    private FileConfiguration promptsConfig;
    private final File promptsFile;

    
    private static final Map<String, String> DEFAULT_PROMPTS = new HashMap<>();

    static {
        
        DEFAULT_PROMPTS.put("ollama.prompt",
                "You are a professional translator specializing in gaming terminology and casual Minecraft chat.\n" +
                "Translate the following {sourceLang} text to {targetLang} while following these rules strictly:\n\n" +
                "1. Provide a direct translation of the text ONLY — do NOT add, remove, or change any content.\n" +
                "2. Do NOT translate or modify placeholders or variables (like %player%, {player}, {item}, ${amount}, {0}, etc.).\n" +
                "3. Do NOT translate or modify command syntax (like /warp, /msg, /give).\n" +
                "4. Do NOT translate JSON keys or structure — only translate text values.\n" +
                "5. Preserve punctuation, spacing, capitalization, and emoji exactly as in the input.\n" +
                "6. Maintain the informal or gaming tone appropriate for in-game chat.\n" +
                "7. Do not include explanations, quotes, or additional text — return ONLY the translated content.\n\n" +
                "DON'T INCLUDE NOTES IN YOUR TEXT, JUST THE TRANSLATED CONTENT.\n" +
                "DON'T INCLUDE EXPLANATIONS IN THE (), JUST THE TRANSLATED CONTENT.\n\n" +
                "Text to translate:\n{message}");

        
        DEFAULT_PROMPTS.put("openai.systemPrompt",
                "You are a professional translator specializing in gaming terminology and casual Minecraft chat.\n" +
                "Your role is to provide accurate, natural translations while preserving game-specific elements.\n" +
                "Always respond with ONLY the translated text, without any explanations, notes, or additional content.");

        DEFAULT_PROMPTS.put("openai.userPrompt",
                "Translate from {sourceLang} to {targetLang}. Follow these rules strictly:\n" +
                "1) Directly translate content only; do not add, remove, or change anything.\n" +
                "2) Do not translate placeholders/variables (like %player%, {player}, {item}, ${amount}, {0}) or command syntax (e.g. /warp, /msg).\n" +
                "3) Only translate text values; do not modify JSON keys or structure.\n" +
                "4) Preserve punctuation, spacing, capitalization, emoji, and the informal gaming tone.\n" +
                "Return ONLY the translated text — no explanations, notes, quotes, or extra content.\n\n" +
                "Text:\n{message}");

        
        DEFAULT_PROMPTS.put("gemini.systemInstruction",
                "You are a professional translator for a Minecraft server chat system. " +
                "Your task is to translate messages between players accurately while preserving gaming context and informal tone. " +
                "Guidelines:\n" +
                "- Preserve gaming terminology, slang, and Minecraft-specific terms\n" +
                "- Keep the informal, casual tone typical of gaming chat\n" +
                "- Don't translate proper nouns unless contextually necessary\n" +
                "- For ambiguous words, choose meaning based on gaming/chat context\n" +
                "- Return ONLY the translated text, no explanations or extra formatting\n" +
                "- If the text is already in the target language, return it unchanged\n" +
                "- Preserve any special characters or formatting symbols");

        DEFAULT_PROMPTS.put("gemini.systemInstructionWithContext",
                "You are a professional translator for a Minecraft server chat system. " +
                "Your task is to translate messages between players accurately while preserving gaming context and informal tone. " +
                "Guidelines:\n" +
                "- Preserve gaming terminology, slang, and Minecraft-specific terms\n" +
                "- Keep the informal, casual tone typical of gaming chat\n" +
                "- Don't translate proper nouns unless contextually necessary\n" +
                "- Player names in the 'Online players' list should not be translated\n" +
                "- For ambiguous words, choose meaning based on gaming/chat context\n" +
                "- Return ONLY the translated text, no explanations or extra formatting\n" +
                "- If the text is already in the target language, return it unchanged\n" +
                "- Preserve any special characters or formatting symbols");

        DEFAULT_PROMPTS.put("gemini.translationPrompt",
                "Translate the following text from {sourceLang} to {targetLang}:\n\n{message}");

        DEFAULT_PROMPTS.put("gemini.autoDetectPrompt",
                "Automatically detect the language of the following text and translate it to {targetLang}:\n\n{message}");

        DEFAULT_PROMPTS.put("gemini.translationPromptWithContext",
                "Online players: {playerContext}\n\nTranslate the following text from {sourceLang} to {targetLang}:\n\n{message}");

        DEFAULT_PROMPTS.put("gemini.autoDetectPromptWithContext",
                "Online players: {playerContext}\n\nAutomatically detect the language of the following text and translate it to {targetLang}:\n\n{message}");
    }

    public PromptManager(MysterriaTranslator plugin) {
        this.plugin = plugin;
        this.promptsFile = new File(plugin.getDataFolder(), "prompts.yml");
        loadPrompts();
    }

    /**
     * Loads or reloads prompts from prompts.yml.
     * Creates the file with defaults if it doesn't exist.
     */
    public void loadPrompts() {
        
        if (!promptsFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                InputStream defaultPrompts = plugin.getResource("prompts.yml");
                if (defaultPrompts != null) {
                    Files.copy(defaultPrompts, promptsFile.toPath());
                    plugin.getLogger().info("Created default prompts.yml");
                } else {
                    plugin.getLogger().warning("Could not find default prompts.yml in resources");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to create prompts.yml", e);
            }
        }

        
        try {
            promptsConfig = YamlConfiguration.loadConfiguration(promptsFile);
            plugin.getLogger().info("Loaded custom translation prompts");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load prompts.yml, using defaults", e);
            promptsConfig = new YamlConfiguration();
        }
    }

    /**
     * Gets a prompt template with variables substituted.
     *
     * @param promptPath The path in prompts.yml (e.g., "ollama.prompt", "openai.systemPrompt")
     * @param variables  Map of variable names to values (e.g., "sourceLang" -> "English")
     * @return The prompt with variables replaced
     */
    public String getPrompt(String promptPath, Map<String, String> variables) {
        String template = promptsConfig.getString(promptPath, DEFAULT_PROMPTS.get(promptPath));

        if (template == null) {
            plugin.getLogger().warning(String.format(
                    "Prompt '%s' not found in prompts.yml and no default available", promptPath));
            return "";
        }

        
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            result = result.replace(placeholder, entry.getValue());
        }

        return result;
    }

    /**
     * Gets a raw prompt template without variable substitution.
     *
     * @param promptPath The path in prompts.yml
     * @return The raw template string
     */
    public String getRawPrompt(String promptPath) {
        return promptsConfig.getString(promptPath, DEFAULT_PROMPTS.get(promptPath));
    }

    /**
     * Checks if a prompt exists in the configuration.
     *
     * @param promptPath The path to check
     * @return true if the prompt exists
     */
    public boolean hasPrompt(String promptPath) {
        return promptsConfig.contains(promptPath) || DEFAULT_PROMPTS.containsKey(promptPath);
    }

    /**
     * Reloads prompts from disk.
     */
    public void reload() {
        loadPrompts();
    }
}
