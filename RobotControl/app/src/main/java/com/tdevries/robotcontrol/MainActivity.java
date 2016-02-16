package com.tdevries.robotcontrol;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
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

public class MainActivity extends AppCompatActivity {

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

        startStream_btn = (Button) findViewById(R.id.startStream_btn);
        piIP_edtTxt = (EditText) findViewById(R.id.piIP_edtTxt);
        robotVideoStream_wv = (WebView) findViewById(R.id.robotVideoStream_wv);

        startStream_btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadStream();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCommandSend();
        client.cancel(true);        //In case the task is currently running
    }

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
                //Log.i("AsyncTask", "onProgressUpdate: " + values[0].length + " bytes received.");
                Log.i("AsyncTask", "onProgressUpdate: " + new String(values[0]));

                //GPS coordinates will be here. Use to update map.
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
