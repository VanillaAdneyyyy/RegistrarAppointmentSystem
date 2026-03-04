package com.example.registarappointmentsystem.data.remote

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit


object RetrofitClient {
    
    // IMPORTANT: Change this based on your setup:
    // Option 1: Android Emulator - use 10.0.2.2 to access host localhost
    // private const val BASE_URL = "http://10.0.2.2:3000/"
    
    // Option 2: Physical Device - use your computer's actual IP address
    // Find your IP: Run 'ipconfig' (Windows) or 'ifconfig' (Mac/Linux)
    // Example: private const val BASE_URL = "http://192.168.1.100:3000/"
    
    // Option 3: If using ngrok or deployed server
    // private const val BASE_URL = "https://your-ngrok-url.ngrok.io/"
    
    // Windows Mobile Hotspot - works without internet
    // Hotspot IP: 192.168.137.1
    private const val BASE_URL = "http://192.168.137.1:3000/"


    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val apiService: ApiService = retrofit.create(ApiService::class.java)
}
