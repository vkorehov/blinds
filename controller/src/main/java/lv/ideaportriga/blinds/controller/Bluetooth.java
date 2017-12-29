package lv.ideaportriga.blinds.controller;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by VladislavKorehov on 27/12/2017.
 */

public class Bluetooth {
    private static final ParcelUuid SERVICE_UUID = ParcelUuid.fromString("D973F2E0-B19E-11E2-9E96-0800200C9A66");
    private final int CONN_RETRY_INTERVAL = 3000; // 3 Seconds
    private Handler handler = new Handler();
    private final ReentrantLock lock = new ReentrantLock();
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothCallback callback;
    private Context context;
    private boolean activated = false;
    private static class State {
        private BluetoothGatt gatt;
        private boolean expectDisconnect = false;
        private BluetoothGattCharacteristic txChar;
        private Integer towrite = null; // null means no write needed
        private Integer written = null; // null means no write needed

        public boolean isExpectDisconnect() {
            return expectDisconnect;
        }

        public void setExpectDisconnect(boolean expectDisconnect) {
            this.expectDisconnect = expectDisconnect;
        }

        public Integer getTowrite() {
            return towrite;
        }

        public void setTowrite(Integer towrite) {
            this.towrite = towrite;
        }

        public Integer getWritten() {
            return written;
        }

        public void setWritten(Integer written) {
            this.written = written;
        }

        public BluetoothGatt getGatt() {
            return this.gatt;
        }
        public void setGatt(BluetoothGatt gatt) {
            this.gatt = gatt;
        }
        public void setTxChar(BluetoothGattCharacteristic txChar) {
            this.txChar = txChar;
        }
        public BluetoothGattCharacteristic getTxChar() {
            return txChar;
        }

    }
    private Map<BluetoothDevice, State> state = new HashMap<>();


    public Bluetooth(Context ctx, BluetoothManager manager, BluetoothCallback cb) {
        mBluetoothManager = manager;
        mBluetoothAdapter = manager.getAdapter();
        callback = cb;
        context = ctx;
        // Start periodic reconnection attempts.
        //handler.postAtTime(mAutoReconnectionHandler, System.currentTimeMillis()+CONN_RETRY_INTERVAL);
        handler.postDelayed(mAutoReconnectionHandler, CONN_RETRY_INTERVAL);
    }

    private Runnable mAutoReconnectionHandler = new Runnable(){
        public void run() {
            lock.lock();
            try {
                if(activated) {
                    for (Map.Entry<BluetoothDevice, State> e : state.entrySet()) {
                        if (e.getValue().getGatt() == null) {
                            e.getValue().setExpectDisconnect(false);
                            e.getValue().setGatt(createGatt(e.getKey()));
                        } else if(e.getValue().getTxChar() != null) {
                            // write any pending writes.
                            write(e.getKey(), e.getValue().getTxChar());
                        }
                    }
                }
            } finally {
                lock.unlock();
                handler.postDelayed(mAutoReconnectionHandler, CONN_RETRY_INTERVAL);
            }
        }
    };

    public boolean isDisabled() {
        return mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled();
    }

