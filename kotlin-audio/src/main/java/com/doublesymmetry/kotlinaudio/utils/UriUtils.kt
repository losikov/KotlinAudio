package com.doublesymmetry.kotlinaudio.utils

import android.content.Context
import android.net.Uri
import java.io.File
import android.util.Log

object UriUtils {
    private const val TAG = "KotlinAudio-UriUtils"

    /**
     * Converts a file:// URI to a content:// URI using ImageContentProvider for Android Auto compatibility.
     * Android Auto cannot access file:// URIs from app's private storage, so we need content:// URIs.
     * 
     * @param context The application context
     * @param fileUri The file:// URI to convert (can be String or Uri)
     * @return The content:// URI if conversion succeeds, or the original URI if it's not a file:// URI or conversion fails
     */
    fun convertFileUriToContentUri(context: Context, fileUri: String?): String? {
        if (fileUri == null || fileUri.isEmpty()) return null
        
        // If it's not a file:// URI, return as-is (e.g., http://, https://)
        if (!fileUri.startsWith("file://")) {
            return fileUri
        }

        try {
            // Parse the file URI to get the file path
            val uri = Uri.parse(fileUri)
            val filePath = uri.path ?: return fileUri
            
            val file = File(filePath)
            
            // Check if file exists
            if (!file.exists()) {
                Log.w(TAG, "File does not exist: $filePath")
                return fileUri
            }

            // Get the host app's package name for ContentProvider authority
            val packageName = context.packageName
            val authority = "$packageName.trackplayer.imageprovider"
            
            // Extract relative path from the file
            val relativePath = getRelativePathFromFile(context, file)
            
            if (relativePath == null) {
                Log.w(TAG, "Could not determine relative path for file: $filePath")
                return fileUri
            }
            
            // Build content:// URI
            // Format: content://{authority}/images/{bookId}/{filename}
            // Split the relative path and append each segment separately to avoid URL encoding
            val uriBuilder = Uri.Builder()
                .scheme("content")
                .authority(authority)
            
            // Split path by "/" and append each segment separately
            relativePath.split("/").forEach { segment ->
                if (segment.isNotEmpty()) {
                    uriBuilder.appendPath(segment)
                }
            }
            
            val contentUri = uriBuilder.build()
            return contentUri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert file:// URI to content:// URI: $fileUri", e)
            // Return original URI if conversion fails
            return fileUri
        }
    }

    /**
     * Converts a file:// URI to a content:// URI using ImageContentProvider (Uri version).
     */
    fun convertFileUriToContentUri(context: Context, fileUri: Uri?): Uri? {
        if (fileUri == null) return null
        val converted = convertFileUriToContentUri(context, fileUri.toString())
        return converted?.let { Uri.parse(it) } ?: fileUri
    }

    /**
     * Attempts to convert an HTTP/HTTPS URL to a content:// URI by checking if the image is cached locally.
     * This is needed because Android Auto requires content:// URIs for artwork, not HTTP/HTTPS URLs.
     * 
     * @param context The application context
     * @param httpUri The HTTP/HTTPS URL to convert
     * @return The content:// URI if a cached version is found, null otherwise
     */
    fun convertHttpUriToContentUri(context: Context, httpUri: String?): String? {
        if (httpUri == null) {
            return null
        }
        
        try {
            val uri = Uri.parse(httpUri)
            if (uri.scheme != "http" && uri.scheme != "https") {
                return null
            }
            
            // Extract the path from the URL (e.g., "/img/books/big-book-4-android-auto.jpg")
            val urlPath = uri.path ?: return null
            
            // Try to find a cached file that matches this URL
            // Images are typically cached in: images/{bookId}/{filename}
            val cachedFile = findCachedFileByUrlPath(context, urlPath)
            
            if (cachedFile != null && cachedFile.exists()) {
                // Convert the cached file to content:// URI
                val fileUri = "file://${cachedFile.absolutePath}"
                return convertFileUriToContentUri(context, fileUri)
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert HTTP URI to content URI: $httpUri", e)
            return null
        }
    }
    
    /**
     * Attempts to find a cached file that matches the given URL path.
     * This searches common cache directories for files that might correspond to the URL.
     */
    private fun findCachedFileByUrlPath(context: Context, urlPath: String): File? {
        // Extract filename from URL path (e.g., "big-book-4-android-auto.jpg" from "/img/books/big-book-4-android-auto.jpg")
        val filename = urlPath.substringAfterLast("/")
        if (filename.isEmpty() || filename == urlPath) {
            return null
        }
        
        // Search in images/ directory structure: images/{bookId}/{filename}
        val directories = listOfNotNull(
            context.getExternalFilesDir(null),
            context.filesDir,
            context.cacheDir
        )
        
        for (baseDir in directories) {
            val imagesDir = File(baseDir, "images")
            if (imagesDir.exists() && imagesDir.isDirectory) {
                // Search in all subdirectories (bookId directories)
                val bookDirs = imagesDir.listFiles { file -> file.isDirectory }
                if (bookDirs != null) {
                    for (bookDir in bookDirs) {
                        val candidateFile = File(bookDir, filename)
                        if (candidateFile.exists()) {
                            return candidateFile
                        }
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Gets the relative path of a file from common storage directories.
     * Returns the path relative to files directory (e.g., "images/book123/thumbnail.jpg")
     */
    private fun getRelativePathFromFile(context: Context, file: File): String? {
        val absolutePath = file.absolutePath
        
        // Check common storage directories
        val directories = listOfNotNull(
            context.getExternalFilesDir(null)?.absolutePath,
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath
        )
        
        for (dirPath in directories) {
            if (absolutePath.startsWith(dirPath)) {
                val relative = absolutePath.substring(dirPath.length)
                return if (relative.startsWith("/")) relative.substring(1) else relative
            }
        }
        
        return null
    }
}

