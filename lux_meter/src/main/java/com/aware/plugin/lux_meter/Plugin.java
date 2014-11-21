package com.aware.plugin.lux_meter;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.aware.Aware;
import com.aware.ui.Stream_UI;
import com.aware.Aware_Preferences;
import com.aware.plugin.lux_meter.Provider.LuxMeter_Data;
import com.aware.providers.Light_Provider.Light_Data;
import com.aware.utils.Aware_Plugin;

import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Handler;

public class Plugin extends Aware_Plugin {

    public static final String ACTION_AWARE_PLUGIN_LUX_METER = "ACTION_AWARE_PLUGIN_LUX_METER";

    public static final String EXTRA_OVER_THRESHOLD = "over_threshold";

    public static final String EXTRA_AVG_LUX = "avg_lux";

    private static boolean over_threashold;

    public static int temp_interval = 5;

    public LuxAlarm alarm = new LuxAlarm();

    public static ContextProducer context_producer;

    static SharedPreferences sharedpreferences;

    public static final String LUXMETER_PREFS = "luxmeter_prefs" ;

    public static final String LAST_TIME = "last_time";

    private static long last_timestamp;

    private static long avg = 0;

    @Override
    public void onCreate() {
        Log.d("asd", "onCreate");
        super.onCreate();
        TAG = "AWARE::Lux Meter";
        DEBUG = true;

        Intent aware = new Intent(this, Aware.class);
        startService(aware);

        Aware.setSetting(getApplicationContext(), Settings.STATUS_PLUGIN_LUX_METER, true);

        if( Aware.getSetting(getApplicationContext(), Settings.FREQUENCY_PLUGIN_LUX_METER).length() == 0 ) {
            Aware.setSetting(getApplicationContext(), Settings.FREQUENCY_PLUGIN_LUX_METER, 10);
        }

        if( Aware.getSetting(getApplicationContext(), Settings.THRESHOLD_PLUGIN_LUX_METER).length() == 0 ) {
            Aware.setSetting(getApplicationContext(), Settings.THRESHOLD_PLUGIN_LUX_METER, 100);
        }

        CONTEXT_PRODUCER = new ContextProducer() {
            @Override
            public void onContext() {
                Intent context_lux_meter = new Intent();
                context_lux_meter.setAction(ACTION_AWARE_PLUGIN_LUX_METER);
                context_lux_meter.putExtra(EXTRA_AVG_LUX, avg);
                context_lux_meter.putExtra(EXTRA_OVER_THRESHOLD, over_threashold);
                sendBroadcast(context_lux_meter);
            }
        };
        context_producer = CONTEXT_PRODUCER;

        Aware.setSetting(getApplicationContext(), Aware_Preferences.STATUS_LIGHT, true);
        Intent apply = new Intent(Aware.ACTION_AWARE_REFRESH);
        sendBroadcast(apply);

        DATABASE_TABLES = Provider.DATABASE_TABLES;
        TABLES_FIELDS = Provider.TABLES_FIELDS;
        CONTEXT_URIS = new Uri[]{ LuxMeter_Data.CONTENT_URI };

        int interval_min =  Integer.parseInt(Aware.getSetting(getApplicationContext(), Settings.FREQUENCY_PLUGIN_LUX_METER));
        alarm.SetAlarm(Plugin.this, interval_min);
        temp_interval = interval_min;

        sharedpreferences = getSharedPreferences(LUXMETER_PREFS, Context.MODE_PRIVATE);

        if (sharedpreferences.contains(LAST_TIME)) {
            last_timestamp = sharedpreferences.getLong(LAST_TIME, System.currentTimeMillis());
        }

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("asd", "onStartCommand");
        int interval_min =  Integer.parseInt(Aware.getSetting(getApplicationContext(), Settings.FREQUENCY_PLUGIN_LUX_METER));

        if (interval_min != temp_interval) {
            Log.d("asd", "differ");
            if(interval_min >= 1) {
                Log.d("asd", "bigger");
                alarm.CancelAlarm(Plugin.this);
                alarm.SetAlarm(Plugin.this, interval_min);
                temp_interval = interval_min;
            } else {
                Log.d("asd", "zero");
                temp_interval = interval_min;
                alarm.CancelAlarm(Plugin.this);
                Aware.setSetting(getApplicationContext(), Aware_Preferences.STATUS_LIGHT, true);
                Intent apply = new Intent(Aware.ACTION_AWARE_REFRESH);
                getApplicationContext().sendBroadcast(apply);
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d("asd", "onDestroy");
        super.onDestroy();

        alarm.CancelAlarm(Plugin.this);

        Aware.setSetting(getApplicationContext(), Settings.STATUS_PLUGIN_LUX_METER, false);
        Aware.setSetting(getApplicationContext(), Aware_Preferences.STATUS_LIGHT, false);

        Intent apply = new Intent(Aware.ACTION_AWARE_REFRESH);
        sendBroadcast(apply);
    }

    protected static void getLight(Context context) {
        Log.d("asd", "getLight");
        try {
            Thread.sleep(10000);
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        Intent lux_Service = new Intent(context, Lux_Service.class);
        context.startService(lux_Service);
    }

    public static class Lux_Service extends IntentService {
        public Lux_Service() {
            super("AWARE LUX_METER");
        }

        @Override
        protected void onHandleIntent(Intent intent) {
            Log.d("asd", "onHandleIntent");
            Aware.setSetting(getApplicationContext(), Aware_Preferences.STATUS_LIGHT, false);
            Intent apply = new Intent(Aware.ACTION_AWARE_REFRESH);
            getApplicationContext().sendBroadcast(apply);

            last_timestamp = System.currentTimeMillis();
            String[] values = {Long.toString(sharedpreferences.getLong(LAST_TIME, System.currentTimeMillis())) ,Long.toString(last_timestamp)};

            Cursor light = getApplicationContext().getContentResolver().query(Light_Data.CONTENT_URI, null, "TIMESTAMP BETWEEN ? AND ?", values, Light_Data.TIMESTAMP + " DESC");
            Editor editor = sharedpreferences.edit();

            editor.putLong(LAST_TIME, last_timestamp);
            editor.commit();

            if( light != null && light.moveToFirst() ) {

                long count = light.getCount();
                long sum = 0;

                light.moveToFirst();
                while(!light.isAfterLast()) {
                    sum += light.getLong(light.getColumnIndex(Light_Data.LIGHT_LUX));
                    light.moveToNext();
                }

                avg = sum/count;

                over_threashold = false;
                if( Integer.parseInt(Aware.getSetting(getApplicationContext(), Settings.THRESHOLD_PLUGIN_LUX_METER)) < avg ) {
                    over_threashold = true;
                }

                ContentValues data = new ContentValues();
                data.put(LuxMeter_Data.TIMESTAMP, System.currentTimeMillis());
                data.put(LuxMeter_Data.DEVICE_ID, Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                data.put(LuxMeter_Data.UPDATE_FREQUENCY, Integer.parseInt(Aware.getSetting(getApplicationContext(), Settings.FREQUENCY_PLUGIN_LUX_METER)));
                data.put(LuxMeter_Data.LIGHT_THRESHOLD, Integer.parseInt(Aware.getSetting(getApplicationContext(), Settings.THRESHOLD_PLUGIN_LUX_METER)));
                data.put(LuxMeter_Data.LUX_AVG, avg);
                data.put(LuxMeter_Data.OVER_THRESHOLD, over_threashold);

                getContentResolver().insert(LuxMeter_Data.CONTENT_URI, data);

                context_producer.onContext();
            }

            if( light != null && ! light.isClosed() ) light.close();
        }
    }
}
