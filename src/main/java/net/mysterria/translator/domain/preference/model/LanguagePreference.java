package net.mysterria.translator.domain.preference.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class LanguagePreference {
    private String language;
    private String chatLanguage;
    private boolean translationsEnabled;
    private boolean hintShown;
    private boolean globalChatShown;
    private boolean discordChatShown;

    public LanguagePreference copy() {
        return new LanguagePreference(language, chatLanguage, translationsEnabled, hintShown, globalChatShown, discordChatShown);
    }
}
