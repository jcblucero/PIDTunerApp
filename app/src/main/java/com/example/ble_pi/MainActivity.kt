package com.example.ble_pi

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.core.content.ContextCompat
import java.util.*

enum class THROTTLE_STATE {
    STOPPED, RUNNING
}
enum class CONNECTION_STATE {
    DISCONNECTED, ADVERTISING, CONNECTED
}

class MainActivity : AppCompatActivity() {


    inner class GattServerCallback() : BluetoothGattServerCallback()
    {
        //public var ble_connection_state = CONNECTION_STATE.DISCONNECTED;
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if( newState == BluetoothProfile.STATE_CONNECTED){
                ble_connection_state = CONNECTION_STATE.CONNECTED;
                ble_connection_button.setText("Connected");
                ble_advertiser.stopAdvertising(ble_advertise_callback);
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED){
                ble_connection_state = CONNECTION_STATE.DISCONNECTED;
                ble_connection_button.setText("Disconnected");
                //ble_connection_button.setBackgroundColor( ContextCompat.getColor(this,R.color.stopRed ) );
            }
        }
    }

    inner class BleAdvertiseCallback(): AdvertiseCallback()
    {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            ble_connection_button.setText("Advertising");
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            ble_connection_button.setText("Advertise Failed");
            Log.e( "BLE", "Advertising onStartFailure: " + errorCode);
            Log.e("BLE", ble_advertising_data.toString());
        }
    }

    //BLE Management Variables
    lateinit var bluetooth_adapter: BluetoothAdapter;
    lateinit var gatt_server_callback : GattServerCallback;
    lateinit var ble_advertiser : BluetoothLeAdvertiser;
    lateinit var ble_advertising_settings: AdvertiseSettings;
    lateinit var ble_advertising_data: AdvertiseData;
    lateinit var ble_advertise_callback: AdvertiseCallback;

    var ble_adv_uuid = "DEADBEEF-3f00-4789-ae47-7e75d6b23bc8";
    var advertising_parcel_uuid = ParcelUuid.fromString(ble_adv_uuid);


    //App States
    var throttle_state = THROTTLE_STATE.STOPPED;
    var ble_connection_state = CONNECTION_STATE.DISCONNECTED;

    //UI Elements
    lateinit var start_stop_button : Button;
    lateinit var ble_connection_button: Button;

    //Bluetooth LE Initialization
    fun bluetooth_le_init(){
        val bluetooth_manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetooth_adapter = bluetooth_manager.getAdapter();
        gatt_server_callback = GattServerCallback();

        ble_advertiser = bluetooth_adapter.getBluetoothLeAdvertiser();
        //advertise settings,data, and callback
        ble_advertising_settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0) //no timeout
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build();
        ble_advertising_data = AdvertiseData.Builder()
            //.setIncludeDeviceName(true)
            //.addServiceUuid(advertising_parcel_uuid)
            .addServiceData(advertising_parcel_uuid, "HELLO".toByteArray( Charsets.UTF_8 ))
            .build();

        ble_advertise_callback = BleAdvertiseCallback();

        //public void startAdvertising (AdvertiseSettings settings,
        //                AdvertiseData advertiseData,
        //                AdvertiseCallback callback)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Bluetooth initialization
        bluetooth_le_init();


        //Button Listeners
        start_stop_button = findViewById<Button>(R.id.start_stop_throttle_button);
        start_stop_button?.setOnClickListener()
        {
            if(throttle_state == THROTTLE_STATE.STOPPED){
                //start_stop_button.setBackgroundColor( Color.parseColor( (R.color.startGreen).toString() ) );
                throttle_state = THROTTLE_STATE.RUNNING;
                start_stop_button.setText("Stop");
                start_stop_button.setBackgroundColor( ContextCompat.getColor(this,R.color.stopRed ) );
            }
            else{
                throttle_state = THROTTLE_STATE.STOPPED;
                start_stop_button.setText("Start");
                start_stop_button.setBackgroundColor( ContextCompat.getColor(this,R.color.startGreen ) );
            }
        }

        ble_connection_button = findViewById<Button>(R.id.ble_connection_button);
        ble_connection_button?.setOnClickListener()
        {
            if(ble_connection_state == CONNECTION_STATE.DISCONNECTED){
                ble_connection_state = CONNECTION_STATE.ADVERTISING;
                ble_connection_button.setText("Advertising");
                ble_advertiser.startAdvertising(ble_advertising_settings,ble_advertising_data,ble_advertise_callback);
                ble_connection_button.setBackgroundColor( ContextCompat.getColor(this,R.color.startGreen ) );
            }
            else if( (ble_connection_state == CONNECTION_STATE.ADVERTISING) ||
                    (ble_connection_state == CONNECTION_STATE.CONNECTED) ){
                ble_connection_state = CONNECTION_STATE.DISCONNECTED;
                ble_connection_button.setText("Disconnected");
                ble_connection_button.setBackgroundColor( ContextCompat.getColor(this,R.color.stopRed ) );
                ble_advertiser.stopAdvertising(ble_advertise_callback);
            }
        }

    }
}
