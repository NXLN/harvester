package de.hft_leipzig.hfapp2.hfapp_kotlin

import android.Manifest
import android.app.*
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.*
import android.telephony.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.google.android.gms.location.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import androidx.annotation.RequiresApi
import java.lang.Exception
import java.lang.reflect.Method


const val CSV_HEADER = "timestamp;sessionID;datetime;type;status;band;mcc;mnc;pci;rsrp;rsrq;asu;rssnr;ta;cqi;ci;lat;lon;alt;acc;speed;speed_acc;rssi;bw;rb;download;upload"

private const val LTE_TAG = "LTE_Tag"
private const val LTE_SIGNAL_STRENGTH = "getLteSignalStrength"

private var signalStrength: SignalStrength? = null

@Database(entities = [PingResult::class, MeasurementPoint::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementPointDao(): MeasurementPointDao
    abstract fun pingResultDao(): PingResultDao
}

class MeasurementService : Service() {
    private val interval: Long = 500
    private val fastestInterval: Long = 250
    private lateinit var db: AppDatabase

    private lateinit var startedMeasurementAt: Date

    private val myBinder = MyLocalBinder()
    private lateinit var mNotification: Notification

    var isRecording = false
    var pingThread: Thread? = null
    private var sessionID = ""
    val pingQueue: Queue<String> = ArrayDeque<String>()

    private val mainHandler = Handler()
    private lateinit var backgroundTask: Runnable

    private lateinit var tm: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var mFusedLocationProviderClient: FusedLocationProviderClient? = null
    private lateinit var mLocationRequest: LocationRequest
    private var mLastLocation: Location? = null
    var lastMeasurements: ArrayList<MeasurementPoint> = ArrayList()

    private var pingEnabled = false
    private var pingServer = "8.8.8.8"
    private var pingSize = 56
    private var pingInterval = 100.0f
    private var pingCount = 5
    private var pingAdaptive = false

    internal inner class AddMeasurementToDB(var mp: MeasurementPoint) : Runnable {
        override fun run() {
            db.measurementPointDao().insertAll(mp)
        }
    }

    internal inner class AddPingToDB(var pr: PingResult) : Runnable {
        override fun run() {
            db.pingResultDao().insertAll(pr)
        }
    }

    internal inner class GetMeasurementsFromDB : Runnable {
        override fun run() {
            val exportDir =  File(Environment.getExternalStorageDirectory(), "")
            Log.i("Measurement service", exportDir.absolutePath)

            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val file = File(exportDir, "csvname.csv")
            if (!file.exists()) {
                file.createNewFile()
            }

            val fileWriter = FileWriter(file.absoluteFile)
            fileWriter.append(CSV_HEADER)
            fileWriter.append('\n')
            val res = db.measurementPointDao().getAll()
            for (r in res) {
                Log.i("Measurement service", r.sessionID)
                fileWriter.append(r.toCSVRow())
                fileWriter.append('\n')
            }
            fileWriter.flush()
            fileWriter.close()
        }
    }

    internal inner class PingProcess : Runnable {
        override fun run() {
            val serverAddr = pingServer
            val interval = pingInterval.toString()
            val count = pingCount.toString()
            val size = pingSize.toString()

            val cmd = mutableListOf("ping", "-D")
            cmd.addAll(arrayOf("-i", interval))
            cmd.addAll(arrayOf("-c", count))
            cmd.addAll(arrayOf("-s", size))
            if (pingAdaptive) {
                cmd.addAll(arrayOf("-A"))
            }
            cmd.add(serverAddr)

            val builder = ProcessBuilder()
            builder.command(cmd)

            val process = builder.start()
            val stdInput = process.inputStream.bufferedReader()

            val isThreadRunning = true
            while (isThreadRunning) {
                val currentStr = try {
                    stdInput.readLine()
                } catch (e: IllegalStateException) {
                    break
                } ?: break

                PING_REGEX.toRegex().find(currentStr) ?: continue

                val pingResult = PingResult(currentStr, sessionID)
                pingResult.newLocation(mLastLocation)
                Thread(AddPingToDB(pingResult)).start()

                pingQueue.add(currentStr)
            }
            process.destroy()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return myBinder
    }

    private val mLocationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            mLastLocation = locationResult.lastLocation
        }
    }

    fun saveMeasurement() {
        Thread(GetMeasurementsFromDB()).start()
    }

    fun stopMeasurement() {
        sessionID = ""
        isRecording = false
    }

    private fun newRecording() {
        startedMeasurementAt = Date()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val infoDate = Date()
        sessionID = "hfapp2_" + sdf.format(infoDate)
    }

    fun toggleRecording() {
        if (!isRecording && sessionID=="") {
            newRecording()
        }
        isRecording = !isRecording
    }

    fun elapsed(): String {
        val diff = Date().time - startedMeasurementAt.time
        val seconds = diff / 1000
        val secondsShow = (seconds%60).toString().padStart(2, '0')
        val minutes = seconds / 60
        val minutesShow = (minutes%60).toString().padStart(2, '0')
        val hours = (minutes / 60).toString().padStart(2, '0')
        return "$hours:$minutesShow:$secondsShow"
    }

    private fun getMeasurements(): ArrayList<MeasurementPoint> {
        lastMeasurements = ArrayList()

        if (pingEnabled) {
            try{
                if (pingThread != null && pingThread!!.isAlive) {
                    pingThread?.join()
                }
            } finally {
                pingThread = null
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return lastMeasurements
        }
        val allCellInfo = tm.allCellInfo

        if (allCellInfo.size == 0) {
            return lastMeasurements
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tm.signalStrength?.toString()
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val datetime = sdf.format(Date())

        for (cellInfo in allCellInfo) {
            if (cellInfo == null) {
                continue
            }

            val mp = MeasurementPoint(cellInfo)

            when (cellInfo) {
                is CellInfoLte ->{
                    mp.sessionID = sessionID
                    mp.newLocation(mLastLocation)
                    mp.datetime = datetime
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Log.i("measurements","getLTEsignalStrength(): "+tm.signalStrength?.cellSignalStrengths?.toString())
                        val signalStrength = tm.signalStrength?.cellSignalStrengths?.get(0).toString().split(": ")[1].split(" ")

                        for (i in signalStrength) {
                            if ("rssnr" in i){
                                mp.rssnr = i.split("=")[1].toDouble()
                                if (mp.rssnr in -6.93..-3.19) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 0.65
                                            mp.avb4 = 0.86}
                                        10.0 -> {mp.avb3 = 1.32
                                            mp.avb4 = 1.72}
                                        15.0 -> {mp.avb3 = 1.99
                                            mp.avb4 = 2.6}
                                        20.0 -> {mp.avb3 = 2.66
                                            mp.avb4 = 3.46}
                                    }
                                } else if (mp.rssnr in -3.18..-1.26) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 1.11
                                            mp.avb4 = 1.35}
                                        10.0 -> {mp.avb3 = 2.11
                                            mp.avb4 = 2.72}
                                        15.0 -> {mp.avb3 = 3.21
                                            mp.avb4 = 4.19}
                                        20.0 -> {mp.avb3 = 4.37
                                            mp.avb4 = 5.47}
                                    }
                                } else if (mp.rssnr in -1.25..0.75) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 2.11
                                            mp.avb4 = 1.72}
                                        10.0 -> {mp.avb3 = 4.19
                                            mp.avb4 = 3.46}
                                        15.0 -> {mp.avb3 = 6.4
                                            mp.avb4 = 5.1}
                                        20.0 -> {mp.avb3 = 8.35
                                            mp.avb4 = 6.89}
                                    }
                                } else if (mp.rssnr in 0.76..2.69) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 2.97
                                            mp.avb4 = 2.11}
                                        10.0 -> {mp.avb3 = 5.91
                                            mp.avb4 = 4.19}
                                        15.0 -> {mp.avb3 = 8.72
                                            mp.avb4 = 6.4}
                                        20.0 -> {mp.avb3 = 11.65
                                            mp.avb4 = 8.35}
                                    }
                                } else if (mp.rssnr in 2.7..4.68) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 3.82
                                            mp.avb4 = 2.48}
                                        10.0 -> {mp.avb3 = 7.62
                                            mp.avb4 = 4.92}
                                        15.0 -> {mp.avb3 = 11.28
                                            mp.avb4 = 7.38}
                                        20.0 -> {mp.avb3 = 15.11
                                            mp.avb4 = 9.82}
                                    }
                                } else if (mp.rssnr in 4.69..6.51) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 4.74
                                            mp.avb4 = 2.97}
                                        10.0 -> {mp.avb3 = 9.45
                                            mp.avb4 = 5.91}
                                        15.0 -> {mp.avb3 = 14.56
                                            mp.avb4 = 8.72}
                                        20.0 -> {mp.avb3 = 18.93
                                            mp.avb4 = 11.65}
                                    }
                                } else if (mp.rssnr in 6.52..8.56) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 6.16
                                            mp.avb4 = 3.33}
                                        10.0 -> {mp.avb3 = 12.36
                                            mp.avb4 = 6.65}
                                        15.0 -> {mp.avb3 = 18.2
                                            mp.avb4 = 10.19}
                                        20.0 -> {mp.avb3 = 24.28
                                            mp.avb4 = 13.46}
                                    }
                                } else if (mp.rssnr in 8.57..10.35) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 7.38
                                            mp.avb4 = 3.82}
                                        10.0 -> {mp.avb3 = 14.56
                                            mp.avb4 = 7.62}
                                        15.0 -> {mp.avb3 = 21.86
                                            mp.avb4 = 11.28}
                                        20.0 -> {mp.avb3 = 29.16
                                            mp.avb4 = 15.11}
                                    }
                                } else if (mp.rssnr in 10.36..12.28) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 9.45
                                            mp.avb4 = 4.19}
                                        10.0 -> {mp.avb3 = 18.93
                                            mp.avb4 = 8.35}
                                        15.0 -> {mp.avb3 = 27.94
                                            mp.avb4 = 12.36}
                                        20.0 -> {mp.avb3 = 31.41
                                            mp.avb4 = 16.75}
                                    }
                                } else if (mp.rssnr in 12.29..14.16) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 11.99
                                            mp.avb4 = 4.19}
                                        10.0 -> {mp.avb3 = 24.28
                                            mp.avb4 = 8.35}
                                        15.0 -> {mp.avb3 = 36.13
                                            mp.avb4 = 12.36}
                                        20.0 -> {mp.avb3 = 48.66
                                            mp.avb4 = 16.75}
                                    }
                                } else if (mp.rssnr in 14.17..15.88) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 13.46
                                            mp.avb4 = 4.74}
                                        10.0 -> {mp.avb3 = 27.02
                                            mp.avb4 = 9.45}
                                        15.0 -> {mp.avb3 = 41.79
                                            mp.avb4 = 14.56}
                                        20.0 -> {mp.avb3 = 54.68
                                            mp.avb4 = 18.93}
                                    }
                                } else if (mp.rssnr in 15.89..17.8) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 15.11
                                            mp.avb4 = 5.47}
                                        10.0 -> {mp.avb3 = 30.24
                                            mp.avb4 = 10.92}
                                        15.0 -> {mp.avb3 = 44.72
                                            mp.avb4 = 16.2}
                                        20.0 -> {mp.avb3 = 60.82
                                            mp.avb4 = 21.86}
                                    }
                                } else if (mp.rssnr in 17.81..50.0) {
                                    when (mp.bw) {
                                        5.0 -> {mp.avb3 = 17.49
                                            mp.avb4 = 6.16}
                                        10.0 -> {mp.avb3 = 35.0
                                            mp.avb4 = 12.36}
                                        15.0 -> {mp.avb3 = 52.51
                                            mp.avb4 = 18.2}
                                        20.0 -> {mp.avb3 = 71.88
                                            mp.avb4 = 24.28}
                                    }
                                } else {
                                    mp.avb3 = 0.0
                                    mp.avb4 = 0.0
                                }
                            }
                            if ("level" in i){
                                mp.lvl = i.split("=")[1].toInt()
                            }

                        }
                    }

                }

            }

              if (isRecording) {
                if (pingEnabled) {
                    pingThread = Thread(PingProcess())
                    pingThread?.start()
                }
                Thread(AddMeasurementToDB(mp)).start()
            }

            lastMeasurements.add(mp)
        }
        return lastMeasurements
    }

    private fun startLocationUpdates() {
        mLocationRequest = LocationRequest()
        mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        mLocationRequest.interval = interval
        mLocationRequest.fastestInterval = fastestInterval

        val builder = LocationSettingsRequest.Builder()
        builder.addLocationRequest(mLocationRequest)
        val locationSettingsRequest = builder.build()

        val settingsClient = LocationServices.getSettingsClient(this)
        settingsClient.checkLocationSettings(locationSettingsRequest)

        mFusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {

            return
        }
        mFusedLocationProviderClient!!.requestLocationUpdates(mLocationRequest, mLocationCallback,
            Looper.myLooper())
    }

    inner class MyLocalBinder : Binder() {
        fun getService() : MeasurementService {
            return this@MeasurementService
        }
    }

    private fun getLTEsignalStrength() {
        try {
            val methods: Array<Method> = SignalStrength::class.java.methods
            for (mthd in methods) {
                if (mthd.getName().equals(LTE_SIGNAL_STRENGTH)) {
                    val LTEsignalStrength = mthd.invoke(signalStrength, arrayOf<Any>()) as Int
                    Log.i(LTE_TAG, "signalStrength = $LTEsignalStrength")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(LTE_TAG, "Exception: $e")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        getNotification(applicationContext)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        pingEnabled = prefs.getBoolean("ping_enabled", pingEnabled)
        pingServer = prefs.getString("ping_server", pingServer)!!
        pingSize = prefs.getString("ping_size", pingSize.toString())!!.toInt()
        pingInterval = prefs.getString("ping_interval", pingInterval.toString())!!.toFloat()
        pingCount = prefs.getString("ping_count", pingCount.toString())!!.toInt()
        pingAdaptive = prefs.getBoolean("ping_adaptive", pingAdaptive)

        tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Listener for the signal strength.
        val mListener: PhoneStateListener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(sStrength: SignalStrength) {
                signalStrength = sStrength
                getLTEsignalStrength()
            }
        }


        // Register the listener for the telephony manager
        tm.listen(mListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)


        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startedMeasurementAt = Date()

        backgroundTask = Runnable {
            getMeasurements()
            mainHandler.postDelayed(
                backgroundTask,
                500
            )
        }
        startLocationUpdates()
        backgroundTask.run()

//        this.deleteDatabase("measurements")
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "measurements"
        ).build()

        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(backgroundTask)
        Toast.makeText(this, "Measurement service is stopped", Toast.LENGTH_LONG).show()
        stopForeground(true)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "measurements.notification.hftl.de.MEAS"
        const val CHANNEL_NAME = "Measurement Service"
    }
    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val importance = NotificationManager.IMPORTANCE_HIGH
            val notificationChannel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance)
            notificationChannel.enableVibration(true)
            notificationChannel.setShowBadge(true)
            notificationChannel.enableLights(true)
            notificationChannel.lightColor = Color.parseColor("#e8334a")
            notificationChannel.description = "Measurement in progress"
            notificationChannel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            notificationManager.createNotificationChannel(notificationChannel)
        }
    }

    private fun getNotification(context: Context) {
        createChannel(context)

        val notifyIntent = Intent(context, MainActivity::class.java)

        val title = "Measurement Service"
        val message = "RF Measurement in progress"

        notifyIntent.putExtra("title", title)
        notifyIntent.putExtra("message", message)
        notifyIntent.putExtra("notification", true)

        notifyIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        val pendingIntent = PendingIntent.getActivity(context, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotification = Notification.Builder(context, CHANNEL_ID)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setAutoCancel(true)
                .setContentTitle(title)
                .setStyle(Notification.BigTextStyle()
                    .bigText(message))
                .setContentText(message).build()
        } else {
            mNotification = Notification.Builder(context)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_MAX)
                .setContentTitle(title)
                .setStyle(Notification.BigTextStyle()
                    .bigText(message))
                .setContentText(message).build()

        }
        startForeground(999, mNotification)
    }
}