package geyer.sensorlab.topofmind;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;
import com.google.gson.Gson;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;

import at.favre.lib.armadillo.Armadillo;
import timber.log.Timber;

public class CytonService extends Service {

    /**
     * ******************************GLOBAL VARIABLES**********************
     */


    //Classes
    private SigError sigError;
    private ImpedanceTest impedanceTest;
    private QRInstructions qrInstructions;
    private DataCollection dataCollection;
    private Manager manager;

    //Connection components
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbSerialDevice serialDevice;
    private LinkedList<EEGChannel> eegChannels;

    //Widely used components
    private SharedPreferences servicePreferences, securePreferences;
    private Handler handler;
    private NotificationManager notificationManager;
    private BroadcastReceiver broadcastReceiver;

    //Manager components
    private int state;
    private boolean batteryNotePresent;
    private final int managerRecheckDuration = 5*1000;
    private boolean testBattery;
    private int onGoingDataCollecting;
    private int minutesUntilDataAssessment = 0;

    /**
     * ******************************INITIALIZATION************************
     */


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(Constant.CYTON_SERVICE_ID, generateNotification());
        statelessInitialization();
        return START_STICKY;
    }

    private Notification generateNotification() {
        final String
                channelID = "CytonServiceChannel",
                contentTitle = "Cyton operations",
                contentText = "Cyton is currently recording data";


        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = generateChannel();
            if (manager != null && channel != null) {
                channel.enableLights(false);
                try {
                    manager.createNotificationChannel(channel);
                } catch (IllegalArgumentException e) {

                    Timber.e(e.getLocalizedMessage());
                }
            } else {
                if (manager == null) {
                    Timber.e("Notification manager equals null");
                } else {
                    Timber.e("Channel equals null");
                }
            }
        }

        Resources resources = getResources();

        final int icon = R.drawable.ic_service;

        NotificationCompat.Builder nfc = new NotificationCompat.Builder(this, channelID)
                .setSmallIcon(icon)
                .setLargeIcon(BitmapFactory.decodeResource(resources, icon))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET) //This hides the notification from lock screen
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setOngoing(true)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText).setBigContentTitle(contentTitle))
                .setWhen(System.currentTimeMillis());

        return nfc.build();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private NotificationChannel generateChannel(){
        NotificationChannel channel = new NotificationChannel("CytonServiceChannel", "Cyton Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null,null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

        channel.enableVibration(false);
        channel.setShowBadge(true);

        channel.setDescription("Notification channel for background operations associated with cyton data collection");
        return channel;
    }

    private void statelessInitialization() {
        initializeSharedPreferences();
        initializeClasses();
        sigError.reportUpdate("Service onStartCommand called");
        registerReceivers();
        initializeNotificationChannel();
        initializeManagerComponents();
        informConnectOfInitialization();
    }

    private void initializeClasses() {
        sigError = new SigError(getSharedPreferences("errorLog", MODE_PRIVATE));
        manager = new Manager();
    }

    private int establishEEGChannels(String c0, String c1, String c2, String c3, String c4, String c5, String c6, String c7) {
        Gson gson = new Gson();

        if (!c0.equals("null")){
            eegChannels.add(gson.fromJson(c0, EEGChannel.class));
        }
        if(!c1.equals("null")){
            eegChannels.add(gson.fromJson(c1, EEGChannel.class));
        }
        if (!c2.equals("null")){
            eegChannels.add(gson.fromJson(c2, EEGChannel.class));
        }
        if(!c3.equals("null")){
            eegChannels.add(gson.fromJson(c3, EEGChannel.class));
        }
        if (!c4.equals("null")){
            eegChannels.add(gson.fromJson(c4, EEGChannel.class));
        }
        if(!c5.equals("null")){
            eegChannels.add(gson.fromJson(c5, EEGChannel.class));
        }
        if (!c6.equals("null")){
            eegChannels.add(gson.fromJson(c6, EEGChannel.class));
        }
        if(!c7.equals("null")){
            eegChannels.add(gson.fromJson(c7, EEGChannel.class));
        }

        sigError.reportUpdate("Number of channels:" + eegChannels.size());

        servicePreferences.edit().putInt("Channels", eegChannels.size()).apply();

        return eegChannels.size();
    }

    //BROADCAST RECEIVERS
    private BroadcastReceiver localBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras != null){
                String purpose = extras.getString("purpose");
                if (purpose != null) {
                    if(purpose.equals("change channel")){
                        int channel = extras.getInt("channel");
                        double connectivity = extras.getDouble("connectivity");
                        servicePreferences.edit().putInt("channel", 1+servicePreferences.getInt("channel", 1)).apply();
                        servicePreferences.edit().putString("channel" + channel, Double.toString(connectivity)).apply();
                        startImpedance();
                    }else if(purpose.equals("collateFromMain")){
                        sigError.reportUpdate("collateFromMain called");
                        securePreferences.edit().putString("json instructions", intent.getStringExtra("instructions")).apply();
                        Gson gson = new Gson();
                        determineChannels(gson.fromJson(intent.getStringExtra("instructions"), QRInstructions.class));
                    }
                }
            }
        }
    };

    void determineChannels(QRInstructions instructions){
        eegChannels = new LinkedList<>();
        qrInstructions = instructions;

        int numchannels = establishEEGChannels(instructions.c0,
                instructions.c1,
                instructions.c2,
                instructions.c3,
                instructions.c4,
                instructions.c5,
                instructions.c6,
                instructions.c7);

        minutesUntilDataAssessment = instructions.frequencyOfImpedance;

        impedanceTest = new ImpedanceTest(sigError, this);
        try {
            dataCollection = new DataCollection(sigError, numchannels);
        } catch (IOException e) {
            sigError.reportError(e.getMessage(),e.getStackTrace());
        }
        establishNextStep();
    }

    private void registerReceivers() {

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle extras = intent.getExtras();
                if (extras != null){
                    String purpose = extras.getString("purpose");
                    if (purpose != null) {
                        if(purpose.equals("restart impedance test")){
                            sigError.reportUpdate("Restarting the Impedance test");
                            startImpedance();
                        }
                        if (purpose.equals("retryBatteryDeleted")){
                            batteryNotePresent = false;
                        }
                    }
                }
            }
        };

        LocalBroadcastManager.getInstance(this).registerReceiver(localBroadcastReceiver, new IntentFilter("toCytonService"));
        IntentFilter intentFilter = new IntentFilter("cytonService");
        registerReceiver(broadcastReceiver, intentFilter);
    }

    private void initializeSharedPreferences() {
        servicePreferences= getSharedPreferences("service", MODE_PRIVATE);
        securePreferences = Armadillo.create( this, "cytonService").encryptionFingerprint(this).build();
    }

    private void initializeNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setUpNotificationChannel();
        }
    }

    private void initializeManagerComponents() {
        handler = new Handler();
        updateState(Constant.NOT_INITIALIZING, new Throwable().getStackTrace()[0].getLineNumber());
        handler.postDelayed(manager, managerRecheckDuration);
        batteryNotePresent = false;
        onGoingDataCollecting = 0;
        testBattery = false;
    }

    private int establishState() {
        if (usbManager == null){
            return 1;
        }else if (usbDevice == null){
            return 2;
        }else if (serialDevice == null){
            return 3;
        }else{
            return 4;
        }
    }

    private void informConnectOfInitialization() {
        Intent intent = new Intent("toConnect");
        intent.putExtra("purpose", "service has been initialized");
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        sigError.reportUpdate("update sent to connect");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void setUpNotificationChannel() {
        CharSequence name = "connectivityChannel";
        String description = "Relaying information on the connectivity";
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel notificationChannel = new NotificationChannel("Poor_notification_connection", name, importance);
        notificationChannel.setDescription(description);
        notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.createNotificationChannel(notificationChannel);
        }else{
            sigError.reportUpdate("Could not create poor notification connection channel");
        }
    }


    private final IBinder binder = new CytonService.LocalBinder();
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    class LocalBinder extends Binder {
        CytonService getService(){return CytonService.this;}
    }


    /**
     * /*******************************ESTABLISH CONNECTION**************************************
     */

    //STAGE 1

    void inheritUSB(UsbDevice usbDevice, UsbManager usbManager) throws Exception {
        sigError.reportUpdate("UsbDevice and manager supplied");
        this.usbDevice = usbDevice;
        this.usbManager = usbManager;

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(usbPermissionReceiver, intentFilter);

        initializeSerialDevice();
    }

    private BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())){
                sigError.reportUpdate("USB became detached");
                notifyAboutProblem(true);
                serialDevice = null;
                updateState(Constant.NOT_INITIALIZING, new Throwable().getStackTrace()[0].getLineNumber());
                handler.removeCallbacks(manager);
            }
            if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())){
                notificationManager.cancel(1);
                handler.postDelayed(manager, managerRecheckDuration);
                reinitializeUSB();
            }
        }
    };

    void reinitializeUSB(){
        sigError.reportUpdate("State: " + establishState());
        try {
            initializeSerialDevice();
        } catch (Exception e) {
            sigError.reportError(e.getLocalizedMessage(),e.getStackTrace());
        }
    }

    //STAGE 2

    private void initializeSerialDevice() throws Exception {

        sigError.reportUpdate("Initializing serial device");

        if(!UsbSerialDevice.isSupported(usbDevice)){
            throw new Exception("device does not support serial usb devices");
        }

        if(usbManager!= null){
            sigError.reportUpdate("Usb manager != null");
        }else{
            sigError.reportUpdate("Usb manager == null");
            usbManager = (UsbManager) getSystemService(USB_SERVICE);
        }

        UsbDeviceConnection deviceConnection;
        if (usbManager != null) {
            deviceConnection = usbManager.openDevice(usbDevice);
        }else{
            throw new Exception("USBManager is null");
        }

        serialDevice = UsbSerialDevice.createUsbSerialDevice(usbDevice, deviceConnection);

        serialDevice.open();
        serialDevice.setBaudRate(115200);
        serialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
        serialDevice.setParity(UsbSerialInterface.PARITY_NONE);
        serialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_RTS_CTS);
        serialDevice.setStopBits(UsbSerialInterface.STOP_BITS_2);

        if (serialDevice != null) {
            sigError.reportUpdate("Serial device != null");
            establishNextStep();
        }
    }

    private void establishNextStep() {
        sigError.reportUpdate("instructions in cyton service are: " + securePreferences.getString("instruction", "not instruction"));
        boolean haveInstructions = !securePreferences.getString("json instructions", "not instruction").equals("not instruction");
        if(haveInstructions){
            initializeConnectionWithCyton();
        }else{
            reportToConnect("finish activity");
            reportBackToMain("request description", "na");
        }
    }

    void initializeConnectionWithCyton(){
        //Set up callback from cyton
        UsbSerialInterface.UsbReadCallback cytonCallBack = this::readDataFromCyton;
        serialDevice.read(cytonCallBack);
        confirmConnectionWithCyton();
    }

    void confirmConnectionWithCyton(){

        reportBackToMain("inform change", "testing connection with cyton");
        updateState(Constant.INITIALIZING, new Throwable().getStackTrace()[0].getLineNumber());
        sigError.reportUpdate("Testing a connection with cyton");
        serialDevice.write("d".getBytes());
        if(testBattery){
            handler.postDelayed(testConnection,500);
        }


    }

    private void readDataFromCyton(byte[] data){
        switch (state){
            case Constant.INITIALIZING:
                testingConnection(String.valueOf(StandardCharsets.UTF_8.decode(ByteBuffer.wrap(data))));
                break;
            case Constant.IMPEDANCE:
                onGoingDataCollecting = 0;

                impedanceTest.supplyData(data);
                break;
            case Constant.DATA_COLLECTING:
                onGoingDataCollecting = 0;
                dataCollection.storeRawData(data);
                break;
        }
    }

    private void testingConnection(String fromCyton) {
        testBattery = false;
        //Device did not respond to call
        if (fromCyton.contains("Device failed to poll")){
            sigError.reportUpdate("no response from cyton");
            if (!batteryNotePresent){
                notifyAboutProblem(false);
            }
        }
        //Device did respond to call
        else if (fromCyton.contains("default")){
            updateState(Constant.IMPEDANCE, new Throwable().getStackTrace()[0].getLineNumber());
            onGoingDataCollecting = 0;
            startImpedance();
            onGoingDataCollecting = 0;

            //report connection to cyton
            sigError.reportUpdate("Communicating with cyton");

            //remove a notification about issue with the battery
            batteryNotePresent= false;
            notificationManager.cancel(2);

            //start impedance testing
            reportBackToMain("inform change", "ready to test impedance");

        }else if(fromCyton.equals("null")){
            sigError.reportUpdate("test connection runnable is the problem");
            if (!batteryNotePresent){
                notifyAboutProblem(false);
            }

        }else if (fromCyton.length() > 100){
            serialDevice.write("s".getBytes());
            initializeConnectionWithCyton();
        }else{
            sigError.reportUpdate("cyton connection was not identifiable");
            try {
                if (serialDevice == null){
                    initializeSerialDevice();
                }
            } catch (Exception e) {
                sigError.reportError(e.getLocalizedMessage(),e.getStackTrace());
            }
            if (!batteryNotePresent){
                notifyAboutProblem(false);
            }
        }
    }

    private Runnable testConnection = () -> testingConnection("null");

    /**
     * *****************************MANAGER***************************************
     */

    class Manager implements Runnable {
        @Override
        public void run() {
            sigError.reportUpdate("manager reviewing");
            if (state != Constant.DATA_COLLECTING){
                handler.removeCallbacks(testImpedance);
            }
            switch (state){
                case Constant.NOT_INITIALIZING:

                    break;
                case Constant.INITIALIZING:
                    testBattery = true;
                    confirmConnectionWithCyton();
                    break;
                case Constant.IMPEDANCE:
                    sigError.reportUpdate("Assessing impedance - onGoingDataCollecting: " + onGoingDataCollecting);
                    if (onGoingDataCollecting > 1){
                        updateState(Constant.INITIALIZING, new Throwable().getStackTrace()[0].getLineNumber());
                        testBattery = true;
                        confirmConnectionWithCyton();
                    }
                    onGoingDataCollecting ++;
                    break;
                case Constant.DATA_COLLECTING:
                    sigError.reportUpdate("Assessing data collection - onGoingDataCollecting: " + onGoingDataCollecting);

                    if (onGoingDataCollecting > 1){

                        updateState(Constant.INITIALIZING, new Throwable().getStackTrace()[0].getLineNumber());
                        testBattery = true;
                        confirmConnectionWithCyton();
                    }
                    onGoingDataCollecting++;
                    break;

            }
            restartManager();
        }
    }

    void updateState(int state, int line){
        this.state = state;
        sigError.reportUpdate("State changed to: " + state + " change was called from line: " + line);
    }

    void restartManager(){
        handler.postDelayed(manager, managerRecheckDuration);
    }

    /**
     * *******************************CONDUCTING IMPEDANCE TEST*********************
     */

    void startImpedance(){
        updateState(Constant.IMPEDANCE, new Throwable().getStackTrace()[0].getLineNumber());

        sigError.reportUpdate("impedance test to begin");
        reportBackToMain("inform change", "impedance test starting");
        reportToConnect("finish activity");
        try {
            performImpedanceTest();
        } catch (InterruptedException e) {
            sigError.reportError(e.getMessage(),e.getStackTrace());
        }
    }



    /**
     * Initiate actions for performance
     *
     * The impedance test operates in the following fashion, if the service is ready to function:
     * the currentState is equal to 4. Then we begin with reporting that the EEG being carried out. The connectivity level is reinitialized
     * If this is not the first iteration through this method then the cyton will be made to stop sending data
     *
     * A handler is set up to assess if the impedance is underway, this happens every second.
     *
     * If all open channels are checked then the iteration through this method is concluded, with setting the channel back to 1 and the results are reported
     *
     * Otherwise, we reinitialise the underlying impedance score.
     * Start the recording of the data.
     * Select the channel.
     *
     * Change the state to running impedance and then begin to data collection
     *
     */

    void performImpedanceTest() throws InterruptedException {
        reportBackToMain("inform change", "performing an impedance test.");
        int channel = servicePreferences.getInt("channel", 1);

        int currentState = establishState();
        if(currentState == 4){
            updateState(Constant.IMPEDANCE, new Throwable().getStackTrace()[0].getLineNumber());

            if (channel == 1){
                notificationManager.cancel(0);
                sigError.reportUpdate("EEG is ready to perform impedance test");
            }else{
                serialDevice.write("s".getBytes());
            }

            sigError.reportUpdate("num of channels: " + servicePreferences.getInt("Channels", 0));
            sigError.reportUpdate("current channel: " + channel);
            if (channel >= 1 + (servicePreferences.getInt("Channels", 0))){
                servicePreferences.edit().putInt("channel", 1).apply();
                updateState(Constant.NOT_INITIALIZING, new Throwable().getStackTrace()[0].getLineNumber());
                reportTheConnectivityLevel();
                return;
            }

            impedanceTest.determineChannel(channel);

            Thread.sleep(100);
            serialDevice.write("d".getBytes());
            Thread.sleep(100);
            serialDevice.write("<".getBytes());
            Thread.sleep(100);
            serialDevice.write(("z" + channel + "10Z").getBytes());

            Thread.sleep(100);
            serialDevice.write("b".getBytes());

        }else{
            sigError.reportUpdate("EEG could not perform impedance test because state was: " + currentState);
        }
    }

    private void reportTheConnectivityLevel() {
        ArrayList<String> unconnectedChannels = new ArrayList<>();
        sigError.reportUpdate("connectivity identified");
        try {
            ExternalDataStorage externalDataStorage = new ExternalDataStorage("Impedance.txt", servicePreferences.getInt("Channels", 0));
            externalDataStorage.writeToFile("Impedance test concluded at: " + System.currentTimeMillis() +"\n");

            for (int i = 0; i < servicePreferences.getInt("Channels", 0) ; i++){
                int channel = i + 1;

                String connectivity = servicePreferences.getString("channel" + channel, "0");

                double connect = Double.parseDouble(connectivity);

                if (connect == 0){
                    sigError.reportError("Problem with the impedance test");
                }

                String toReport = "Channel: " + channel + " - connectivity: " + connect +"\n";
                externalDataStorage.writeToFile(toReport);

                if (connect > qrInstructions.valueOfImpedance){
                    unconnectedChannels.add(eegChannels.get((channel-1) ).location);
                }
            }

            externalDataStorage.closeFile();

        } catch (FileNotFoundException e) {
            sigError.reportError(e.getMessage(),e.getStackTrace());
        } catch (IOException e) {
            sigError.reportError(e.getMessage(),e.getStackTrace());
        }

        if (unconnectedChannels.size() > 0){
            NotifyAboutPoorConnectivity(unconnectedChannels);
        }else{
            collectData();
        }
    }

    private void NotifyAboutPoorConnectivity(ArrayList<String> unconnectedChannels) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "Poor_notification_connection")
                .setSmallIcon(R.drawable.ic_service)
                .setContentText("Connection")
                .setContentText("Poor connection with channels in areas: " + unconnectedChannels)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(new long[]{1000,1000,1000, 1000,1000,1000})
                .setLights(Color.RED, 3000,3000);

        Intent intent = new Intent("cytonService");
        intent.putExtra("purpose", "restart impedance test");
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 10101, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        builder.setContentIntent(pendingIntent);
        builder.setDeleteIntent(pendingIntent);
        notificationManager.notify(0, builder.build());
    }

    /**
     * *******************************COLLECTING DATA******************************
     */

    void collectData(){
        reportBackToMain("inform change", "can start to collect data.");
        updateState(Constant.DATA_COLLECTING, new Throwable().getStackTrace()[0].getLineNumber());
        onGoingDataCollecting = 0;
        beginCollectingData();
        handler.postDelayed(testImpedance, 1000*60*minutesUntilDataAssessment);
    }

    void beginCollectingData(){
        reportBackToMain( "inform change","Collecting data");
        sigError.reportUpdate("Starting data collection");
        updateState(Constant.DATA_COLLECTING, new Throwable().getStackTrace()[0].getLineNumber());
        try {

            serialDevice.write("d".getBytes());
            Thread.sleep(100);
            serialDevice.write("<".getBytes());
            Thread.sleep(100);
            dataCollection.addTimeStamp();
            serialDevice.write("b".getBytes());

        } catch (Exception e) {
            sigError.reportError(e.getMessage(),e.getStackTrace());
        }
    }

    private Runnable testImpedance = new Runnable() {
        @Override
        public void run() {
            onGoingDataCollecting = 0;
            startImpedance();
        }
    };

    /**
     * *******************************INFORMING ABOUT SERVICE STATE*****************
     */

    private void notifyAboutProblem (boolean usb){
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "Poor_notification_connection")
                .setSmallIcon(R.drawable.ic_service)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(new long[]{1000,1000,1000, 1000,1000,1000})
                .setLights(Color.RED, 3000,3000);

        if (usb){
            builder.setContentText("The USB seems to have disconnected, please reconnect the usb: ");
            notificationManager.notify(1,builder.build());
        }else{
            batteryNotePresent = true;
            builder.setContentText("There seems to be a problem connecting to the brain scanner, can you please check the battery hasn't fallen out.");

            Intent intent = new Intent("cytonService");
            intent.putExtra("purpose", "retryBatteryDeleted");


            notificationManager.notify(2, builder.build());
        }
    }

    private void reportBackToMain(String purpose, String change){
        sigError.reportUpdate("to update main with: "+ purpose);
        Intent intent = new Intent("Main_Receiver");
        intent.putExtra("purpose", purpose);
        intent.putExtra("change", change);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void reportToConnect (String info){
        sigError.reportUpdate("to update connect with: " + info);
        Intent intent = new Intent("toConnect");
        intent.putExtra("purpose", info);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }


    /**
     * ********************************HANDLING THE END OF THE SERVICE*****************
     */

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(manager);
    }
}
