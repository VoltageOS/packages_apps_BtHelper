/*
 * Copyright (C) 2019-2022 Federico Dossena
 *               2019 The MoKee Open Source Project
 *               2023 someone5678
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */

package com.android.bluetooth.bthelper.pods;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAssignedNumbers;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources.NotFoundException;
import android.Manifest;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.Objects;

import com.android.bluetooth.bthelper.R;

import com.android.bluetooth.bthelper.pods.models.IPods;
import com.android.bluetooth.bthelper.pods.models.RegularPods;
import com.android.bluetooth.bthelper.pods.models.SinglePods;
import static com.android.bluetooth.bthelper.pods.PodsStatusScanCallback.getScanFilters;

/**
 * This is the class that does most of the work. It has 3 functions:
 * - Detect when AirPods are detected
 * - Receive beacons from AirPods and decode them (easier said than done thanks to google's autism)
 */
public class PodsService extends Service {

    private BluetoothLeScanner btScanner;
    private PodsStatus status = PodsStatus.DISCONNECTED;

    private BroadcastReceiver btReceiver = null;
    private PodsStatusScanCallback scanCallback = null;

    private BluetoothDevice mCurrentDevice;
    
    private boolean statusChanged = false;
    private boolean isModelSet = false;
    private boolean isModelIconSet = false;
    private boolean isModelLowBattThresholdSet = false;

    private static final byte[] TRUE = "true".getBytes();
    private static final byte[] FALSE = "false".getBytes();

    public PodsService () {
    }

