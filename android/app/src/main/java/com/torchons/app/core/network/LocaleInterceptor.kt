package com.torchons.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intercepteur OkHttp qui injecte le header `Accept-Language` sur chaque
 * requete a partir de la locale Android courante.
 *
 * Le backend (E3.5) lit ce header pour servir les labels du catalogue
 * (titres / descriptions des tasks, noms des rooms / categories) dans la
 * bonne langue. Sans ce header, le backend tombe sur son fallback "fr".
 *
 * On NE re-calcule PAS le tag par requete a la main : si l'user change
 * la langue du systeme pendant que l'app tourne, Android recree les
 * Activities et l'OkHttpClient (Hilt @Singleton) reste — donc le tag
 * suit `Locale.getDefault()` qui est mis a jour par le framework.
 *
 * Format envoye : un tag BCP-47 (ex. `fr-FR`, `en-US`). Le backend ne
 * regarde que le subtag primaire (`fr` / `en`).
 */
@Singleton
class LocaleInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        // Si le header est deja set (ex. test), on ne l'ecrase pas.
        if (req.header("Accept-Language") != null) {
            return chain.proceed(req)
        }
        val tag = Locale.getDefault().toLanguageTag().ifBlank { "fr" }
        return chain.proceed(
            req.newBuilder()
                .header("Accept-Language", tag)
                .build()
        )
    }
}
