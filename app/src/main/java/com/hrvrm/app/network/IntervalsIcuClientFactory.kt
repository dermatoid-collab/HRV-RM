package com.hrvrm.app.network

import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Intervals.icu authenticates API requests with HTTP Basic auth using the literal
 * username "API_KEY" and the athlete's personal API key as the password.
 */
object IntervalsIcuClientFactory {

    private const val BASE_URL = "https://intervals.icu/"

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun create(apiKey: String): IntervalsIcuApi {
        val authInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Authorization", Credentials.basic("API_KEY", apiKey))
                .build()
            chain.proceed(request)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(IntervalsIcuApi::class.java)
    }
}
