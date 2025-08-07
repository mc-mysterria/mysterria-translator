package net.mysterria.translator.storage.model;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public enum LangEnum {
    BRITISH_ENGLISH("en_gb"),
    AMERICAN_ENGLISH("en_us"),
    UPSIDE_DOWN_ENGLISH("en_ud"),
    GERMAN("de_de"),
    FRENCH("fr_fr"),
    SPANISH("es_es"),
    ITALIAN("it_it"),
    DUTCH("nl_nl"),
    POLISH("pl_pl"),
    FINNISH("fi_fi"),
    SWEDISH("sv_se"),
    CZECH("cs_cz"),
    SLOVAKIAN("sk_sk"),
    AFRIKAANS("af_za"),
    ANGLISH("enp"),
    ARABIC("ar_sa"),
    ASTURIAN("ast_es"),
    AZERBAIJANI("az_az"),
    BASHKIR("ba_ru"),
    BAVARIAN("bar"),
    BELARUSIAN("be_by"),
    BULGARIAN("bg_bg"),
    BRETON("br_fr"),
    BRABANTIAN("brb"),
    BOSNIAN("bs_BA"),
    CATALAN("ca_es"),
    WELSH("cy_gb"),
    DANISH("da_dk"),
    AUSTRIAN_GERMAN("de_at"),
    SWISS_GERMAN("de_ch"),
    GREEK("el_gr"),
    PIRATE("en_7s"),
    SHAKESPEAREAN("en_ws"),
    ESPERANTO("eo_uy"),
    ARGENTINIAN_SPANISH("es_ar"),
    CHILEAN_SPANISH("es_CL"),
    ECUADORIAN_SPANISH("es_ec"),
    MEXICAN_SPANISH("es_mx"),
    URUGUAYAN_SPANISH("es_uy"),
    VENEZUELAN_SPANISH("es_ve"),
    ANDALUSIAN("esan"),
    ESTONIAN("et_ee"),
    BASQUE("eu_es"),
    PERSIAN("fa_ir"),
    FILIPINO("fil_ph"),
    FAROESE("fo_fo"),
    EAST_FRANCONIAN("fra_de"),
    FRIULIAN("fur_it"),
    FRISIAN("fy_nl"),
    IRISH("ga_ie"),
    SCOTTISH_GAELIC("gd_gb"),
    GALICIAN("gl_es"),
    MANX("gv_im"),
    HAWAIIAN("haw"),
    HEBREW("he_il"),
    HINDI("hi_in"),
    CROATIAN("hr_hr"),
    HUNGARIAN("hu_hu"),
    ARMENIAN("hy_am"),
    INDONESIAN("id_id"),
    IGBO("ig_ng"),
    IDO("io_en"),
    ICELANDIC("is_is"),
    INTERSLAVIC("isv"),
    JAPANESE("ja_jp"),
    LOJBAN("jbo"),
    GEORGIAN("ka_ge"),
    KAZAKH("kk_kz"),
    KABYLE("kab_dz"),
    KANNADA("kn_in"),
    KOREAN("ko_kr"),
    RIPUARIAN("ksh_de"),
    CORNISH("kw_gb"),
    LATIN("la_va"),
    LOLCAT("lol_aa"),
    LITHUANIAN("lt_lt"),
    LATVIAN("lv_lv"),
    MAORI("mi_nz"),
    MACEDONIAN("mk_mk"),
    MONGOLIAN("mn_mn"),
    MOHAWK("mo_us"),
    MALAY("ms_my"),
    MALTESE("mt_mt"),
    NORWEGIAN("no_no"),
    BRAZILIAN_PORTUGESE("pt_br"),
    PORTUGESE("pt_pt"),
    QUENYA("qya_aa"),
    ROMANIAN("ro_ro"),
    RUSSIAN("ru_ru"),
    SLOVENIAN("sl_si"),
    SOMALI("so_so"),
    ALBANIAN("sq_al"),
    SERBIAN("sr_sp"),
    THAI("th_th"),
    KLINGON("tlh_aa"),
    TURKISH("tr_tr"),
    UKRAINIAN("uk_ua"),
    VIETNAMESE("vi_vn"),
    FRANCONIAN("vmf_de"),
    YORUBA("yo_ng"),
    CHINESE_SIMPLIFIED("zh_cn"),
    CHINESE_TRADITIONAL("zh_tw");

    private final String code;

    LangEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static Optional<LangEnum> fromCode(String code) {
        for (LangEnum lang : values()) {
            if (lang.getCode().equalsIgnoreCase(code)) {
                return Optional.of(lang);
            }
        }
        return Optional.empty();
    }

    public static boolean isValidCode(String code) {
        code = code.toLowerCase();
        return fromCode(code).isPresent();
    }

    public static Set<String> getAllCodes() {
        return Arrays.stream(values())
                .map(LangEnum::getCode)
                .collect(Collectors.toSet());
    }
}
