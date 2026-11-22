package tech.bogomolov.incomingsmsgateway;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {

    private Context context;
    private ListAdapter listAdapter;

    private static final int PERMISSION_CODE = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ArrayList<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (permissions.isEmpty()) {
            showList();
        } else {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != PERMISSION_CODE) {
            return;
        }
        for (int i = 0; i < permissions.length; i++) {
            if (!permissions[i].equals(Manifest.permission.RECEIVE_SMS)) {
                continue;
            }

            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                showList();
            } else {
                showInfo(getResources().getString(R.string.permission_needed));
            }

            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED) {
            showList();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        invalidateOptionsMenu();
        if (listAdapter != null) {
            listAdapter.clear();
            listAdapter.addAll(ForwardingConfig.getAll(this));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.action_bar_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem retryItem = menu.findItem(R.id.action_bar_retry_failed);
        int count = FailedMessage.getCount(this);
        retryItem.setVisible(count > 0);
        if (count > 0) {
            retryItem.setTitle(getString(R.string.menu_retry_failed, count));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_bar_select_office) {
            showOfficePicker();
            return true;
        }

        if (id == R.id.action_bar_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }

        if (id == R.id.action_bar_retry_failed) {
            int count = FailedMessage.getCount(this);
            FailedMessage.retryAll(this);
            Toast.makeText(this, getString(R.string.retry_failed_toast, count), Toast.LENGTH_LONG).show();
            invalidateOptionsMenu();
            return true;
        }

        if (id == R.id.action_bar_syslogs) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View view = getLayoutInflater().inflate(R.layout.syslogs, null);

            String logs = "";
            try {
                String[] command = new String[]{
                        "logcat", "-d", "*:E", "-m", "1000",
                        "|", "grep", "tech.bogomolov.incomingsmsgateway"};
                Process process = Runtime.getRuntime().exec(command);

                BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    logs += line + "\n";
                }
            } catch (IOException ex) {
                logs = "getLog failed";
            }

            TextView logsTextContainer = view.findViewById(R.id.syslogs_text);
            logsTextContainer.setText(logs);

            TextView version = view.findViewById(R.id.syslogs_version);
            version.setText("v" + BuildConfig.VERSION_NAME);

            builder.setView(view);
            builder.setNegativeButton(R.string.btn_close, null);
            builder.setNeutralButton(R.string.btn_clear, null);

            final AlertDialog dialog = builder.show();
            Objects.requireNonNull(dialog.getWindow())
                    .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view1 -> {
                        String[] command = new String[]{"logcat", "-c"};
                        try {
                            Runtime.getRuntime().exec(command);
                        } catch (IOException e) {
                            Log.e("SmsGateway", "log clear error: " + e);
                        }
                        dialog.cancel();
                    });
        }

        return super.onOptionsItemSelected(item);
    }

    // Шаг 1: выбор офиса. После выбора — спрашиваем номер этого телефона.
    private void showOfficePicker() {
        int current = Offices.getSelectedIndex(this);
        final int[] choice = { current };
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Выберите офис");
        builder.setSingleChoiceItems(Offices.NAMES, current, (d, which) -> choice[0] = which);
        builder.setPositiveButton("Далее", (d, w) -> {
            if (choice[0] >= 0) {
                askPhoneNumber(choice[0]);
            }
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    // Шаг 2: номер этого телефона (виден в /status). Применяем офис + номер.
    private void askPhoneNumber(int officeIndex) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint("+7...");
        input.setText(Offices.getPhoneLabel(this));
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Номер этого телефона");
        builder.setMessage("Введите номер этой SIM — он будет виден в статусе телефонов.");
        builder.setView(input);
        builder.setPositiveButton("Готово", (d, w) -> {
            String label = input.getText().toString().trim();
            Offices.apply(this, officeIndex, label);
            if (listAdapter != null) {
                listAdapter.clear();
                listAdapter.addAll(ForwardingConfig.getAll(this));
            }
            Toast.makeText(this,
                    "Этот телефон: " + Offices.NAMES[officeIndex]
                            + (label.isEmpty() ? "" : ", " + label),
                    Toast.LENGTH_LONG).show();
            // После настройки — просим разрешение не уходить в сон (без ограничений батареи).
            requestIgnoreBatteryOptimizations();
        });
        builder.setNegativeButton("Отмена", null);
        builder.show();
    }

    // Показывает системный запрос "работать без ограничений батареи" (отключить Doze),
    // чтобы телефон не усыплял приложение и SMS пересылались сразу, а не пачкой при
    // разблокировке. Если разрешение уже выдано — ничего не делает.
    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        String pkg = getPackageName();
        if (pm != null && pm.isIgnoringBatteryOptimizations(pkg)) {
            return;  // уже разрешено
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + pkg));
            startActivity(intent);
        } catch (Exception e) {
            // На некоторых прошивках прямой запрос недоступен — открываем общий список.
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Log.e("SmsGateway", "battery opt request failed: " + e);
            }
        }
    }

    private void showList() {
        context = this;
        ListView listview = findViewById(R.id.listView);

        ArrayList<ForwardingConfig> configs = ForwardingConfig.getAll(context);

        showInfo(configs.isEmpty() ? getString(R.string.empty_list_hint) : "");

        listAdapter = new ListAdapter(configs, context);
        listview.setAdapter(listAdapter);

        listAdapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                showInfo(listAdapter.getCount() == 0 ? getString(R.string.empty_list_hint) : "");
            }
        });

        FloatingActionButton fab = findViewById(R.id.btn_add);
        fab.setOnClickListener(this.showAddDialog());

        if (!this.isServiceRunning()) {
            this.startService();
        }

        // Первый запуск: офис не выбран -> открываем выбор офиса сразу.
        if (Offices.getSelectedIndex(context) == -1) {
            showOfficePicker();
        }
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (tech.bogomolov.incomingsmsgateway.SmsReceiverService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void startService() {
        Context appContext = getApplicationContext();
        Intent intent = new Intent(this, SmsReceiverService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent);
        } else {
            appContext.startService(intent);
        }
    }

    private void showInfo(String text) {
        TextView notice = findViewById(R.id.info_notice);
        notice.setText(text);
        findViewById(R.id.empty_state).setVisibility(
                text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View.OnClickListener showAddDialog() {
        return v -> {
            (new ForwardingConfigDialog(context, getLayoutInflater(), listAdapter)).showNew();
        };
    }
}
