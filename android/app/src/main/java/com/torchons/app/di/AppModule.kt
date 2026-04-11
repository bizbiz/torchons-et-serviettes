package com.torchons.app.di

import android.content.Context
import com.torchons.app.BuildConfig
import com.torchons.app.core.network.BackendHealthInterceptor
import com.torchons.app.core.network.RetryGetInterceptor
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.api.AuthInterceptor
import com.torchons.app.data.local.TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Module Hilt fournissant les dependances globales de l'application.
 * Configure Retrofit, OkHttp, et les autres services partages.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Fournit le gestionnaire de token JWT.
     */
    @Provides
    @Singleton
    fun provideTokenManager(
        @ApplicationContext context: Context
    ): TokenManager {
        return TokenManager(context)
    }

    /**
     * Fournit le client OkHttp.
     *
     * Ordre des intercepteurs (important) :
     * 1. AuthInterceptor : ajoute le header Authorization
     * 2. RetryGetInterceptor : retry une fois les GET en cas d'IOException
     *    (avant le BackendHealthInterceptor pour que le retry ne pollue pas
     *    l'etat de connexion sur un blip)
     * 3. BackendHealthInterceptor : notifie le moniteur de l'etat backend
     * 4. HttpLoggingInterceptor (debug uniquement) : log des requetes
     *
     * Timeouts reduits par rapport au defaut 30s/30s/30s :
     * - connect 5s : un backend qui n'accepte pas la connexion en 5s est down
     * - read 10s : 10s laisse de la marge pour les endpoints lents (stats)
     *   tout en gardant l'app reactive si le backend rame
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        retryGetInterceptor: RetryGetInterceptor,
        healthInterceptor: BackendHealthInterceptor,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(retryGetInterceptor)
            .addInterceptor(healthInterceptor)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        // Logging des requetes HTTP en mode debug
        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }

    /**
     * Fournit l'instance Retrofit configuree avec l'URL de base du backend.
     */
    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        // Ajouter un / a la fin de l'URL de base si necessaire
        val baseUrl = BuildConfig.API_BASE_URL.let {
            if (it.endsWith("/")) it else "$it/"
        }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    /**
     * Fournit le service API Retrofit.
     */
    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}
