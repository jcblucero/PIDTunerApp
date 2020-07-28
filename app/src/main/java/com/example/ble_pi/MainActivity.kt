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


//Implementation of a PIDTunerProfile for Android in Kotlin
object PidTunerProfile //Objects are equivalent to singletons in kotlin
{
    //PID Tuner will offer 5 characteristics
    //Throttle PWM: R/W/M - float between 10.0-20.0 representing % duty cycle to command throttle motor
    //Kp: R/W/M - Float representing PID proportional gain constant
    //Ki: R/W/M - Float representing PID integral gain constant
    //Kd: R/W/M - Float representing PID derivative gain constant
    //Start Stop: R/W/M - Bool telling whether to run start (1), or stop robot (0)
    val PID_TUNER_SERVICE_UUID: UUID = UUID.fromString("DEADBEEF-3f00-4789-ae47-7e75d6b23bc8");
    val THROTTLE_CHAR_UUID: UUID = UUID.fromString("DEADBEE1-3f00-4789-ae47-7e75d6b23bc8");
    val KP_CHAR_UUID: UUID = UUID.fromString("DEADBEE2-3f00-4789-ae47-7e75d6b23bc8");
    val KI_CHAR_UUID: UUID = UUID.fromString("DEADBEE3-3f00-4789-ae47-7e75d6b23bc8");
    val KD_CHAR_UUID: UUID = UUID.fromString("DEADBEE4-3f00-4789-ae47-7e75d6b23bc8");
    val START_STOP_CHAR_UUID: UUID = UUID.fromString("DEADBEE5-3f00-4789-ae47-7e75d6b23bc8");
    /*Client Characteristic Config Descriptor - Client must write 0x0001 to this to enable notifications */
    val START_STOP_CCFG_UUID: UUID = UUID.fromString("DEADBEE6-3f00-4789-ae47-7e75d6b23bc8");

    var throttle_pwm: Float = 15.0F;
    var kp: Float = 1.0F;
    var ki: Float = 0.2F;
    var kd: Float = 0.0F;
    //0 = stop, 1 = start
    var start_stop_state: Int = 0;

    /* Collection of notification subscribers */
    private val registeredDevices = mutableSetOf<BluetoothDevice>()


    //When client receives notification of start, it will read other values.
    //Avoids having to notify when each value changes
    //Drawback is that we must stop in order to change characteristics

    //Create a BluetoothGattService,
    //Add service characteristics and descriptors
    //Return the created BluetoothGattService
    fun CreatePidTunerService(): BluetoothGattService {
        val service = BluetoothGattService(PID_TUNER_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        /* TODO: Add service BluetoothGattCharacteristic + config descriptor (BluetoothGattDescriptor) */

        return service;
    }

    //Multiply float by 1000 to go to 3 digit significance
    // receiver will have to divide by 1000
    //Must do this because Kotlin does not provide bitwise (&,>>, etc) functions for floats,
    // so convert to int
    fun PackFloatToByteArray(value: Float) : ByteArray {
        val bytes = ByteArray(4);
        val converted_val = (value * 1000).toInt();
        bytes[3] = (converted_val and 0xFF).toByte()
        bytes[2] = ((converted_val ushr 8) and 0xFF).toByte()
        bytes[1] = ((converted_val ushr 16) and 0xFF).toByte()
        bytes[0] = ((converted_val ushr 24) and 0xFF).toByte()
        return bytes;
    }

    //Convert Integer to Network Order (big endian) byte array
    fun PackIntToByteArray(value: Int): ByteArray {
        val bytes = ByteArray(4);
        bytes[3] = (value and 0xFF).toByte()
        bytes[2] = ((value ushr 8) and 0xFF).toByte()
        bytes[1] = ((value ushr 16) and 0xFF).toByte()
        bytes[0] = ((value ushr 24) and 0xFF).toByte()
        return bytes;
    }

    //Returns a byte array (size 4) of the value for the given characteristic UUID
    //Meant to be used with android onCharacteristicReadRequest of BluetoothGattServerCallback....
    //...next time make read generic, instead of tied to API (return value instead of ByteArray)
    fun ReadCharacteristicByUuid(char_uuid: UUID) : ByteArray? {
        //switch(UUID)...
        when {
            PidTunerProfile.KP_CHAR_UUID == char_uuid -> {
                return PackFloatToByteArray(kp);
            }
            PidTunerProfile.KI_CHAR_UUID == char_uuid -> {
                return PackFloatToByteArray(ki);
            }
            PidTunerProfile.KD_CHAR_UUID == char_uuid -> {
                return PackFloatToByteArray(kd);
            }
            PidTunerProfile.THROTTLE_CHAR_UUID == char_uuid -> {
                return PackFloatToByteArray(throttle_pwm);
            }
            PidTunerProfile.START_STOP_CHAR_UUID == char_uuid -> {
                return PackIntToByteArray(start_stop_state);
            }
            else -> {
                return null;
            }
        }
    }

    fun ReadDescriptorByUuid(descriptor_uuid: UUID, device: BluetoothDevice) : ByteArray?
    {
        Log.d("PID_TUNER_PROFILE", "Config descriptor read")
        when {
            PidTunerProfile.START_STOP_CCFG_UUID == descriptor_uuid -> {
                if (registeredDevices.contains(device)) {
                    return BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                }
                else {
                    return BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                }
            }
            else -> {
                return null;
            }
        }
    }

    //Writes descriptor for given UUID, returnn True if written, false if not
    //Only Client Characteristic Configuration Descriptor is supported
    //Must write 0x0001 to enable notifications as per BT spec
    fun WriteDescriptorByUuid(descriptor_uuid: UUID, device: BluetoothDevice, write_value: ByteArray) : Boolean {
        when {
            PidTunerProfile.START_STOP_CCFG_UUID == descriptor_uuid -> {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, write_value)) {
                    Log.d("PID_TUNER_PROFILE", "Subscribe device to notifications: $device")
                    registeredDevices.add(device)
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, write_value)) {
                    Log.d("PID_TUNER_PROFILE", "Unsubscribe device from notifications: $device")
                    registeredDevices.remove(device)
                }
                return true;
            }
            else -> {
                return false;
            }
        }
    }
    //Writes: vars are public by default, just write manually if you want to modify


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
                ble_connection_button.setBackgroundColor( ContextCompat.getColor(applicationContext,R.color.stopRed ) );
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
    lateinit var gatt_server : BluetoothGattServer;
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

        //Set up BLE Peripheral Server
        gatt_server = bluetooth_manager.openGattServer(applicationContext, gatt_server_callback);
        //gatt_server.addService(PidTunerProfile.createBleService())
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
