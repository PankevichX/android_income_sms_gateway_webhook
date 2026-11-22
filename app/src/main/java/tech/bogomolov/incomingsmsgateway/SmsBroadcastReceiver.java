package tech.bogomolov.incomingsmsgateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.work.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class SmsBroadcastReceiver extends BroadcastReceiver {

    private Context context;

    // Одна совпавшая пересылка, чтобы отправить её вне главного потока.
    private static class Delivery {
        final ForwardingConfig config;
        final String sender;
        final String slotName;
        final String content;
        final long timeStamp;

        Delivery(ForwardingConfig config, String sender, String slotName,
                 String content, long timeStamp) {
            this.config = config;
            this.sender = sender;
            this.slotName = slotName;
            this.content = content;
            this.timeStamp = timeStamp;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        this.context = context;

        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            return;
        }

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) {
            return;
        }

        StringBuilder content = new StringBuilder();
        final SmsMessage[] messages = new SmsMessage[pdus.length];
        for (int i = 0; i < pdus.length; i++) {
            messages[i] = SmsMessage.createFromPdu((byte[]) pdus[i]);
            content.append(messages[i].getDisplayMessageBody());
        }

        ArrayList<ForwardingConfig> configs = ForwardingConfig.getAll(context);
        String asterisk = context.getString(R.string.asterisk);

        String sender = messages[0].getOriginatingAddress();
        if (sender == null) {
            return;
        }

        List<Delivery> deliveries = new ArrayList<>();

        for (ForwardingConfig config : configs) {
            if (!matchesSender(config, sender, asterisk)) {
                continue;
            }

            if (!config.getIsSmsEnabled()) {
                continue;
            }

            if (!matchesFilter(config.getSmsFilter(), content.toString())) {
                continue;
            }

            int slotId = this.detectSim(bundle) + 1;
            String slotName = "undetected";
            if (slotId < 0) {
                slotId = 0;
            }

            if (config.getSimSlot() > 0 && config.getSimSlot() != slotId) {
                continue;
            }

            if (slotId > 0) {
                slotName = "sim" + slotId;
            }

            deliveries.add(new Delivery(config, sender, slotName, content.toString(),
                    messages[0].getTimestampMillis()));
        }

        if (deliveries.isEmpty()) {
            return;
        }

        // Приход SMS коротко будит телефон из Doze. Мы используем это окно, чтобы
        // отправить СРАЗУ, а не класть в очередь (которую Doze отложил бы). goAsync()
        // держит ресивер живым, пока фоновый поток делает сетевой запрос, а короткий
        // wakelock не даёт CPU заснуть до завершения отправки. Если отправить не
        // вышло — откатываемся в обычную очередь с повторами.
        final PendingResult pendingResult = goAsync();
        final Context appContext = context.getApplicationContext();

        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wakeLock = (pm == null) ? null : pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SmsForwarder:send");
        if (wakeLock != null) {
            try {
                wakeLock.acquire(55_000L);  // страховочный таймаут
            } catch (Exception ignored) {
            }
        }

        new Thread(() -> {
            try {
                for (Delivery d : deliveries) {
                    deliverNow(appContext, d);
                }
            } catch (Throwable t) {
                Log.e("SmsBroadcastReceiver", "immediate send error: " + t);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    try {
                        wakeLock.release();
                    } catch (Exception ignored) {
                    }
                }
                try {
                    pendingResult.finish();
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    // Пытается доставить прямо сейчас, в окне пробуждения от SMS. При любой неудаче
    // (не 2xx, сетевая ошибка, исключение) откатывается в очередь WorkManager, чтобы
    // существующая логика повторов / backoff / "сохранять неудавшиеся" продолжала
    // работать как раньше.
    private void deliverNow(Context appContext, Delivery d) {
        ForwardingConfig config = d.config;
        String message = config.prepareMessage(d.sender, d.content, d.slotName, d.timeStamp);

        boolean sentOk = false;
        try {
            Request request = new Request(config.getUrl(), message);
            request.setJsonHeaders(config.getHeaders());
            if (config.getSignHmacSha256()
                    && config.getSignHmacSha256Secret() != null
                    && !config.getSignHmacSha256Secret().isEmpty()) {
                request.setSignatureHeader(config.getSignHmacSha256Secret(), message);
            }
            request.setIgnoreSsl(config.getIgnoreSsl());
            request.setUseChunkedMode(config.getChunkedMode());
            String result = request.execute();
            sentOk = Request.RESULT_SUCCESS.equals(result);
        } catch (Throwable t) {
            Log.e("SmsBroadcastReceiver", "direct send failed: " + t);
        }

        if (sentOk) {
            return;
        }

        // Откат: обычная очередь с повторами.
        Data data = new Data.Builder()
                .putString(RequestWorker.DATA_URL, config.getUrl())
                .putString(RequestWorker.DATA_TEXT, message)
                .putString(RequestWorker.DATA_HEADERS, config.getHeaders())
                .putBoolean(RequestWorker.DATA_IGNORE_SSL, config.getIgnoreSsl())
                .putBoolean(RequestWorker.DATA_CHUNKED_MODE, config.getChunkedMode())
                .putInt(RequestWorker.DATA_MAX_RETRIES, config.getRetriesNumber())
                .putBoolean(RequestWorker.DATA_SIGN_HMAC_SHA256, config.getSignHmacSha256())
                .putString(RequestWorker.DATA_SIGN_HMAC_SHA256_SECRET, config.getSignHmacSha256Secret())
                .putBoolean(RequestWorker.DATA_STORE_FAILED, config.getStoreFailed())
                .putBoolean(RequestWorker.DATA_LOCAL_MODE, config.getLocalMode())
                .build();

        RequestWorker.enqueue(appContext, data);
    }

    // Per-config sender match. The asterisk wildcard always means "any sender"
    // regardless of the regex flag.
    static boolean matchesSender(ForwardingConfig config, String sender, String asterisk) {
        String configured = config.getSender();
        if (configured.equals(asterisk)) {
            return true;
        }
        if (config.getIsSenderRegex()) {
            try {
                return Pattern.compile(configured).matcher(sender).find();
            } catch (PatternSyntaxException e) {
                Log.e("SmsBroadcastReceiver",
                        "Invalid sender regex \"" + configured + "\": " + e.getMessage());
                return false;
            }
        }
        return sender.equals(configured);
    }

    // Per-config content filter. An empty filter forwards every message.
    static boolean matchesFilter(String filter, String content) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        try {
            return Pattern.compile(filter).matcher(content).find();
        } catch (PatternSyntaxException e) {
            Log.e("SmsBroadcastReceiver", "Invalid filter regex \"" + filter + "\": " + e.getMessage());
            return true;
        }
    }

    private int detectSim(Bundle bundle) {
        int slotId = -1;
        Set<String> keySet = bundle.keySet();
        for (String key : keySet) {
            switch (key) {
                case "phone":
                    slotId = bundle.getInt("phone", -1);
                    break;
                case "slot":
                    slotId = bundle.getInt("slot", -1);
                    break;
                case "simId":
                    slotId = bundle.getInt("simId", -1);
                    break;
                case "simSlot":
                    slotId = bundle.getInt("simSlot", -1);
                    break;
                case "slot_id":
                    slotId = bundle.getInt("slot_id", -1);
                    break;
                case "simnum":
                    slotId = bundle.getInt("simnum", -1);
                    break;
                case "slotId":
                    slotId = bundle.getInt("slotId", -1);
                    break;
                case "slotIdx":
                    slotId = bundle.getInt("slotIdx", -1);
                    break;
                case "android.telephony.extra.SLOT_INDEX":
                    slotId = bundle.getInt("android.telephony.extra.SLOT_INDEX", -1);
                    break;
                default:
                    if (key.toLowerCase().contains("slot") | key.toLowerCase().contains("sim")) {
                        String value = bundle.getString(key, "-1");
                        if (value.equals("0") | value.equals("1") | value.equals("2")) {
                            slotId = bundle.getInt(key, -1);
                        }
                    }
            }

            if (slotId != -1) {
                break;
            }
        }

        return slotId;
    }
}
