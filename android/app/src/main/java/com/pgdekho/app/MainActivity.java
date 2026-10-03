package com.pgdekho.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Handler;
import android.os.Looper;

public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private WebView webView;
    private LocationManager locationManager;
    private GeolocationPermissions.Callback geolocationCallback;
    private String geolocationOrigin;

    public class AndroidBridge {
        @JavascriptInterface
        public void requestPreciseLocation() {
            runOnUiThread(() -> checkAndRequestLocationPermission());
        }

        @JavascriptInterface
        public void checkPermissionStatus() {
            runOnUiThread(() -> {
                boolean hasPermission = ContextCompat.checkSelfPermission(
                        MainActivity.this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED;
                if (hasPermission) {
                    fetchNativeLocation();
                }
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        webView = findViewById(R.id.webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setGeolocationEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidLocation");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Check if already granted and update UI automatically
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    fetchNativeLocation();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                geolocationOrigin = origin;
                geolocationCallback = callback;
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    checkAndRequestLocationPermission();
                } else {
                    callback.invoke(origin, true, false);
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");

        // Request immediately on launch if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestLocationPermission();
        }
    }

    private void checkAndRequestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST_CODE
            );
        } else {
            fetchNativeLocation();
        }
    }

    @SuppressLint("MissingPermission")
    private void fetchNativeLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (locationManager == null) {
            sendLocationToWebView(12.9716, 77.5946);
            return;
        }

        // 1. Check last known location across all enabled providers
        Location bestLocation = null;
        try {
            List<String> providers = locationManager.getProviders(true);
            if (providers != null) {
                for (String provider : providers) {
                    try {
                        Location loc = locationManager.getLastKnownLocation(provider);
                        if (loc != null) {
                            if (bestLocation == null || loc.getAccuracy() < bestLocation.getAccuracy() || loc.getTime() > bestLocation.getTime()) {
                                bestLocation = loc;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        if (bestLocation != null) {
            sendLocationToWebView(bestLocation.getLatitude(), bestLocation.getLongitude());
            return;
        }

        // 2. Request single update from ALL available providers with 0 delay and 0 distance
        final AtomicBoolean locationSent = new AtomicBoolean(false);
        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location loc) {
                if (locationSent.compareAndSet(false, true)) {
                    try {
                        locationManager.removeUpdates(this);
                    } catch (Exception ignored) {}
                    sendLocationToWebView(loc.getLatitude(), loc.getLongitude());
                }
            }
            @Override
            public void onProviderDisabled(@NonNull String provider) {}
            @Override
            public void onProviderEnabled(@NonNull String provider) {}
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
        };

        boolean requested = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, listener);
                requested = true;
            }
        } catch (Exception ignored) {}

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, listener);
                requested = true;
            }
        } catch (Exception ignored) {}

        if (!requested) {
            sendLocationToWebView(12.9716, 77.5946);
            return;
        }

        // 3. Fallback timeout after 2500ms so app NEVER hangs indoors or without satellite fix
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (locationSent.compareAndSet(false, true)) {
                try {
                    locationManager.removeUpdates(listener);
                } catch (Exception ignored) {}
                sendLocationToWebView(12.9716, 77.5946);
            }
        }, 2500);
    }

    private void sendLocationToWebView(double lat, double lon) {
        new Thread(() -> {
            String city = "Bengaluru";
            String locality = "";
            String fullAddress = "";
            try {
                Geocoder geocoder = new Geocoder(MainActivity.this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address addr = addresses.get(0);
                    if (addr.getLocality() != null && !addr.getLocality().isEmpty()) {
                        city = addr.getLocality();
                    } else if (addr.getSubAdminArea() != null && !addr.getSubAdminArea().isEmpty()) {
                        city = addr.getSubAdminArea();
                    } else if (addr.getAdminArea() != null && !addr.getAdminArea().isEmpty()) {
                        city = addr.getAdminArea();
                    }

                    if (addr.getSubLocality() != null && !addr.getSubLocality().isEmpty()) {
                        locality = addr.getSubLocality();
                    } else if (addr.getThoroughfare() != null && !addr.getThoroughfare().isEmpty()) {
                        locality = addr.getThoroughfare();
                    } else if (addr.getFeatureName() != null && !addr.getFeatureName().isEmpty()) {
                        locality = addr.getFeatureName();
                    }

                    if (addr.getMaxAddressLineIndex() >= 0) {
                        fullAddress = addr.getAddressLine(0);
                    }
                }
            } catch (Exception ignored) {}

            if (locality == null || locality.isEmpty()) {
                locality = city;
            }
            if (fullAddress == null || fullAddress.isEmpty()) {
                fullAddress = locality.equals(city) ? city : (locality + ", " + city);
            }

            final String finalCity = escapeJs(city);
            final String finalLocality = escapeJs(locality);
            final String finalAddress = escapeJs(fullAddress);

            runOnUiThread(() -> {
                String js = String.format(Locale.US, "javascript:if(window.onNativeLocationReceived){window.onNativeLocationReceived(%f, %f, '%s', '%s', '%s');}", lat, lon, finalCity, finalLocality, finalAddress);
                webView.evaluateJavascript(js, null);
            });
        }).start();
    }

    private String escapeJs(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("'", "\\'")
                  .replace("\"", "\\\"")
                  .replace("\n", " ")
                  .replace("\r", "");
    }

    private void notifyLocationDenied(String reason) {
        runOnUiThread(() -> {
            String js = String.format("javascript:onNativeLocationDenied('%s');", reason);
            webView.evaluateJavascript(js, null);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (geolocationCallback != null && geolocationOrigin != null) {
                geolocationCallback.invoke(geolocationOrigin, granted, false);
                geolocationCallback = null;
                geolocationOrigin = null;
            }
            if (granted) {
                fetchNativeLocation();
            } else {
                notifyLocationDenied("Permission denied by user");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
