package com.nforetek.bt.aidl;

import com.nforetek.bt.aidl.INfCallbackBluetooth;

interface INfCommandBluetooth {
    boolean isBluetoothServiceReady();
    boolean registerBtCallback(INfCallbackBluetooth callback);
    boolean unregisterBtCallback(INfCallbackBluetooth callback);
    String getNfServiceVersionName();
    boolean setBtEnable(boolean enabled);
    boolean setBtDiscoverableTimeout(int timeout);
    boolean startBtDiscovery();
    boolean cancelBtDiscovery();
    boolean reqBtPair(String address);
    boolean reqBtUnpair(String address);
    boolean reqBtPairedDevices();
    String getBtLocalName();
    String getBtRemoteDeviceName(String address);
    String getBtLocalAddress();
    boolean setBtLocalName(String name);
    boolean isBtEnabled();
    int getBtState();
    boolean isBtDiscovering();
    boolean isBtDiscoverable();
    boolean isBtAutoConnectEnable();
    int reqBtConnectHfpA2dp(String address);
    int reqBtDisconnectAll();
    int getBtRemoteUuids(String address);
    boolean isDeviceAclDisconnected(String address);
    boolean switchBtRoleMode(int mode);
    int getBtRoleMode();
    void setBtAutoConnect(int condition, int period);
    int getBtAutoConnectCondition();
    int getBtAutoConnectPeriod();
    int getBtAutoConnectState();
    String getBtAutoConnectingAddress();
}
