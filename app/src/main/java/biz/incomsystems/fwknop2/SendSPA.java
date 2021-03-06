/*
This file is part of Fwknop2.

    Fwknop2 is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 2 of the License, or
    (at your option) any later version.

    Foobar is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Foobar.  If not, see <http://www.gnu.org/licenses/>.
 */
package biz.incomsystems.fwknop2;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.database.Cursor;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import com.sonelli.juicessh.pluginlibrary.PluginClient;
import com.sonelli.juicessh.pluginlibrary.exceptions.ServiceNotConnectedException;
import com.sonelli.juicessh.pluginlibrary.listeners.OnClientStartedListener;
import com.sonelli.juicessh.pluginlibrary.listeners.OnSessionFinishedListener;
import com.sonelli.juicessh.pluginlibrary.listeners.OnSessionStartedListener;

import org.xbill.DNS.*;
import org.apache.commons.validator.routines.InetAddressValidator;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SendSPA implements OnSessionStartedListener, OnSessionFinishedListener {
    DBHelper mydb;
    public static Config config;
    ProgressDialog pdLoading;
    Boolean ready;
    public PluginClient client;
    public boolean isConnected = false;

    public native String sendSPAPacket();

    //These are the configs to pass to the native code
    public String access_str;
    public String allowip_str;
    public String passwd_str;
    public String passwd_b64;
    public String hmac_str;
    public String hmac_b64;
    public String fw_timeout_str;
    public String nat_ip_str;
    public String nat_port_str;
    public String nat_access_str;
    public String server_cmd_str;
    public String legacy;

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == 2585){
            client.gotActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onSessionStarted(int i, String s) {
        SendSPA.this.isConnected = true;
        try {
            client.attach(i,s);
        } catch (ServiceNotConnectedException ex){
            Log.e("fwknop2", "Error attaching");
        }
    }

    @Override
    public void onSessionCancelled() {
    }

    @Override
    public void onSessionFinished() {
    }

    public int send(String nick, final Activity ourAct) {
        mydb = new DBHelper(ourAct);
        config = new Config();
        config = mydb.getConfig(nick);
        Cursor CurrentIndex = mydb.getData(nick);
        CurrentIndex.moveToFirst();

        //These variables are the ones that the jni pulls settings from.
        access_str = config.PORTS;
        passwd_str = config.KEY;
        hmac_str = config.HMAC;
        fw_timeout_str = config.SERVER_TIMEOUT;
        allowip_str = config.ACCESS_IP;
        nat_ip_str = config.NAT_IP;
        nat_port_str = config.NAT_PORT;
        server_cmd_str = "";
        nat_access_str = "";
        if (!nat_ip_str.equalsIgnoreCase("")) {
            nat_access_str = nat_ip_str + "," + nat_port_str;
        }
        if (config.KEY_BASE64) {
            passwd_b64 = "true";
        } else {
            passwd_b64 = "false";
        }
        if (config.HMAC_BASE64) {
            hmac_b64 = "true";
        } else {
            hmac_b64 = "false";
        }
        if (config.LEGACY) {
            legacy = "true";
        } else {
            legacy = "false";
        }
        if (config.SERVER_PORT.equalsIgnoreCase("random")) {
            Random r = new Random();
            int random_port = r.nextInt(65535 - 10000) + 10000;
            config.SERVER_PORT = String.valueOf(random_port);
        }
        if (passwd_str.equalsIgnoreCase("")) { //here is where we prompt for a key

            AlertDialog.Builder alert = new AlertDialog.Builder(ourAct);
            alert.setTitle(ourAct.getResources().getText(R.string.Rijndael_Key));
            final EditText input = new EditText(ourAct);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            alert.setView(input);
            alert.setPositiveButton(ourAct.getResources().getText(R.string.ok), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    passwd_str = input.getText().toString();
                    if (!(passwd_str.equalsIgnoreCase(""))) {
                        final getExternalIP task = new getExternalIP(ourAct);
                        task.execute();
                    } else {
                        Toast.makeText(ourAct, ourAct.getResources().getText(R.string.blank_key), Toast.LENGTH_LONG).show();
                    }
                }
            });
            alert.setNegativeButton(ourAct.getResources().getText(R.string.cancel), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    // Canceled.
                }
            });
            alert.show();
        } else {
            final getExternalIP task = new getExternalIP(ourAct);
            task.execute();
        }
        return 0;
    }

    private class getExternalIP extends AsyncTask<Void, Void, String>
    {
        private Activity mActivity;
        private String spaPacket;
        public getExternalIP (Activity activity) {
            mActivity = activity;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            pdLoading = new ProgressDialog(mActivity);
            pdLoading.setMessage("\t" + mActivity.getResources().getText(R.string.sending));
            pdLoading.show();
        }

        @Override
        protected String doInBackground(Void... params) {
            InetAddressValidator ipValidate = new InetAddressValidator();

            try {
                System.load(mActivity.getFilesDir().getParentFile().getPath() + "/lib/libfwknop.so");
            } catch (Exception ex) {
                Log.e("fwknop2", "Could not load libfko: " + ex);
            }
            if (allowip_str.equalsIgnoreCase("Source IP")) {
                allowip_str = "0.0.0.0";
            } else if (allowip_str.equalsIgnoreCase("Resolve IP")) {



                SharedPreferences prefs = mActivity.getSharedPreferences("MyPreferences", Context.MODE_PRIVATE);
                if (prefs.getBoolean("EnableDns", true)) {
                    try {
                        Resolver resolver = new SimpleResolver("208.67.222.222");
                        Lookup lookup = new Lookup("myip.opendns.com", Type.A);
                        lookup.setResolver(resolver);
                        Record[] records = lookup.run();
                        allowip_str = ((ARecord) records[0]).getAddress().toString();
                        Log.v("fwknop2", "Your external IP address is " + allowip_str);
                        if (allowip_str.contains("/")) {
                            allowip_str = allowip_str.split("/")[1];
                        }
                    } catch (Exception ex) {
                        Log.e("fwknop2", "dns error " + ex);
                    }
                }

                try {
                    if (!(ipValidate.isValid(allowip_str))) {
                        Pattern p = Pattern.compile("(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)");
                        URL url = new URL(prefs.getString("ipSource", "https://api.ipify.org"));
                        BufferedReader bufferReader = new BufferedReader(new InputStreamReader(url.openStream()));
                        String result;
                        while ((result = bufferReader.readLine()) != null) {
                            Log.v("fwknop2", result);
                            Matcher m = p.matcher(result);
                            if (m.find()) {
                                allowip_str = m.group();
                                Log.v("fwknop2", "Your external IP address is " + allowip_str);
                                break;
                            }
                        }
                    }
                } catch (Exception ex) {
                    Log.e("fwknop2", "error " + ex);
                }
                if (!(ipValidate.isValid(allowip_str))) {
                    Log.e("fwknop2", "Could not resolve external ip.");
                    return mActivity.getString(R.string.error_resolve);
                }
            }
            if (!config.SERVER_CMD.equalsIgnoreCase("")) {
                server_cmd_str = allowip_str + "," + config.SERVER_CMD;
            }
            spaPacket = sendSPAPacket();
            Log.v("fwknopd2", spaPacket);
            if (spaPacket != null) {
                InetAddress resolved_IP;
                try {
                    resolved_IP = InetAddress.getByName(config.SERVER_IP);
                    if (config.PROTOCOL.equalsIgnoreCase("udp")) {
                        byte[] spaBytes = spaPacket.getBytes();
                        DatagramPacket p = new DatagramPacket(spaBytes, spaBytes.length, resolved_IP, Integer.parseInt(config.SERVER_PORT));
                        DatagramSocket s = new DatagramSocket();
                        s.send(p);
                        s.close();
                    } else if (config.PROTOCOL.equalsIgnoreCase("tcp")) {
                        Socket s = new Socket(resolved_IP, Integer.parseInt(config.SERVER_PORT));
                        OutputStream out = s.getOutputStream();
                        PrintWriter output = new PrintWriter(out);
                        output.print(spaPacket);
                        output.flush();
                        output.close();
                        out.flush();
                        out.close();
                        s.close();
                    } else if (config.PROTOCOL.equalsIgnoreCase("http")) {
                        spaPacket = spaPacket.replace("+", "-");
                        spaPacket = spaPacket.replace("/", "_");
                        URL packet = new URL("http://" + config.SERVER_IP + "/" + spaPacket);
                        HttpURLConnection conn = (HttpURLConnection)packet.openConnection();
                        conn.setRequestMethod("GET");
                        conn.connect();
                        conn.getResponseCode();
                        conn.disconnect();
                    }
                } catch (Exception ex) {
                    return ex.toString();
                }
                return mActivity.getResources().getText(R.string.success).toString();
            } else return mActivity.getResources().getText(R.string.spa_failure).toString();//"Failure generating SPA data";
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            pdLoading.dismiss();

            Toast.makeText(mActivity, result, Toast.LENGTH_LONG).show();
            Log.v("fwknop2", result);
            if (result.contains("Success")) {
                if (config.SSH_CMD.contains("juice:")) {
                    ready = false; //probably not needed
                    client = new PluginClient();
                    client.start(mActivity, new OnClientStartedListener() {
                        @Override
                        public void onClientStarted() {
                            SendSPA.this.isConnected = true;
                            try {
                                client.connect(mActivity, config.juice_uuid, SendSPA.this, 2585);
                            } catch (ServiceNotConnectedException ex) {
                                Log.e("fwknop2", "not connected error");
                            }
                        }
                        @Override
                        public void onClientStopped() {
                            Log.v("fwknop2", "client stopped");
                        }
                    });

                } else if (!config.SSH_CMD.equalsIgnoreCase("")) {
                    String ssh_uri = "ssh://" + config.SSH_CMD + "/#" + config.NICK_NAME;
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(ssh_uri));
                    mActivity.startActivity(i);
                }
            }
        }
    }
}