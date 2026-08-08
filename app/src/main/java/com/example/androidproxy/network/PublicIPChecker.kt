package com.example.androidproxy.network

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class PublicIPChecker {
    
    /**
     * 使用指定的網路獲取公網 IP
     * @param network 要使用的網路
     * @return IP 地址字串，失敗返回 null
     */
    suspend fun getPublicIP(network: android.net.Network? = null): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.ipify.org?format=text")
            val connection = if (network != null) {
                network.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
            
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "AndroidProxy/1.0")
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { inputStream ->
                    inputStream.bufferedReader().use { reader ->
                        reader.readText().trim()
                    }
                }
            } else {
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("PublicIPChecker", "Failed to get public IP", e)
            null
        }
    }
    
    /**
     * 檢查網路連通性
     * @param network 要使用的網路
     * @return 是否連通
     */
    suspend fun checkConnectivity(network: android.net.Network? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://www.google.com")
            val connection = if (network != null) {
                network.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
            
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            val responseCode = connection.responseCode
            responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            android.util.Log.e("PublicIPChecker", "Connectivity check failed", e)
            false
        }
    }
}