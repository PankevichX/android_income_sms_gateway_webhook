package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;

// ============================================================================
//  НАСТРОЙКИ ОФИСОВ — единственное место, которое нужно править под себя.
//  KEY каждого офиса — значение "src=..." из URL, который выдал бот (/addsource).
// ============================================================================
public class Offices {

    // Адреса сервера-маршрутизатора. Без слэша в конце.
    public static final String SERVER_BASE = "http://132.243.189.102:8443/sms";
    public static final String SERVER_HEARTBEAT = "http://132.243.189.102:8443/heartbeat";

    // Названия офисов.
    public static final String[] NAMES = {
            "Офис 1",
            "Офис 2",
            "Офис 3",
            "Офис 4",
    };

    // Ключи src=... по порядку NAMES.
    public static final String[] KEYS = {
            "WkRlkSgwN6hMzTvMJy59SA",   // Офис 1
            "-s7tZJ5qdFZfp3b1YLXkVw",   // Офис 2
            "q7Sbup9IL2HKyygqmbz3Dg",   // Офис 3
            "OM6ba04CUTDAJgmJRI91JQ",   // Офис 4
    };

    private static final String TEMPLATE =
            "{\"from\":\"%from%\",\"text\":\"%text%\",\"sim\":\"%sim%\"}";

    private static final String RULE_KEY = "office_rule";
    private static final int HEARTBEAT_MINUTES = 5;  // как часто телефон шлёт "я жив"

    private static final String PREF = "office_selection";
    private static final String PREF_INDEX = "selected_index";
    private static final String PREF_PHONE = "phone_label";

    public static int getSelectedIndex(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(PREF_INDEX, -1);
    }

    public static String getPhoneLabel(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PREF_PHONE, "");
    }

    private static void saveSelection(Context context, int index, String phoneLabel) {
        SharedPreferences.Editor e = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        e.putInt(PREF_INDEX, index);
        e.putString(PREF_PHONE, phoneLabel);
        e.commit();
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // Применяет выбранный офис и номер телефона: создаёт правило пересылки на
    // сервер и включает "пульс" (телефон сам отмечается раз в HEARTBEAT_MINUTES мин).
    public static void apply(Context context, int index, String phoneLabel) {
        if (index < 0 || index >= KEYS.length) {
            return;
        }
        if (phoneLabel == null) phoneLabel = "";
        String q = "?src=" + KEYS[index] + "&device=" + enc(phoneLabel);

        ForwardingConfig config = new ForwardingConfig(context);
        config.setKey(RULE_KEY);
        config.setSender("*");
        config.setSmsFilter("");
        config.setUrl(SERVER_BASE + q);
        config.setTemplate(TEMPLATE);
        config.setHeaders(ForwardingConfig.getDefaultJsonHeaders());
        config.setRetriesNumber(ForwardingConfig.getDefaultRetriesNumber());
        config.setChunkedMode(false);
        config.save();

        // Пульс на тот же сервер, с тем же ключом и номером.
        new HeartbeatSettings(true, SERVER_HEARTBEAT + q, HEARTBEAT_MINUTES).save(context);

        saveSelection(context, index, phoneLabel);
    }
}
