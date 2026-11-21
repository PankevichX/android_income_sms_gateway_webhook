package tech.bogomolov.incomingsmsgateway;

import android.content.Context;
import android.content.SharedPreferences;

// ============================================================================
//  НАСТРОЙКИ ОФИСОВ — единственное место, которое нужно править под себя.
//  Если сменится сервер или ключи — меняешь только константы ниже и пересобираешь.
//  KEY каждого офиса — это значение "src=..." из URL, который выдал бот
//  командой /addsource (часть после src= в ссылке).
// ============================================================================
public class Offices {

    // Адрес твоего сервера-маршрутизатора. Без слэша в конце.
    public static final String SERVER_BASE = "http://132.243.189.102:8443/sms";

    // Названия офисов, как они показываются в выборе.
    public static final String[] NAMES = {
            "Офис 1",
            "Офис 2",
            "Офис 3",
            "Офис 4",
    };

    // Ключ src=... для каждого офиса, В ТОМ ЖЕ ПОРЯДКЕ, что и NAMES выше.
    public static final String[] KEYS = {
            "WkRlkSgwN6hMzTvMJy59SA",   // Офис 1
            "-s7tZJ5qdFZfp3b1YLXkVw",   // Офис 2
            "q7Sbup9IL2HKyygqmbz3Dg",   // Офис 3
            "OM6ba04CUTDAJgmJRI91JQ",   // Офис 4
    };

    // Тело запроса, которое уходит на сервер по каждой SMS.
    private static final String TEMPLATE =
            "{\"from\":\"%from%\",\"text\":\"%text%\",\"sim\":\"%sim%\"}";

    // Фиксированный ключ хранения, чтобы повторный выбор офиса ПЕРЕЗАПИСЫВАЛ
    // одно и то же правило, а не плодил новые.
    private static final String RULE_KEY = "office_rule";

    private static final String PREF = "office_selection";
    private static final String PREF_INDEX = "selected_index";

    // Какой офис выбран сейчас (индекс в NAMES/KEYS), или -1 если ещё не выбирали.
    public static int getSelectedIndex(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.getInt(PREF_INDEX, -1);
    }

    private static void setSelectedIndex(Context context, int index) {
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        p.edit().putInt(PREF_INDEX, index).commit();
    }

    // Применяет выбранный офис: создаёт (или перезаписывает) единственное правило
    // пересылки, которое шлёт все входящие SMS на URL этого офиса.
    public static void apply(Context context, int index) {
        if (index < 0 || index >= KEYS.length) {
            return;
        }
        ForwardingConfig config = new ForwardingConfig(context);
        config.setKey(RULE_KEY);
        config.setSender("*");
        config.setSmsFilter("");
        config.setUrl(SERVER_BASE + "?src=" + KEYS[index]);
        config.setTemplate(TEMPLATE);
        config.setHeaders(ForwardingConfig.getDefaultJsonHeaders());
        config.setRetriesNumber(ForwardingConfig.getDefaultRetriesNumber());
        // Обычный запрос с Content-Length (не chunked) — так надёжнее принимается сервером.
        config.setChunkedMode(false);
        config.save();

        setSelectedIndex(context, index);
    }
}
