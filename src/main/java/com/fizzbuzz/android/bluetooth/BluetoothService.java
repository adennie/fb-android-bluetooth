package com.fizzbuzz.android.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.IBinder;
import com.fizzbuzz.android.dagger.InjectingApplication;
import com.fizzbuzz.android.dagger.InjectingService;
import dagger.Module;
import dagger.Provides;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class BluetoothService extends InjectingService {
    public static final String INTENT_ACTION_LISTEN_FOR_BLUETOOTH_CONNECTIONS = "com.fizzbuzz.bluetooth.LISTEN_FOR_BLUETOOTH_CONNECTIONS";
    public static final String INTENT_ACTION_CONNECT_TO_BLUETOOTH_DEVICE = "com.fizzbuzz.bluetooth.CONNECT_TO_BLUETOOTH_DEVICE";

    private enum State {
        STATE_NONE,
        STATE_LISTENING,
        STATE_CONNECTING,
        STATE_CONNECTED
    }

    // https://www.bluetooth.org/en-us/specification/assigned-numbers-overview/service-discovery
    private static UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final Logger mLogger = LoggerFactory.getLogger(LoggingManager.TAG);
    @Inject BluetoothAdapter mBtAdapter;
    private State mState;
    private ListeningThread mListeningThread;
    private ConnectingThread mConnectingThread;
    private ConnectedThread mConnectedThread;

    @Override
    public void onCreate() {
        super.onCreate();

        ((InjectingApplication) getApplicationContext()).getObjectGraph().inject(this);

        //TODO: call startForeground
    }

    @Override
    protected List<Object> getModules() {
        return Arrays.<Object>asList(new BluetoothSeviceModule());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        // if the intent is null, it means the service was restarted after being killed by the system
        if (intent.getAction().equals(INTENT_ACTION_LISTEN_FOR_BLUETOOTH_CONNECTIONS)){
            reset();
            listen();
        }else if (intent.getAction().equals(INTENT_ACTION_CONNECT_TO_BLUETOOTH_DEVICE)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            reset();
            connect(device);
        }

        // if the system kills the service to reclaim resources, it should restart it later,
        // resending the intent.
        // see http://developer.android.com/reference/android/app/Service.html#START_REDELIVER_INTENT
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // binding support not implemented
    }

    public synchronized void connect(BluetoothDevice device) {
        // if a thread is already in the process of connecting to another device, cancel that other one
        if (getServiceState() == State.STATE_CONNECTING) {
            if (mConnectingThread != null) {
                mLogger.debug("BluetoothService.connect: canceling previous connecting thread");
                mConnectingThread.cancel();
                mConnectingThread = null;
            }
        }

        // if we're already connected to another device, disconnect and end that thread
        if (mConnectedThread != null) {
            mLogger.debug("BluetoothService.connect: canceling previous connected thread");
            mConnectedThread.cancel(); // this will close the socket, which will cause run() to exit
            mConnectedThread = null;
        }

        setServiceState(State.STATE_CONNECTING);

        mLogger.debug("BluetoothService.connect: starting new connecting thread");
        mConnectingThread = new ConnectingThread(device);
        mConnectingThread.start();
    }

    protected synchronized void onConnectionFailed(final BluetoothDevice device) {
        reset(); // reset over
    }

    protected void onInboundConnectionRequestReceived(final BluetoothDevice device) {
    }

    protected void onBeforeConnect(final BluetoothDevice device) {

    }

    protected synchronized void onConnected(BluetoothSocket socket) {
        setServiceState(State.STATE_CONNECTED);

        mLogger.debug("BluetoothService.onConnected: starting new connected thread");
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
    }

    protected void runWhileConnected(final BluetoothSocket socket) {
        // invoked by ConnectedThread.run().  To be implemented by subclass.
    }

    private synchronized State getServiceState() {
        return mState;
    }

    private synchronized void setServiceState(State state) {
        mLogger.debug("BluetoothService.setServiceState: old state = {}, new state = {}", mState, state);
        mState = state;
    }

    private synchronized void reset() {
        // if there's a thread that's currently connecting to a device, cancel it
        if (mConnectingThread != null) {
            mLogger.debug("BluetoothService.reset: canceling previous connecting thread");
            mConnectingThread.cancel();
            mConnectingThread = null;
        }

        // if we're already connected to another device, disconnect and end that thread
        if (mConnectedThread != null) {
            mLogger.debug("BluetoothService.reset: canceling previous connected thread");
            mConnectedThread.cancel(); // this will close the socket, which will cause run() to exit
            mConnectedThread = null;
        }

        setServiceState(State.STATE_NONE);
    }

    private synchronized void listen() {

        setServiceState(State.STATE_LISTENING);

        if (mListeningThread == null) {
            mLogger.info("BluetoothService.listen: starting listening thread");
            mListeningThread = new ListeningThread("Bluetooth Inspector", SPP_UUID);
            mListeningThread.start();
        }
    }

    private synchronized void onInboundConnection(BluetoothSocket socket) {
        // cancel the listening thread, because we only want to connect to one device at a time
        mListeningThread.cancel();
        setServiceState(State.STATE_CONNECTED);
    }

    private synchronized void stop() {
        if (mListeningThread != null) {
            mListeningThread.cancel();
            mListeningThread = null;
        }
        setServiceState(State.STATE_NONE);
    }

    @Module(injects = {BluetoothService.class})
    public static class BluetoothSeviceModule {
        public BluetoothSeviceModule() {
        }

        @Provides
        @Singleton
        public BluetoothAdapter provideBluetoothAdapter() {
            return BluetoothAdapter.getDefaultAdapter();
        }
    }

    private class ListeningThread extends Thread {
        private final UUID mHostUUID;
        private final String mHostName;
        private BluetoothServerSocket mServerSocket;

        private ListeningThread(String hostName, UUID hostUUID) {
            mHostName = hostName;
            mHostUUID = hostUUID;
        }

        @Override
        public void run() {
            setName(mHostName);

            // establish the server socket
            try {
                mLogger.debug("BluetoothService$ListeningThread.run: opening server socket");
                mServerSocket = mBtAdapter.listenUsingRfcommWithServiceRecord(mHostName, mHostUUID);
            } catch (IOException e) {
                mLogger.error("BluetoothService$ListeningThread.run: caught IOException from " +
                        "listenUsingRfcommWithServiceRecord() call", e);
                return;
            }

            BluetoothSocket socket = null;

            while (getServiceState() == BluetoothService.State.STATE_LISTENING) {
                try {
                    // listen on the server socket.  Keep in mind that the state can change while we're waiting.
                    // Note that when this call returns a BluetoothSocket, the socket is _already_ connected.
                    mLogger.debug("BluetoothService$ListeningThread.run: calling accept()");
                    socket = mServerSocket.accept(); // blocking call

                    mLogger.debug("BluetoothService$ListeningThread.run: accepted remote connection, " +
                            "current state = " + getServiceState().toString());

                    onInboundConnectionRequestReceived(socket.getRemoteDevice());
                } catch (IOException e) {
                    mLogger.error("BluetoothService$ListeningThread.run: caught IOException from server socket " +
                            "accept() call", e);
                    break;
                }

                if (getServiceState() == BluetoothService.State.STATE_LISTENING) {
                    onInboundConnection(socket);
                } else {
                    mLogger.debug("BluetoothService$ListeningThread.run: current state is not " +
                            "STATE_LISTENING, so closing socket");
                    try {
                        socket.close();
                    } catch (IOException e) {
                        mLogger.error("BluetoothService$ListeningThread.run: caught IOException from " +
                                "socket close() call", e);
                        break;
                    }
                }
            }
            mLogger.debug("BluetoothService$ListeningThread.run: exiting");
            cancel();
        }

        private synchronized void cancel() {
            try {
                if (mServerSocket != null) {
                    mLogger.debug("BluetoothService$ListentingThread.cancel: closing server socket");
                    mServerSocket.close();
                    mServerSocket = null;
                }
            } catch (IOException e) {
                mLogger.error("BluetoothService$ListeningThread.cancel: caught IOException when closing server " +
                        "socket", e);
            }
        }
    }

    private class ConnectingThread extends Thread {
        private final BluetoothDevice mDevice;
        private BluetoothSocket mSocket;

        public ConnectingThread(BluetoothDevice device) {
            mDevice = device;
        }

        public synchronized void cancel() {
            try {
                if (mSocket != null) {
                    mLogger.debug("BluetoothService$ConnectingThread.cancel: closing socket");
                    mSocket.close();
                    mSocket = null;
                }
            } catch (IOException e) {
                mLogger.error("BluetoothService$ConnectingThread.cancel: caught IOException when closing " +
                        "socket", e);
            }
        }

        @Override
        public void run() {

            mLogger.debug("BluetoothService$ConnectingThread.run: posting ObdConnectingEvent");
            onBeforeConnect(mDevice);
            try {
                mLogger.debug("BluetoothService$ConnectingThread.run: opening socket");
                mSocket = mDevice.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch (IOException e) {
                mLogger.error("BluetoothService$ConnectingThread.run: caught IOException when creating socket", e);
                onConnectionFailed(mDevice);
                return;
            }

            try {
                mLogger.debug("BluetoothService$ConnectingThread.run: connecting to device");
                mSocket.connect();
                mLogger.debug("BluetoothService$ConnectingThread.run: connected successfully");
                mLogger.debug("BluetoothService$ConnectingThread.run: posting ObdConnectedEvent");
                onConnected(mSocket);
            } catch (IOException e) {
                mLogger.error("BluetoothService$ConnectingThread.run: caught IOException when connecting to " +
                        "socket", e);
                try {
                    mSocket.close();
                } catch (IOException e2) {
                    mLogger.error("BluetoothService$ConnectingThread.run: caught IOException when closing " +
                            "socket", e2);
                }
                onConnectionFailed(mDevice);
            }
            mLogger.debug("BluetoothService$ConnectingThread.run: exiting");
        }
    }

    private class ConnectedThread extends Thread {
        private BluetoothSocket mSocket;
        private InputStream mInStream;
        private OutputStream mOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            mSocket = socket;
        }

        public BluetoothSocket getSocket() {
            return mSocket;
        }

        public synchronized void cancel() {
            try {
                if (mSocket != null) {
                    mLogger.debug("BluetoothService$ConnectedThread.cancel: closing socket");
                    mSocket.close();
                    mSocket = null;
                }
            } catch (IOException e) {
                mLogger.error("BluetoothService$ConnectedThread.cancel: caught IOException when closing socket", e);
            }
        }


        @Override
        public void run() {
            runWhileConnected(mSocket); // implementation supplied by BluetoothService subclass
            cancel();
        }


    }
}
