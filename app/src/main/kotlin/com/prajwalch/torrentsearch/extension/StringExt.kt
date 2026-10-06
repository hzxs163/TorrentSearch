package com.prajwalch.torrentsearch.extension

import android.util.Base64

import java.util.Locale

private const val UNRESERVED_CHARACTERS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

/**
 * Encodes [this] the same way JavaScript's `encodeURIComponent` does, which is
 * what the search endpoints of the Chinese providers expect.
 */
fun String.encodeURIComponent(): String = buildString {
    this@encodeURIComponent.toByteArray(Charsets.UTF_8).forEach { byte ->
        val value = byte.toInt() and 0xFF

        if (UNRESERVED_CHARACTERS.contains(value.toChar())) {
            append(value.toChar())
        } else {
            append('%')
            append(value.toString(16).uppercase(Locale.US).padStart(2, '0'))
        }
    }
}

/** Encodes [this] as standard base64 without line breaks. */
fun String.encodeBase64(): String =
    Base64
        .encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        .trim()

/**
 * Encodes [this] as URL-safe base64 without padding, matching the
 * `b64.encode(urlsafe=True).rstrip('=')` style used by some providers.
 */
fun String.encodeBase64UrlNoPadding(): String =
    Base64
        .encodeToString(toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        .trim()

/** Encodes [this] as lowercase hex, as expected by some search paths. */
fun String.encodeHex(): String =
    toByteArray(Charsets.UTF_8).joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

/** Decodes [this] as base64 into a UTF-8 string, or `null` when it isn't valid. */
fun String.decodeBase64ToString(): String? = runCatching {
    String(Base64.decode(replace('-', '+').replace('_', '/'), Base64.DEFAULT), Charsets.UTF_8)
}.getOrNull()

/** Removes HTML tags and collapses the remaining whitespace of [this]. */
fun String.stripHtmlTags(): String = this
    .replace(Regex("<[^>]*>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace(Regex("\\s+"), " ")
    .trim()
