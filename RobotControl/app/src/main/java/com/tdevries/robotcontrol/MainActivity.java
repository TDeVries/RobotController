package com.tdevries.robotcontrol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import android.support.v4.app.FragmentActivity;
import android.os.Bundle;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

public class MainActivity extends FragmentActivity implements OnMapReadyCallback {

    private GoogleMap mMap;
    private LocationManager locationManager;
    private LocationListener locationListener;
    LatLng operatorLocation;
    LatLng robotLocation;
    Marker operatorMarker;
    Marker robotMarker;

    Button startStream_btn;
    EditText piIP_edtTxt;
    WebView robotVideoStream_wv;
    public Client client;

    private int updateInterval = 100;      //Wait this long before sending the robot another command
    private Handler sendCommandHandler;     //Handler runs a sequence every updateInterval milliseconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        startStream_btn = (Button) findViewById(R.id.startStream_btn);
        piIP_edtTxt = (EditText) findViewById(R.id.piIP_edtTxt);
        robotVideoStream_wv = (WebView) findViewById(R.id.robotVideoStream_wv);

        startStream_btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadStream();
            }
        });

        initGPS();
    }

    @Override
    protected void onStop(){
        super.onStop();

        //Turns off GPS after closing app so battery isn't wasted
        if (locationManager != null) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.removeUpdates(locationListener);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCommandSend();
        client.cancel(true);        //In case the task is currently running
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                     Map and GPS                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public void initGPS() {
        //Doesn't work currently, needs to check for permissions or something first
        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        // Define a listener that responds to location updates
        locationListener = new LocationListener() {

            // Called when a new location is found by the network location provider.
            public void onLocationChanged(Location location) {
                double latitude = location.getLatitude();
                double longitude = location.getLongitude();

                operatorLocation = new LatLng(latitude, longitude);

                if (operatorMarker == null){
                    operatorMarker = mMap.addMarker(new MarkerOptions()
                            .position(operatorLocation)
                            .title("You")
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));
                }
                else
                {
                    operatorMarker.setPosition(operatorLocation);
                }
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            public void onProviderEnabled(String provider) {
            }

            public void onProviderDisabled(String provider) {
            }
        };

        if (locationManager != null) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // Register the listener with the Location Manager to receive location updates
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);
            }
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                     Video Stream                                           //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public void loadStream(){       //Run whenever the loadStream button is clicked
        String piIP = piIP_edtTxt.getText().toString();

        client = new Client(piIP, 5000);
        client.execute();

        final String robotVideoStreamURL = "http://" + piIP + ":8080/stream";
        Log.v("robotVideoStreamURL", robotVideoStreamURL);
        robotVideoStream_wv.loadUrl(robotVideoStreamURL);

        sendCommandHandler = new Handler();
        startCommandSend();

        //If there is an error loading the video stream then the webViewClient automatically tries to load it again
        robotVideoStream_wv.setWebViewClient(new WebViewClient() {
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                robotVideoStream_wv.loadUrl(robotVideoStreamURL);
            }
        });
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                     TCP Command Send                                       //
    ////////////////////////////////////////////////////////////////////////////////////////////////
    Runnable sendCommand = new Runnable() {
        //https://stackoverflow.com/questions/6242268/repeat-a-task-with-a-time-delay

        @Override
        public void run() {
            try{
                client.SendDataToNetwork("Test");   //Put here the actual command to be sent to the robot
            }
            finally {       //This is always run
                sendCommandHandler.postDelayed(sendCommand, updateInterval);    //Delay some time before running again
            }
        }
    };

    void startCommandSend(){
        sendCommand.run();
    }

    void stopCommandSend(){
        sendCommandHandler.removeCallbacks(sendCommand);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                 Server Client Class (separate thread)                                      //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public class Client extends AsyncTask<Void, byte[], Boolean> {
        Socket nsocket; //Network Socket
        InputStream nis; //Network Input Stream
        OutputStream nos; //Network Output Stream

        String serverIPAddress;
        int serverPort;

        Client(String addr, int port){
            serverIPAddress = addr;
            serverPort = port;
        }

        @Override
        protected void onPreExecute() {
            Log.i("AsyncTask", "onPreExecute");
        }

        @Override
        protected Boolean doInBackground(Void... params) { //This runs on a different thread
            boolean result = false;
            try {
                Log.i("AsyncTask", "doInBackground: Creating socket");
                SocketAddress sockaddr = new InetSocketAddress(serverIPAddress, serverPort);
                nsocket = new Socket();
                nsocket.connect(sockaddr, 5000); //10 second connection timeout
                if (nsocket.isConnected()) {
                    nis = nsocket.getInputStream();
                    nos = nsocket.getOutputStream();
                    Log.i("AsyncTask", "doInBackground: Socket created, streams assigned");
                    Log.i("AsyncTask", "doInBackground: Waiting for inital data...");
                    byte[] buffer = new byte[1024];
                    int read = nis.read(buffer, 0, 1024); //This is blocking
                    while(read != -1){
                        byte[] tempdata = new byte[read];
                        System.arraycopy(buffer, 0, tempdata, 0, read);
                        publishProgress(tempdata);
                        //Log.i("AsyncTask", "doInBackground: Got some data");
                        read = nis.read(buffer, 0, 1024); //This is blocking
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                Log.i("AsyncTask", "doInBackground: IOException");
                result = true;
            } catch (Exception e) {
                e.printStackTrace();
                Log.i("AsyncTask", "doInBackground: Exception");
                result = true;
            } finally {
                try {
                    nis.close();
                    nos.close();
                    nsocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                Log.i("AsyncTask", "doInBackground: Finished");
            }
            return result;
        }

        public void SendDataToNetwork(String cmd) { //You run this from the main thread.
            try {
                if (nsocket.isConnected()) {
                    //Log.i("AsyncTask", "SendDataToNetwork: Writing received message to socket");
                    nos.write(cmd.getBytes());
                } else {
                    Log.i("AsyncTask", "SendDataToNetwork: Cannot send message. Socket is closed");
                }
            } catch (Exception e) {
                Log.i("AsyncTask", "SendDataToNetwork: Message send failed. Caught an exception");
            }
        }

        @Override
        protected void onProgressUpdate(byte[]... values) {
            if (values.length > 0) {

                String[] latlng = new String(values[0]).split(",");
                Log.i("AsyncTask", "onProgressUpdate: " + latlng[0]);
                //Need to add check here for null strings.
                double latitude = Double.parseDouble(latlng[0]);
                double longitude = Double.parseDouble(latlng[1]);

                Log.i("AsyncTask", "onProgressUpdate: Latitude = " + latitude);
                Log.i("AsyncTask", "onProgressUpdate: Longitude = " + longitude);

                robotLocation = new LatLng(latitude, longitude);

                if (robotMarker == null){
                    robotMarker = mMap.addMarker(new MarkerOptions().position(robotLocation).title("Robot"));
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom((robotLocation), 19.f));
                }
                else
                {
                    robotMarker.setPosition(robotLocation);
                }
                float currentZoom = mMap.getCameraPosition().zoom;
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom((robotLocation), currentZoom));
            }
        }

        @Override
        protected void onCancelled() {
            Log.i("AsyncTask", "Cancelled.");
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                Log.i("AsyncTask", "onPostExecute: Completed with an Error.");
            } else {
                Log.i("AsyncTask", "onPostExecute: Completed.");
            }
        }
    }
}
