package com.splitaway.app;

import com.getcapacitor.BridgeActivity;
import android.os.Bundle;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Регистрируем нативный плагин ReceiptScanner до super.onCreate()
        registerPlugin(ReceiptScannerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
