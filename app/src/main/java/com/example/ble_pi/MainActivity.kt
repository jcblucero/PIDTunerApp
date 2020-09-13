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
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
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
    /*Client Characteristic Config Descriptor - Client must write 0x0001 to this to enable notifications
    * (CCCD) 16 bit UUID 2902 as per BT spec. So standard BT UUID + 16 bit = full 128 bit
    * */
    val START_STOP_CCFG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

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

        val property_read_write = BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE;
        val permission_read_write = BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE;
        //read,write,notify
        val property_rwn = property_read_write or BluetoothGattCharacteristic.PROPERTY_NOTIFY;

        //Create Characteristics
        var throttle_characteristic = BluetoothGattCharacteristic(THROTTLE_CHAR_UUID,property_read_write,permission_read_write);
        var kp_characteristic = BluetoothGattCharacteristic(KP_CHAR_UUID,property_read_write,permission_read_write);
        var ki_characteristic = BluetoothGattCharacteristic(KI_CHAR_UUID,property_read_write,permission_read_write);
        var kd_characteristic = BluetoothGattCharacteristic(KD_CHAR_UUID,property_read_write,permission_read_write);
        var start_stop_characteristic = BluetoothGattCharacteristic(START_STOP_CHAR_UUID,property_rwn,permission_read_write);

        //Add CCFG descriptor to start stop characteristic
        var start_stop_descriptor = BluetoothGattDescriptor(START_STOP_CCFG_UUID,permission_read_write);
        start_stop_characteristic.addDescriptor(start_stop_descriptor);

        //Now add characteristics to service
        service.addCharacteristic(throttle_characteristic);
        service.addCharacteristic(kp_characteristic);
        service.addCharacteristic(ki_characteristic);
        service.addCharacteristic(kd_characteristic);
        service.addCharacteristic(start_stop_characteristic);

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
        Log.d("PID_TUNER_PROFILE", "Read Characteristic by UUID")
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

    //When a device is disconnected, we must remove it from our connected devices list
    //call this function to remove from list
    fun DeviceDisconnected( device: BluetoothDevice? )
    {
        registeredDevices.remove(device)
    }

    fun WriteAndNotifyStartStop(new_state : Int, ble_gatt_server : BluetoothGattServer)
    {
        start_stop_state = new_state;
        if (registeredDevices.isEmpty()) {
            Log.i("Notify_PIDProfile", "No subscribers registered")
            return
        }

        Log.i("Notify_PIDProfile", "Sending update to ${registeredDevices.size} subscribers")
        for (device in registeredDevices)
        {
            var start_stop_characteristic = ble_gatt_server?.getService(PID_TUNER_SERVICE_UUID)
                ?.getCharacteristic(START_STOP_CHAR_UUID);
            start_stop_characteristic?.value = PackIntToByteArray(start_stop_state);
            ble_gatt_server?.notifyCharacteristicChanged(device, start_stop_characteristic, false)

        }
    }

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

                //Let the PID profile know a device disconnected
                PidTunerProfile.DeviceDisconnected(device);
            }
        }


        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic)
        {
            var response = PidTunerProfile.ReadCharacteristicByUuid(characteristic.uuid);
            if(response != null)
            {
                gatt_server?.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    response)
            }
            else
            {
                // Invalid characteristic
                Log.d("GATTServerCallback", "Invalid Characteristic Read: " + characteristic.uuid)
                gatt_server?.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0,
                    null)
            }
        } /* End onCharacteristicReadRequest */

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                             descriptor: BluetoothGattDescriptor)
        {
            var response = PidTunerProfile.ReadDescriptorByUuid(descriptor.uuid, device)
            if( response != null){
                gatt_server?.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    response)
            }
            else{
                gatt_server?.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0,
                    null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                                             descriptor: BluetoothGattDescriptor,
                                             preparedWrite: Boolean, responseNeeded: Boolean,
                                             offset: Int, value: ByteArray)
        {
            var success = PidTunerProfile.WriteDescriptorByUuid(descriptor.uuid, device, value)
            if( success ){
                if(responseNeeded) {
                    gatt_server?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null)
                }
            }
            else{
                if(responseNeeded) {
                    gatt_server?.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null
                    )
                }
            }
        }



    }

    inner class BleAdvertiseCallback(): AdvertiseCallback()
    {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)


            Log.e("BLE", advertising_parcel_uuid.toString());
            Log.i("BLE", ble_advertising_data.toString());
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

    lateinit var throttle_edit_text : EditText;
    lateinit var kp_edit_text : EditText;
    lateinit var ki_edit_text : EditText;
    lateinit var kd_edit_text : EditText;

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
            .addServiceUuid(advertising_parcel_uuid)
            //.addServiceData(advertising_parcel_uuid, "HELLO".toByteArray( Charsets.UTF_8 ))
            .build();

        ble_advertise_callback = BleAdvertiseCallback();

        //Set up BLE Peripheral Server
        gatt_server = bluetooth_manager.openGattServer(applicationContext, gatt_server_callback);
        
        gatt_server.addService(PidTunerProfile.CreatePidTunerService())
        //public void startAdvertising (AdvertiseSettings settings,
        //                AdvertiseData advertiseData,
        //                AdvertiseCallback callback)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Bluetooth initialization
        bluetooth_le_init();

        //Add listeners for text box changes
        throttle_edit_text = findViewById<EditText>(R.id.throttle_editText);
        kp_edit_text = findViewById<EditText>(R.id.kp_editText);
        ki_edit_text = findViewById<EditText>(R.id.ki_editText);
        kd_edit_text = findViewById<EditText>(R.id.kd_editText);

        throttle_edit_text.addTextChangedListener( object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                PidTunerProfile.throttle_pwm = s.toString().toFloat();
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }
        })
        kp_edit_text.addTextChangedListener( object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                PidTunerProfile.kp = s.toString().toFloat();
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }
        })
        ki_edit_text.addTextChangedListener( object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                PidTunerProfile.ki = s.toString().toFloat();
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }
        })
        kd_edit_text.addTextChangedListener( object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                PidTunerProfile.kd = s.toString().toFloat();
                Log.d("Kd_edit_text", "Kd set to: " + PidTunerProfile.kd.toString())
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

            }
        })
        //Button Listeners
        /*TODO: notify when start/stop changed*/
        start_stop_button = findViewById<Button>(R.id.start_stop_throttle_button);
        start_stop_button?.setOnClickListener()
        {
            if(throttle_state == THROTTLE_STATE.STOPPED){
                //start_stop_button.setBackgroundColor( Color.parseColor( (R.color.startGreen).toString() ) );
                throttle_state = THROTTLE_STATE.RUNNING;
                start_stop_button.setText("Stop");
                start_stop_button.setBackgroundColor( ContextCompat.getColor(this,R.color.stopRed ) );
                //PidTunerProfile.start_stop_state = 0;
                PidTunerProfile.WriteAndNotifyStartStop(1,gatt_server);
            }
            else{
                throttle_state = THROTTLE_STATE.STOPPED;
                start_stop_button.setText("Start");
                start_stop_button.setBackgroundColor( ContextCompat.getColor(this,R.color.startGreen ) );
                PidTunerProfile.WriteAndNotifyStartStop(0,gatt_server);
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
