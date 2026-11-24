package com.nforetek.bt.aidl;

interface INfCallbackBluetooth {
    void onBluetoothServiceReady();
    void onAdapterStateChanged(int previousState, int state);
    void onAdapterDiscoverableModeChanged(int mode, int timeout);
    void onAdapterDiscoveryStarted();
    void onAdapterDiscoveryFinished();
    void retPairedDevices(int count, in String[] names, in String[] addresses, in int[] states, in byte[] types);
    void onDeviceFound(String name, String address, byte type);
    void onDeviceBondStateChanged(String address, String name, int state, int origin);
    void onDeviceUuidsUpdated(String address, String uuids, int status);
    void onLocalAdapterNameChanged(String name);
    void onDeviceOutOfRange(String address);
    void onDeviceAclDisconnected(String address);
    void onBtRoleModeChanged(int mode);
    void onBtAutoConnectStateChanged(String address, int state, int reason);
}