    public void activate() {
        lock.lock();
        try {
            activated = true;
            for(BluetoothDevice d : listDevices()) {
                State s = state.get(d);
                if(s == null) {
                    s = new State();
                    state.put(d, s);
                }
                s.setExpectDisconnect(false);
                if (s.getGatt() == null) {
                    s.setGatt(createGatt(d));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public List<BluetoothDevice> getDevices() {
        lock.lock();
        try {
            List<BluetoothDevice> l  = new ArrayList<>();
            for(BluetoothDevice d : state.keySet()) {
                l.add(d);
            }
            return l;
        } finally {
            lock.unlock();
        }

    }

    private List<BluetoothDevice> listDevices() {
        ArrayList<BluetoothDevice> mylist = new ArrayList<BluetoothDevice>();
        List<BluetoothDevice> devices = mBluetoothManager.getDevicesMatchingConnectionStates(
                BluetoothProfile.GATT, new int[]{
                        BluetoothProfile.STATE_CONNECTED,
                        BluetoothProfile.STATE_CONNECTING,
                        BluetoothProfile.STATE_DISCONNECTED,
                        BluetoothProfile.STATE_DISCONNECTING
                });
        for (BluetoothDevice device : devices) {
            if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE) {
                if (device.getAddress().startsWith("03:80:E1:00")) {
                    mylist.add(device);
                }
            }
        }
        return mylist;
    }

    public BluetoothGatt createGatt(BluetoothDevice d)  {
        return d.connectGatt(context, false, gattCallback);
    }

    public void passivate() {
        lock.lock();
        try {
            activated = false;
            for(Map.Entry<BluetoothDevice, State> e : state.entrySet()) {
                e.getValue().setExpectDisconnect(true);
                close(e.getKey());
            }
        } finally {
            lock.unlock();
        }
    }

    private void close(BluetoothDevice d) {
        State s = state.get(d);
        if (s != null) {
            s.setTxChar(null);
            if (s.getGatt() != null) {
                s.getGatt().close();
                s.setGatt(null);
            }
        }
    }

    public void write(BluetoothDevice d, int val) {
        lock.lock();
        try {
            State s = state.get(d);
            if(s != null) {
                s.setExpectDisconnect(false);
                s.setTowrite(val);

                if (s.getGatt() == null) {
                    s.setGatt(createGatt(d));
                } else if (s.getTxChar() != null) { // connected
                    write(d, s.getTxChar());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void write(BluetoothDevice d, BluetoothGattCharacteristic c) {
        State s = state.get(d);
        if(s != null) {
            if (s.getTowrite() != s.getWritten()) {
                c.setValue(s.getTowrite(), BluetoothGattCharacteristic.FORMAT_UINT32, 0);
                if (s.getGatt().writeCharacteristic(c)) {
                    s.setWritten(s.getTowrite());
                }
            }
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.i("onConnectionStateChange", "Status: " + status);
            switch (newState) {
                case BluetoothProfile.STATE_CONNECTED:
                    Log.i("gattCallback", "STATE_CONNECTED");
                    gatt.discoverServices();
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    Log.i("gattCallback", "STATE_DISCONNECTED");
                    lock.lock();
                    try {
                        State s = state.get(gatt.getDevice());
                        if(s != null) {
                            if (!s.isExpectDisconnect()) { // unexpected disconnect
                                s.setExpectDisconnect(false);
                                callback.onMissing(gatt.getDevice());
                            }
                            close(gatt.getDevice());
                        }
                    } finally {
                        lock.unlock();
                    }
                    break;
                default:
                    Log.e("gattCallback", "STATE_OTHER");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            List<BluetoothGattService> services = gatt.getServices();
            for (BluetoothGattService service : services) {
                Log.i("SERVICE:", service.getUuid().toString());
                if (service.getUuid().equals(SERVICE_UUID.getUuid())) {
                    for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                        final int charaProp = c.getProperties();
                        if ((charaProp & BluetoothGattCharacteristic.PROPERTY_READ) > 0) {// notifiable characteristic
                            lock.lock();
                            try {
                                State s = state.get(gatt.getDevice());
                                // enable local notification callback to trigger
                                gatt.setCharacteristicNotification(c, true);
                                if(s != null) {
                                    if (!gatt.readCharacteristic(c)) {
                                        close(gatt.getDevice());
                                    }
                                }
                            } finally {
                                lock.unlock();
                            }
                        }
                        if ((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {// writable characteristic
                            // write here any pending writes.
                            lock.lock();
                            try {
                                State s = state.get(gatt.getDevice());
                                if(s != null) {
                                    s.setTxChar(c); // save txChar for future writes
                                }
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.i("onCharacteristicChanged", characteristic.getUuid().toString());
            if(characteristic.getValue() != null) {
                callback.onChange(gatt.getDevice(), characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT32, 0));
            } else {
                callback.onMissing(gatt.getDevice());
                lock.lock();
                try{
                    close(gatt.getDevice());
                } finally {
                    lock.unlock();
                }
            }
        }
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                         int status) {
            Log.i("onCharacteristicRead", characteristic.getUuid().toString());
            if(characteristic.getValue() != null) {
                // enable futher notifications for this characteristic
                characteristic.getDescriptors().get(0).setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!gatt.writeDescriptor(characteristic.getDescriptors().get(0))) {
                    close(gatt.getDevice());
                }
                // trigger change callback fur currently received value
                callback.onChange(gatt.getDevice(), characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT32, 0));
            } else {
                callback.onMissing(gatt.getDevice());
                lock.lock();
                try{
                    close(gatt.getDevice());
                } finally {
                    lock.unlock();
                }
            }
        }
    };
}
