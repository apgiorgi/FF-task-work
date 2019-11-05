package io.apg.felfel.postelemetry

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout

import com.microsoft.azure.sdk.iot.device.DeviceClient
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeCallback
import com.microsoft.azure.sdk.iot.device.IotHubConnectionStatusChangeReason
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode
import com.microsoft.azure.sdk.iot.device.Message
import com.microsoft.azure.sdk.iot.device.transport.IotHubConnectionStatus

import okhttp3.*
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.round
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity() {

    // Preferences storage
    private lateinit var preferences: SharedPreferences

    // UI elements
    private lateinit var temperatureVal: TextView
    private lateinit var uplinkVal: TextView
    private lateinit var downlinkVal: TextView
    private lateinit var intervalVal: TextView
    private lateinit var intervalBar: SeekBar
    private lateinit var periodicSwitch: Switch
    private lateinit var sendButton: Button
    private lateinit var flipButton: Button
    private lateinit var bannerView: ConstraintLayout
    private lateinit var statusLabel: TextView

    // IoT connection & provisioning
    private val ProvisioningURL = BuildConfig.ProvisioningURL
    private var client: DeviceClient? = null
    internal var protocol = IotHubClientProtocol.MQTT
    private var telemetryInterval = 300
    private val handler = Handler()
    private var telemetryThread: Thread? = null
    // Was the connection string used sucesfully before? If not, good chances it's not activated
    private var activatedConnString = false
    // The IoT Device Connection String
    private lateinit var connString: String
    private var lastException: String? = null

    // Telemetry data
    private var temperature: Double = 0.toDouble()
    private var inBandwidth: Double = 0.toDouble()
    private var outBandwidth: Double = 0.toDouble()
    private var msgStr: String? = null
    private var sendMessage: Message? = null
    private var msgSentCount = 0

    //OkhttpClient for building http request url
    private val provisioningClient = OkHttpClient().newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Configure UI Elements
        temperatureVal = findViewById(R.id.temperatureVal)
        temperatureVal.isEnabled = false
        uplinkVal = findViewById(R.id.uplinkVal)
        uplinkVal.isEnabled = false
        downlinkVal = findViewById(R.id.downlinkVal)
        downlinkVal.isEnabled = false
        intervalVal = findViewById(R.id.intervalVal)
        intervalBar = findViewById(R.id.intervalBar)
        intervalBar.isEnabled = false
        periodicSwitch = findViewById(R.id.periodicSwitch)
        periodicSwitch.isEnabled = false
        periodicSwitch.isChecked = true
        sendButton = findViewById(R.id.sendButton)
        sendButton.isEnabled = false
        flipButton = findViewById(R.id.flipButton)
        bannerView = findViewById(R.id.bannerView)
        statusLabel = findViewById(R.id.statusLabel)

        // Configure and link interval seekbar
        intervalBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(intervalBar: SeekBar, progress: Int, fromUser: Boolean) {
                intervalVal.setText(progress.toString()+" s")
                telemetryInterval = progress
                // Save in preferences?
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        intervalBar.progress = telemetryInterval

        /**
         * Start connection procedure
         * TODO: Encapsulate provisioning and connection management in its own class
         */
        // Get preferences object
        preferences = getPreferences(Context.MODE_PRIVATE)

        // Check if IoT device connection string was retrived and store before, otherwise get one from the "provisioning" url
        if (preferences.contains("DeviceConnectionString")) {
            // We have a previously stored connection string
            connString = preferences.getString("DeviceConnectionString", null)
            activatedConnString = preferences.getBoolean("activatedConnString", false)
            statusLabel.text = "Connecting..."
            handleInitClient()
        } else {
            // get a new enrollment connection string
            println("Provisioning a new IoT Device")
            statusLabel.text = "Requesting new credentials"
            val request = Request.Builder()
                .url(ProvisioningURL)
                .build()

            // Run the request async.
            provisioningClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                    // Network error. Let the user know and abort. Re-start needed.
                    runOnUiThread(object:Runnable {
                        public override fun run() {
                            statusLabel.text = "Something's wrong. Please get in contact with IoT support"
                        }
                    })
                }

                // Get back the connection string
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            // Something's wrong with the provisioning api. LEt the user know and abort. Re-start needed.
                            runOnUiThread(object:Runnable {
                                public override fun run() {
                                    statusLabel.text = "Something's wrong. Please get in contact with IoT support"
                                }
                            })

                        } else {
                            connString = response.body!!.string()
                            runOnUiThread(object:Runnable {
                                public override fun run() {
                                    statusLabel.text = "Waiting for credentials approval..."
                                }
                            })
                            handleInitClient()
                        }
                    }
                }
            })
        }

    }

    // Here we try to connect and retry until the device is enabled. If the app is closed in the mean time, a new device will be provisioned
    private fun handleInitClient() {
        initClient()
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object:Runnable {
            public override fun run() {
                if (!activatedConnString) {
                    initClient()
                    handler.postDelayed(this, 5000)
                } else {
                    with (preferences.edit()) {
                        putString("DeviceConnectionString", connString)
                        putBoolean("activatedConnString", true)
                        commit()
                    }
                    // connection string is good and activated. store it
                }
            }
        }, 5000)
    }

    private fun initClient() {
        client = DeviceClient(connString, protocol)
        println("Connecting to IoT Hub...")
        try {
            client!!.registerConnectionStatusChangeCallback(IotHubConnectionStatusChangeCallbackLogger(), Any())
            client!!.open()
//            val callback = MessageCallback()
//            client!!.setMessageCallback(callback, null)
//            client!!.subscribeToDeviceMethod(SampleDeviceMethodCallback(), applicationContext, DeviceMethodStatusCallBack(), null)
        } catch (e: Exception) {
            System.err.println("Exception while opening IoTHub connection: $e")
            client!!.closeNow()
            println("Shutting down...")
        }
    }

    private fun postConnect() {
        // Enable all elements and start telemetry handler
        statusLabel.text = "Connected"
        temperatureVal.isEnabled = true
        uplinkVal.isEnabled = true
        downlinkVal.isEnabled = true
        intervalBar.isEnabled = true
        periodicSwitch.isEnabled = true
        sendButton.isEnabled = true

        startTelemetry()
    }

    private fun startTelemetry() {
        telemetryThread = Thread(Runnable {
            try {
                while (true) {
                    temperature = 5.0 + Math.random() * 15 // Digi APIx for Android
                    inBandwidth = 350.0 + Math.random() * 350.0 // TrafficStats.getTotalRxBytes()/1024 - inBandwidth;
                    outBandwidth = 350.0 + Math.random() * 350.0 // TrafficStats.getTotalTxBytes()/1024 - outBandwidth;

                    // Update the UI
                    runOnUiThread(object:Runnable {
                        public override fun run() {
                            temperatureVal.text = (round(temperature * 100)/100).toString()
                            downlinkVal.text = (round(inBandwidth * 100)/100).toString()
                            uplinkVal.text = (round(outBandwidth * 100)/100).toString()
                            sendMessages()
                        }
                    })
                    Thread.sleep(telemetryInterval.toLong()*1000)
                }
            } catch (e: InterruptedException) {
                return@Runnable
            } catch (e: Exception) {
                System.err.println("Exception while sending event: $e")
                // Update the UI
                runOnUiThread(object:Runnable {
                    public override fun run() {
                        periodicSwitch.isChecked = false
                    }
                })
            }
        })

        telemetryThread!!.start()
    }

    private fun sendMessages() {
        temperature = temperatureVal.text.toString().toDouble()
        inBandwidth = downlinkVal.text.toString().toDouble() // TrafficStats.getTotalRxBytes() - inBandwidth;
        outBandwidth = uplinkVal.text.toString().toDouble() // TrafficStats.getTotalTxBytes() - outBandwidth;

        msgStr = "{" +
                "\"tempAvg\":" + String.format("%.2f", temperature) +
                ", \"inBw\":" + String.format("%.2f", inBandwidth) +
                ", \"outBw\":" + String.format("%.2f", outBandwidth) +
                "}"

        try {
            //
            val ptext = msgStr!!.toByteArray(StandardCharsets.ISO_8859_1)
            val msgStrUTF8 = String(ptext, StandardCharsets.UTF_8)

            sendMessage = Message(msgStrUTF8)
            sendMessage!!.setProperty("BandwidthNotify", if (inBandwidth + outBandwidth > 1024 * 1024) "true" else "false")
            sendMessage!!.setProperty("temperatureNotify", if (temperature > 10) "true" else "false")
            sendMessage!!.setProperty("temperatureAlert", if (temperature > 15) "true" else "false")
            sendMessage!!.contentEncoding = "utf-8"
            sendMessage!!.contentType = "application/json"
            sendMessage!!.messageId = java.util.UUID.randomUUID().toString()
            val eventCallback = EventCallback()
            client!!.sendEventAsync(sendMessage!!, eventCallback, msgSentCount)
            println("Message Sent: " + msgStr!!)
            msgSentCount++
        } catch (e: Exception) {
            System.err.println("Exception while sending event: $e")
            // Update the UI
            runOnUiThread(object:Runnable {
                public override fun run() {
                    periodicSwitch.isChecked = false
                }
            })
        }
    }

    internal inner class EventCallback : IotHubEventCallback {
        override fun execute(status: IotHubStatusCode, context: Any) {
            val i = if (context is Int) context else 0
            println("IoT Hub responded to message " + i.toString()
                    + " with status " + status.name)
        }
    }

    protected inner class IotHubConnectionStatusChangeCallbackLogger : IotHubConnectionStatusChangeCallback {

        override fun execute(status: IotHubConnectionStatus, statusChangeReason: IotHubConnectionStatusChangeReason, throwable: Throwable?, callbackContext: Any) {
            println()
            println("CONNECTION STATUS UPDATE: $status")
            println("CONNECTION STATUS REASON: $statusChangeReason")
            println("CONNECTION STATUS THROWABLE: " + if (throwable == null) "null" else throwable.message)
            println()

            throwable?.printStackTrace()

            if (status == IotHubConnectionStatus.DISCONNECTED) {
                //connection was lost, and is not being re-established. Look at provided exception for
                // how to resolve this issue. Cannot send messages until this issue is resolved, and you manually
                // re-open the device client
            } else if (status == IotHubConnectionStatus.DISCONNECTED_RETRYING) {
                //connection was lost, but is being re-established. Can still send messages, but they won't
                // be sent until the connection is re-established
            } else if (status == IotHubConnectionStatus.CONNECTED) {
                //Connection was successfully re-established. Can send messages.
                activatedConnString = true
                postConnect()
            }
        }
    }

    // Switch handler
    fun periodicSwitchOnClick(v: View) {
        if (periodicSwitch.isChecked) {
            // Start telemetry loop thread
            println("Start periodic telemetry")
            startTelemetry()
        } else {
            //stop telemetry loop thread
            println("Stop periodic telemetry")
            telemetryThread!!.interrupt()
        }
    }

    // Send button handler
    fun buttonSendOnClick(v: View) {
        println("Send!")
        sendMessages()
    }

    // View flipper handler
    fun flipButtonOnClick(v: View) {
        if (bannerView.visibility == View.GONE) {
            bannerView.visibility = View.VISIBLE
        } else {
            bannerView.visibility = View.GONE
        }
    }
}