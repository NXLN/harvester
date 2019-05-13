package de.hft_leipzig.hfapp2.hfapp_kotlin

import android.Manifest
import android.app.*
import android.arch.persistence.room.Database
import android.arch.persistence.room.Room
import android.arch.persistence.room.RoomDatabase
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.*
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*

@Database(entities = [MeasurementPoint::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementPointDao(): MeasurementPointDao
}

class MeasurementService : Service() {
    private val interval: Long = 1000
    private val fastestInterval: Long = 500
    private lateinit var db: AppDatabase

    private val myBinder = MyLocalBinder()
    private lateinit var mNotification: Notification

    var isRecording = false
    private var sessionID = ""

    private val mainHandler = Handler()
    private lateinit var backgroundTask: Runnable

    private lateinit var tm: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var mFusedLocationProviderClient: FusedLocationProviderClient? = null
    private lateinit var mLocationRequest: LocationRequest
    private var mLastLocation: Location? = null
    var lastMeasurements: ArrayList<MeasurementPoint> = ArrayList()

    internal inner class AddMeasurementToDB(var mp: MeasurementPoint) : Runnable {
        override fun run() {
            db.measurementPointDao().insertAll(mp)
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

    fun newRecording() {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
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

    fun getMeasurements(): ArrayList<MeasurementPoint> {
        lastMeasurements = ArrayList()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return lastMeasurements
        }
        val allCellInfo = tm.allCellInfo

        if (allCellInfo.size == 0) {
            return lastMeasurements
        }
        for (cellInfo in allCellInfo) {
            if (cellInfo == null) {
                continue
            }
            val mp = MeasurementPoint(cellInfo)
            mp.sessionID = sessionID
            mp.newLocation(mLastLocation)
            if (isRecording) {
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

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Toast.makeText(this, "Service is created", Toast.LENGTH_LONG).show()
        getNotification(applicationContext)

        tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        backgroundTask = Runnable {
            getMeasurements()
            mainHandler.postDelayed(
                backgroundTask,
                1000
            )
        }
        startLocationUpdates()
        backgroundTask.run()

//        this.deleteDatabase("measurements")
//        this.deleteDatabase("database-name2")
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