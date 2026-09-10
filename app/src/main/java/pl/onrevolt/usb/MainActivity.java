package pl.onrevolt.usb;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private static final int LORA_BAUD_RATE = 115200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("OnRevolt USB");
        }

        getSupportFragmentManager().addOnBackStackChangedListener(this);

        if (savedInstanceState == null) {
            if (!openFirstUsbSerialDevice(false)) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .add(R.id.fragment, new DevicesFragment(), "devices")
                        .commit();
            }
        } else {
            onBackStackChanged();
        }
    }

    @Override
    public void onBackStackChanged() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(
                    getSupportFragmentManager().getBackStackEntryCount() > 0
            );
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            openFirstUsbSerialDevice(true);
        }
    }

    private boolean openFirstUsbSerialDevice(boolean forceReconnect) {
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();

        for (UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if (driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }

            if (driver != null && !driver.getPorts().isEmpty()) {
                TerminalFragment existingTerminal =
                        (TerminalFragment) getSupportFragmentManager().findFragmentByTag("terminal");

                if (existingTerminal != null && !forceReconnect) {
                    existingTerminal.status("USB serial device detected");
                    return true;
                }

                Bundle args = new Bundle();
                args.putInt("device", device.getDeviceId());
                args.putInt("port", 0);
                args.putInt("baud", LORA_BAUD_RATE);

                Fragment fragment = new TerminalFragment();
                fragment.setArguments(args);

                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment, fragment, "terminal")
                        .commitAllowingStateLoss();

                return true;
            }
        }

        return false;
    }
}