    @Override
    public IBinder onBind (Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand (Intent intent, int flags, int startId) {
        final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device != null) {
            mCurrentDevice = device;
            startAirPodsScanner();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy () {
        super.onDestroy();
        mCurrentDevice = null;
        stopAirPodsScanner();
    }

    /**
     * The following method (startAirPodsScanner) creates a bluetooth LE scanner.
     * This scanner receives all beacons from nearby BLE devices (not just your devices!) so we need to do 3 things:
     * - Check that the beacon comes from something that looks like a pair of AirPods
     * - Make sure that it is YOUR pair of AirPods
     * - Decode the beacon to get the status
     *
     * After decoding a beacon, the status is written to PodsStatus.
     */
    private void startAirPodsScanner () {
        try {
            BluetoothManager btManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter btAdapter = btManager.getAdapter();

            if (btAdapter == null) {
                return;
            }

            if (btScanner != null && scanCallback != null) {
                btScanner.stopScan(scanCallback);
                scanCallback = null;
            }

            if (!btAdapter.isEnabled()) {
                return;
            }

            btScanner = btAdapter.getBluetoothLeScanner();

            ScanSettings scanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(1) // DON'T USE 0
                    .build();

            scanCallback = new PodsStatusScanCallback() {
                @Override
                public void onStatus (PodsStatus newStatus) {
                    setStatusChanged(status, newStatus);
                    status = newStatus;
                    updatePodsStatus(status, mCurrentDevice);
                }
            };

            btScanner.startScan(getScanFilters(), scanSettings, scanCallback);
        } catch (Throwable t) {
        }
    }

    private void stopAirPodsScanner () {
        try {
            if (btScanner != null && scanCallback != null) {

                btScanner.stopScan(scanCallback);
                scanCallback = null;
            }
            status = PodsStatus.DISCONNECTED;
        } catch (Throwable t) {
        }
    }

    private void setStatusChanged (PodsStatus status, PodsStatus newStatus) {
        if (!Objects.equals(status, newStatus)) {
            statusChanged = true;
        }
    }

    public void updatePodsStatus (PodsStatus status, BluetoothDevice device) {
        IPods airpods = status.getAirpods();
        boolean single = airpods.isSingle();

        if (!isModelSet) {
            boolean isManufacturerSet = false;
            boolean isNameSet = false;
            boolean isTypeSet = false;
            
            if (!single) {
                if (device.getMetadata(device.METADATA_MANUFACTURER_NAME) == null) {
                    isManufacturerSet = device.setMetadata(device.METADATA_MANUFACTURER_NAME,
                                            ((RegularPods)airpods).getMenufacturer().getBytes());
                } else {
                    isManufacturerSet = true;
                }
                if (device.getMetadata(device.METADATA_MODEL_NAME) == null) {
                    isNameSet = device.setMetadata(device.METADATA_MODEL_NAME,
                                            ((RegularPods)airpods).getModel().getBytes());
                } else {
                    isNameSet = true;
                }
            } else {
                if (device.getMetadata(device.METADATA_MANUFACTURER_NAME) == null) {
                    isManufacturerSet = device.setMetadata(device.METADATA_MANUFACTURER_NAME,
                                            ((SinglePods)airpods).getMenufacturer().getBytes());
                } else {
                    isManufacturerSet = true;
                }
                if (device.getMetadata(device.METADATA_MODEL_NAME) == null) {
                    isNameSet = device.setMetadata(device.METADATA_MODEL_NAME,
                                            ((SinglePods)airpods).getModel().getBytes());
                } else {
                    isNameSet = true;
                }
            }
            if (device.getMetadata(device.METADATA_DEVICE_TYPE) == null) {
                isTypeSet = device.setMetadata(device.METADATA_DEVICE_TYPE,
                                        device.DEVICE_TYPE_UNTETHERED_HEADSET.getBytes()
                                    );
            } else {
                isTypeSet = true;
            }
            isModelSet = isManufacturerSet
                         && isNameSet
                         && isTypeSet;
        }

        if (!isModelLowBattThresholdSet) {
            boolean isMainLowBatterySet = false;
            boolean isLeftLowBatterySet = false;
            boolean isRightLowBatterySet = false;
            boolean isCaseLowBatterySet = false;

            if (!single) {
                if (device.getMetadata(device.METADATA_MAIN_LOW_BATTERY_THRESHOLD) == null) {
                    isMainLowBatterySet =
                        device.setMetadata(device.METADATA_MAIN_LOW_BATTERY_THRESHOLD,
                                    (""+((RegularPods)airpods).getLowBattThreshold()).getBytes());
                } else {
                    isMainLowBatterySet = true;
                }
                if (device.getMetadata(device.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD) == null) {
                    isLeftLowBatterySet = 
                        device.setMetadata(device.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                                    (""+((RegularPods)airpods).getLowBattThreshold()).getBytes());
                } else {
                    isLeftLowBatterySet = true;
                }
                if (device.getMetadata(device.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD) == null) {
                    isRightLowBatterySet =
                        device.setMetadata(device.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                                    (""+((RegularPods)airpods).getLowBattThreshold()).getBytes());
                } else {
                    isRightLowBatterySet = true;
                }
                if (device.getMetadata(device.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD) == null) {
                    isCaseLowBatterySet =
                        device.setMetadata(device.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                                    (""+((RegularPods)airpods).getLowBattThreshold()).getBytes());
                } else {
                    isCaseLowBatterySet = true;
                }
                isModelLowBattThresholdSet = isMainLowBatterySet
                                             && isLeftLowBatterySet
                                             && isRightLowBatterySet
                                             && isCaseLowBatterySet;
            } else {
                if (device.getMetadata(device.METADATA_MAIN_LOW_BATTERY_THRESHOLD) == null) {
                    isMainLowBatterySet =
                        device.setMetadata(device.METADATA_MAIN_LOW_BATTERY_THRESHOLD,
                                    (""+((RegularPods)airpods).getLowBattThreshold()).getBytes());
                } else {
                    isMainLowBatterySet = true;
                }
                isModelLowBattThresholdSet = isMainLowBatterySet;
            }
        }

        if (!isModelIconSet) {
            byte[] MODEL_ICON_URI = null;
            byte[] MODEL_ICON_URI_LEFT = null;
            byte[] MODEL_ICON_URI_RIGHT = null;
            byte[] MODEL_ICON_URI_CASE = null;

            boolean isMainIconset = false;
            boolean isLeftIconset = false;
            boolean isRightIconset = false;
            boolean isCaseIconset = false;

            if (!single) {
                MODEL_ICON_URI = 
                        resToUri(((RegularPods)airpods).getDrawable()).toString().getBytes();
                MODEL_ICON_URI_LEFT = 
                        resToUri(((RegularPods)airpods).getLeftDrawable()).toString().getBytes();
                MODEL_ICON_URI_RIGHT = 
                        resToUri(((RegularPods)airpods).getRightDrawable()).toString().getBytes();
                MODEL_ICON_URI_CASE = 
                        resToUri(((RegularPods)airpods).getCaseDrawable()).toString().getBytes();
            } else {
                MODEL_ICON_URI = 
                        resToUri(((SinglePods)airpods).getDrawable()).toString().getBytes();
            }

            if (device.getMetadata(device.METADATA_MAIN_ICON) == null) {
                isMainIconset = device.setMetadata(device.METADATA_MAIN_ICON,
                                                    MODEL_ICON_URI);
            } else {
                isMainIconset = true;
            }
            if (!single) {
                if (device.getMetadata(device.METADATA_UNTETHERED_LEFT_ICON) == null) {
                    isLeftIconset = device.setMetadata(device.METADATA_UNTETHERED_LEFT_ICON,
                                                        MODEL_ICON_URI_LEFT);
                } else {
                    isLeftIconset = true;
                }
                if (device.getMetadata(device.METADATA_UNTETHERED_RIGHT_ICON) == null) {
                    isRightIconset = device.setMetadata(device.METADATA_UNTETHERED_RIGHT_ICON,
                                                         MODEL_ICON_URI_RIGHT);
                } else {
                    isRightIconset = true;
                }
                if (device.getMetadata(device.METADATA_UNTETHERED_CASE_ICON) == null) {
                    isCaseIconset = device.setMetadata(device.METADATA_UNTETHERED_CASE_ICON,
                                                        MODEL_ICON_URI_CASE);
                } else {
                    isCaseIconset = true;
                }
            }
            if (!single) {
                isModelIconSet = isMainIconset
                                 && isLeftIconset
                                 && isRightIconset
                                 && isCaseIconset;
            } else {
                isModelIconSet = isMainIconset;
            }
        }

        if (statusChanged) {
            int batteryUnified = 0;

            boolean chargingMain = false;

            if (!single) {
                RegularPods regularPods = (RegularPods)airpods;

                device.setMetadata(device.METADATA_UNTETHERED_LEFT_CHARGING,
                                    regularPods.isCharging(RegularPods.LEFT) == true ? TRUE : FALSE);
                device.setMetadata(device.METADATA_UNTETHERED_LEFT_BATTERY,
                                    regularPods.getParsedStatus(RegularPods.LEFT).getBytes());
        
                device.setMetadata(device.METADATA_UNTETHERED_RIGHT_CHARGING,
                                    regularPods.isCharging(RegularPods.RIGHT) == true ? TRUE : FALSE);
                device.setMetadata(device.METADATA_UNTETHERED_RIGHT_BATTERY,
                                    regularPods.getParsedStatus(RegularPods.RIGHT).getBytes());
        
                device.setMetadata(device.METADATA_UNTETHERED_CASE_CHARGING,
                                    regularPods.isCharging(RegularPods.CASE) == true ? TRUE : FALSE);
                device.setMetadata(device.METADATA_UNTETHERED_CASE_BATTERY,
                                    regularPods.getParsedStatus(RegularPods.CASE).getBytes());

                chargingMain = regularPods.isCharging(RegularPods.LEFT) 
                               && regularPods.isCharging(RegularPods.RIGHT);

                batteryUnified = Math.min(regularPods.getParsedArgStatus(RegularPods.LEFT), 
                                          regularPods.getParsedArgStatus(RegularPods.RIGHT));

            } else {
                SinglePods singlePods = (SinglePods)airpods;

                chargingMain = singlePods.isCharging();

                batteryUnified = singlePods.getParsedArgStatus();
            }

            device.setMetadata(device.METADATA_MAIN_CHARGING,
                                chargingMain == true ? TRUE : FALSE);

            final Object[] arguments = new Object[] {
                1, // Number of key(IndicatorType)/value pairs
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, // IndicatorType: Battery Level
                batteryUnified, // Battery Level
            };
    
            broadcastVendorSpecificEventIntent(
                    BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV,
                    BluetoothAssignedNumbers.APPLE,
                    BluetoothHeadset.AT_CMD_TYPE_SET,
                    arguments,
                    mCurrentDevice);
        }

        statusChanged = false;
    }

    private void broadcastVendorSpecificEventIntent (String command, int companyId, int commandType,
            Object[] arguments, BluetoothDevice device) {
        final Intent intent = new Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD, command);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, commandType);
        // assert: all elements of args are Serializable
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothDevice.EXTRA_NAME, device.getName());
        intent.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "."
                + Integer.toString(companyId));
        String[] permissions = new String[] {
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_PRIVILEGED,
        };
        sendBroadcastAsUserMultiplePermissions(intent, UserHandle.ALL, permissions);

        if (statusChanged) {
            final Intent statusIntent = new Intent("batterywidget.impl.action.update_bluetooth_data")
                                            .setPackage("com.google.android.settings.intelligence");
            statusIntent.putExtra("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED", intent);
            sendBroadcastAsUser(statusIntent, UserHandle.ALL);
        }
    }

    public Uri resToUri (int resId) {
        try {
            Uri uri = (new Uri.Builder())
                        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                        .authority(getApplicationContext().getResources().getResourcePackageName(resId))
                        .appendPath(getApplicationContext().getResources().getResourceTypeName(resId))
                        .appendPath(getApplicationContext().getResources().getResourceEntryName(resId))
                        .build();
            return uri;
        } catch (NotFoundException e) {
            return null;
        }
    }

}